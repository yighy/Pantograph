package com.yighy.pantograph.drawing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yighy.pantograph.data.LayerEntity
import com.yighy.pantograph.data.PreferenceManager
import com.yighy.pantograph.data.ProjectRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Coordinates one open project.
 *
 * The work itself lives in collaborators, each owning one slice: [StrokeEngine] paints,
 * [SelectionController] handles the selection tools, [LayerController] the layer stack,
 * [HistoryCoordinator] undo/redo, [ProjectPersistence] the write-behind saving,
 * [CanvasTransformController] the viewport, [BrushAssetLoader] and [CustomBrushManager] the
 * brushes. They share the state through [DrawingSession].
 *
 * What stays here is what genuinely spans them: loading the project, the pen and cursor gestures
 * that dispatch by tool, compositing a finished stroke onto its layer, and the brush settings.
 * Everything the UI calls still comes through this class.
 */
class DrawingViewModel(
    private val repository: ProjectRepository,
    private val projectId: Long,
    private val internalFilesDir: File,
    private val preferenceManager: PreferenceManager,
    private val context: android.content.Context
) : ViewModel() {

    private val session = DrawingSession(projectId)
    val uiState: StateFlow<DrawingState> = session.state

    // Survives onCleared() (viewModelScope is already cancelled there), so the final flush of
    // pending layer saves and the history cache cleanup actually run when leaving the screen.
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val engine = StrokeEngine()
    private val persistence = ProjectPersistence(session, repository, projectId, internalFilesDir, viewModelScope, persistScope)
    private val history = HistoryCoordinator(
        session,
        DrawingHistoryManager(context.cacheDir, repository, projectId, persistScope),
        persistence,
        viewModelScope
    )
    private val layers = LayerController(session, repository, projectId, history, persistence, viewModelScope)
    private val selection = SelectionController(session, engine, history, persistence, repository, projectId, viewModelScope)
    private val viewport = CanvasTransformController(session, viewModelScope)
    private val brushAssets = BrushAssetLoader(session, viewModelScope)
    private val referenceImage = ReferenceImageController(session, persistence, viewModelScope)
    private val presets = BrushPresetController(
        session, repository, brushAssets, persistence, preferenceManager, context, viewModelScope
    )

    init {
        loadProject()
        observeSettings()
    }

    // ============================ Project loading ============================

    private fun loadProject() {
        viewModelScope.launch {
            val project = repository.getProjectById(projectId)
            if (project == null) {
                // Nothing to wait for. Leaving isLoading set would hold the placeholder up
                // over a canvas that is never going to be filled.
                session.update { it.copy(isLoading = false) }
                return@launch
            }
            session.update {
                it.copy(
                    projectId = projectId,
                    projectName = project.name,
                    canvasWidth = project.width,
                    canvasHeight = project.height,
                    selectedWidth = project.lastBrushSize,
                    brushSoftness = project.lastBrushSoftness,
                    brushOpacity = project.lastBrushOpacity,
                    brushFlow = project.lastBrushFlow,
                    brushSpacing = project.lastBrushSpacing,
                    brushSmoothing = project.lastBrushSmoothing,
                    selectedColor = Color(project.lastBrushColor),
                    brushRotation = project.lastBrushRotation,
                    brushRotationJitter = project.lastBrushRotationJitter,
                    sizeJitter = project.lastSizeJitter,
                    scatterJitter = project.lastScatterJitter,
                    flowJitter = project.lastFlowJitter,
                    rotationFollow = project.lastRotationFollow,
                    brushTipUri = project.lastBrushTipUri,
                    brushTextureUri = project.lastBrushTextureUri,
                    sizeMultiplier = project.lastSizeMultiplier,
                    cursorSensitivity = project.lastCursorSensitivity
                )
            }

            // Load saved project brush bitmaps if they exist
            project.lastBrushTipUri?.let { setBrushTip(context, it) }
            project.lastBrushTextureUri?.let { setBrushTexture(context, it) }

            // Load saved reference image
            project.referenceImageUri?.let { uri ->
                referenceImage.restore(
                    context,
                    uri,
                    Offset(project.referenceImageOffsetX, project.referenceImageOffsetY),
                    project.referenceImageScale,
                    project.referenceImageRotation
                )
            }

            // Stand-in for the layers while they decode: the thumbnail the home grid already
            // renders from, so it is on disk and at most 512px on its long edge. Launched
            // beside the layer load rather than before it - this is a courtesy, and it must
            // not hold up the real pixels by even one dispatch.
            project.thumbnailPath?.let { path ->
                launch {
                    val preview = withContext(Dispatchers.IO) {
                        File(path).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
                    }
                    // Lost the race: painting the thumbnail over layers that are already up
                    // would be a visible step backwards, from sharp to blurry.
                    if (preview != null && session.value.isLoading) {
                        session.update { it.copy(loadingPreview = preview) }
                    }
                }
            }

            launch {
                repository.getLayersForProject(projectId).collectLatest { loaded ->
                    if (loaded.isEmpty()) return@collectLatest

                    val currentActiveId = session.value.activeLayerId
                    val savedActiveId = project.lastActiveLayerId
                    val newActiveId = when {
                        // Session already has a valid active layer: keep it
                        currentActiveId != -1L && loaded.any { it.id == currentActiveId } -> currentActiveId
                        // Fresh open: restore the layer the user was on last time
                        savedActiveId != -1L && loaded.any { it.id == savedActiveId } -> savedActiveId
                        // New/legacy project: default to the topmost layer, not the bottom
                        else -> loaded.last().id
                    }

                    // Decoded in parallel: the PNGs are independent files, and reading them
                    // one after another made the wait scale with the layer count - a stack
                    // deep enough to be worth opening was the slowest to appear.
                    val decoded = coroutineScope {
                        loaded.filterNot { session.layerBitmaps.containsKey(it.id) }
                            .map { layer ->
                                async { layer.id to layers.loadBitmap(layer, project.width, project.height) }
                            }
                            .awaitAll()
                    }
                    decoded.forEach { (id, bitmap) -> session.layerBitmaps[id] = bitmap }

                    session.update { state ->
                        state.copy(
                            layers = loaded,
                            activeLayerId = newActiveId,
                            layerBitmaps = session.layerBitmaps.toMap(),
                            isLoading = false,
                            // Dropped in the same emission that publishes the layers, so the
                            // swap is one frame with no white gap between the two.
                            loadingPreview = null
                        )
                    }
                }
            }

            presets.observeLibrary()
        }
    }

    private fun observeSettings() {
        preferenceManager.historyLimit
            .onEach { limit -> session.update { it.copy(historyLimit = limit) } }
            .launchIn(viewModelScope)

        preferenceManager.fabDragThreshold
            .onEach { threshold -> session.update { it.copy(fabDragThreshold = threshold) } }
            .launchIn(viewModelScope)

        preferenceManager.satelliteGateSensitivity
            .onEach { sensitivity -> session.update { it.copy(satelliteGateSensitivity = sensitivity) } }
            .launchIn(viewModelScope)

        preferenceManager.pinnedTools
            .onEach { names -> session.update { it.copy(pinnedTools = PinnableTool.fromNames(names)) } }
            .launchIn(viewModelScope)

        preferenceManager.colorHistory
            .onEach { history ->
                val colors = history.mapNotNull {
                    try { Color(android.graphics.Color.parseColor(it)) } catch (e: Exception) { null }
                }
                session.update { it.copy(colorHistory = colors) }
            }
            .launchIn(viewModelScope)

        preferenceManager.colorPickerIsSliderMode
            .onEach { isSlider -> session.update { it.copy(isColorPickerSliderMode = isSlider) } }
            .launchIn(viewModelScope)

        preferenceManager.cursorThickness
            .onEach { thickness -> session.update { it.copy(cursorThickness = thickness) } }
            .launchIn(viewModelScope)

        preferenceManager.fillTolerance
            .onEach { tolerance -> session.update { it.copy(fillTolerance = tolerance) } }
            .launchIn(viewModelScope)

        // The brush settings that are also global preferences. Walked from one list rather
        // than spelled out here, because BrushPresetController has to write the same set back
        // when a preset is loaded - see MirroredBrushSetting for what a mismatch costs.
        MirroredBrushSetting.observeAll(preferenceManager, session, viewModelScope)

        preferenceManager.offscreenCursorArrow
            .onEach { enabled -> session.update { it.copy(showOffscreenCursorArrow = enabled) } }
            .launchIn(viewModelScope)
    }

    fun setCanvasSize(size: IntSize) {
        if (size.width <= 0 || size.height <= 0) return

        if (engine.resizeTarget(size.width, size.height)) {
            session.update { it.copy(strokeBitmap = engine.strokeBitmap) }
        }

        if (session.value.cursorPosition == Offset.Zero) {
            val center = Offset(size.width / 2f, size.height / 2f)
            session.update { it.copy(cursorPosition = center, brushPosition = center) }
        }
    }

    // ============================ Cursor & pen ============================

    fun moveCursor(delta: Offset) {
        val state = session.value
        val movement = engine.smoothMovement(delta, state)

        val newX = (state.cursorPosition.x + movement.x).coerceIn(0f, state.canvasWidth.toFloat())
        val newY = (state.cursorPosition.y + movement.y).coerceIn(0f, state.canvasHeight.toFloat())
        val newPosition = Offset(newX, newY)

        // Lazy/rope mode: the brush trails the cursor, pulled along once the rope goes taut
        val newBrushPosition = if (state.isLazyModeActive) {
            val vector = newPosition - state.brushPosition
            val distance = vector.getDistance()
            if (distance > state.lazyRadius) {
                newPosition - (vector / distance) * state.lazyRadius
            } else {
                state.brushPosition
            }
        } else {
            newPosition
        }

        // Keep the stroke distance up to date: the FAB uses it to tell
        // "drawing" apart from "dragging the button" (see HoverDrawButton)
        val travelled = (newBrushPosition - state.brushPosition).getDistance()
        var needsRedraw = false

        if (state.isPenDown) {
            val mode = state.drawingMode
            when {
                state.isEyeDropperMode -> pickColorFromCanvas(newBrushPosition)

                mode.isSelectionTool() -> selection.updateDrag(newBrushPosition, state)

                mode is DrawingMode.Gradient -> {
                    val start = state.currentPath?.points?.get(0) ?: newBrushPosition
                    engine.drawGradient(start, newBrushPosition, state)
                    session.update { it.copy(
                        cursorPosition = newPosition,
                        brushPosition = newBrushPosition,
                        currentPath = it.currentPath?.copy(points = listOf(start, newBrushPosition)),
                        renderVersion = it.renderVersion + 1
                    ) }
                    engine.addDistance(travelled)
                    return
                }

                mode is DrawingMode.Freehand || mode is DrawingMode.Eraser -> {
                    engine.extendSmoothed(newBrushPosition, state)
                    needsRedraw = true
                }

                mode is DrawingMode.StraightLine || mode is DrawingMode.StraightLineEraser -> {
                    val start = state.currentPath?.points?.get(0) ?: newBrushPosition
                    // Repaint the whole preview line: an accurate one, custom tips and jitter included
                    engine.drawStraightLine(start, newBrushPosition, state)
                    session.update { it.copy(
                        cursorPosition = newPosition,
                        brushPosition = newBrushPosition,
                        currentPath = it.currentPath?.copy(points = listOf(start, newBrushPosition)),
                        renderVersion = it.renderVersion + 1
                    ) }
                    engine.addDistance(travelled)
                    return
                }
            }
        }

        session.update { state2 ->
            state2.copy(
                cursorPosition = newPosition,
                brushPosition = newBrushPosition,
                renderVersion = if (needsRedraw) state2.renderVersion + 1 else state2.renderVersion
            )
        }
        if (state.isPenDown) engine.addDistance(travelled)
    }

    fun togglePen() {
        setPenDown(!session.value.isPenDown)
    }

    fun setPenDown(down: Boolean) {
        val state = session.value

        // Priority to Eye Dropper
        if (down && state.isEyeDropperMode) {
            pickColorFromCanvas(state.brushPosition)
            return
        }

        if (state.drawingMode.isSelectionTool()) {
            selection.handlePen(down, state)
            return
        }

        if (state.drawingMode is DrawingMode.BucketFill) {
            if (down) {
                // Flood fill mutates scattered pixels, so the whole layer is frozen
                history.save(mapOf(state.activeLayerId to SnapshotSpec.FullMutated))
                updateColorHistory(state.selectedColor)
                performFloodFill(state.brushPosition, state.selectedColor.copy(alpha = state.brushOpacity))
            }
            return
        }

        if (state.isPenDown == down) return

        if (down) {
            // Start of stroke. History is saved at pen-up (commit time), once the stroke's
            // bounding box is known, so only the touched region gets snapshotted.
            engine.beginStroke(state.brushPosition, state)
            updateColorHistory(state.selectedColor)

            // The shape tools paint their whole preview per sample, so a dot at pen-down would
            // only be wiped by the next one. Kept outside the state update below: that lambda
            // re-runs under contention, and a re-run would stamp twice.
            if (state.drawingMode !is DrawingMode.BucketFill &&
                state.drawingMode !is DrawingMode.StraightLine &&
                state.drawingMode !is DrawingMode.Gradient
            ) {
                engine.drawSegment(state.brushPosition, state.brushPosition, state)
            }

            session.update {
                it.copy(
                    isPenDown = true,
                    strokeBitmap = engine.strokeBitmap, // Ensure UI has the latest reference
                    currentPath = DrawingPath(
                        points = listOf(it.brushPosition),
                        color = it.selectedColor,
                        width = it.selectedWidth,
                        mode = it.drawingMode,
                        opacity = it.brushOpacity,
                        flow = it.brushFlow,
                        softness = it.brushSoftness,
                        spacing = it.brushSpacing,
                        rotation = it.brushRotation
                    ),
                    renderVersion = it.renderVersion + 1
                )
            }
        } else {
            // End of stroke - Commit the stroke to the active layer
            if (state.drawingMode is DrawingMode.StraightLine || state.drawingMode is DrawingMode.StraightLineEraser) {
                commitCurrentPath()
            }

            // Exactly one history entry per completed stroke, pushed before the layer is
            // mutated so it freezes the pre-stroke pixels of just the stroke's bounding box.
            // Pushed even for an empty stroke: abortCurrentStroke() pops unconditionally.
            history.save(strokeSnapshotSpec(session.value))
            commitStrokeToLayer()

            engine.endStroke()
            session.update { it.copy(isPenDown = false, currentPath = null) }
            persistence.scheduleLayerSave(state.activeLayerId)
            persistence.touchProject()
        }
    }

    /** Repaints the finished straight line from its two endpoints, so preview and commit match. */
    private fun commitCurrentPath() {
        val state = session.value
        val path = state.currentPath ?: return
        if (path.points.size < 2) return

        engine.drawStraightLine(path.points.first(), path.points.last(), state)
        session.bumpRender()
    }

    /**
     * Snapshot spec for the stroke about to be committed: the active layer cropped to the
     * stroke's dirty rect. Gradient paints the whole canvas, so it snapshots the full layer.
     */
    private fun strokeSnapshotSpec(state: DrawingState): Map<Long, SnapshotSpec> {
        val layerBitmap = session.layerBitmaps[state.activeLayerId] ?: return emptyMap()
        if (state.drawingMode is DrawingMode.Gradient) {
            return mapOf(state.activeLayerId to SnapshotSpec.FullMutated)
        }
        val region = engine.dirtyRegion(layerBitmap.width, layerBitmap.height) ?: return emptyMap()
        return mapOf(state.activeLayerId to SnapshotSpec.Region(region))
    }

    private fun commitStrokeToLayer() {
        val state = session.value
        val layerBitmap = session.layerBitmaps[state.activeLayerId] ?: return
        val stroke = engine.strokeBitmap ?: return

        // Canvas-anchored paper grain: mask the whole stroke once before compositing.
        // The stroke bitmap is erased at the next pen-down, so mutating it here is safe.
        engine.applyTextureMask(Canvas(stroke), stroke.width, stroke.height, state)

        // Active selection: clip the stroke to the selected area (applies to brush,
        // eraser, straight lines and gradients alike)
        state.selectionMask?.let { mask ->
            Canvas(stroke).drawBitmap(mask, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        }

        val canvas = Canvas(layerBitmap)
        val paint = Paint().apply {
            isAntiAlias = true
            alpha = (state.brushOpacity * 255).toInt()
            if (state.drawingMode is DrawingMode.Eraser || state.drawingMode is DrawingMode.StraightLineEraser) {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            }
        }
        canvas.drawBitmap(stroke, 0f, 0f, paint)
    }

    private fun performFloodFill(pos: Offset, color: Color) {
        viewModelScope.launch(Dispatchers.Default) {
            val state = session.value
            val activeId = state.activeLayerId
            val bitmap = session.layerBitmaps[activeId] ?: return@launch

            val x = pos.x.toInt().coerceIn(0, bitmap.width - 1)
            val y = pos.y.toInt().coerceIn(0, bitmap.height - 1)
            val targetColor = bitmap.getPixel(x, y)
            val replacementColor = color.toArgb()

            if (targetColor == replacementColor) return@launch

            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            // Active selection: the fill can't leak outside the selected area
            val maskPixels = state.selectionMask?.let { mask ->
                IntArray(width * height).also { mask.getPixels(it, 0, width, 0, 0, width, height) }
            }

            FloodFill.fill(pixels, width, height, x, y, targetColor, replacementColor, state.fillTolerance, maskPixels)
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

            withContext(Dispatchers.Main) {
                session.bumpRender()
                persistence.scheduleLayerSave(activeId)
                persistence.touchProject()
            }
        }
    }

    fun getCurrentStrokeDistance(): Float = engine.strokeDistance

    // ============================ Brush previews ============================

    /** The brush in hand, rendered on the engine's demo stroke. */
    fun renderBrushPreview(widthPx: Int, heightPx: Int): Bitmap =
        engine.renderPreview(session.value, widthPx, heightPx)

    /**
     * Same demo stroke for a saved preset rather than the brush in hand, drawn in [color] so a
     * list thumbnail stays legible whatever colour happens to be selected - the thumbnail is
     * there to show the brush's shape and texture, not its colour.
     *
     * Custom tips and textures come from the preset asset cache, so a thumbnail shows the
     * brush's real stamp rather than a default round one. A preset whose asset has not been
     * decoded yet renders without it and re-renders when the preload bumps
     * [DrawingState.brushAssetsVersion].
     */
    fun renderPresetPreview(config: BrushConfig, color: Color, widthPx: Int, heightPx: Int): Bitmap {
        val current = session.value
        // Fitted to the strip rather than drawn true to size - see BrushPreviewScale. The
        // preset's multiplier is folded in here, so the state below carries 1x: applying it
        // again downstream would scale a size that has already been fitted to the strip.
        val displaySize = BrushPreviewScale.displaySize(config.effectiveSize, heightPx)

        // Prefer the live brush's already-decoded assets, fall back to the preset cache.
        val tip = if (config.tipUri == current.brushTipUri) current.brushTipBitmap
                  else config.tipUri?.let { brushAssets.cachedTip(it) }
        val mask = if (config.textureUri == current.brushTextureUri) current.brushTextureMask
                   else config.textureUri?.let { brushAssets.cachedMask(it) }
        val state = current.copy(
            selectedColor = color,
            selectedWidth = displaySize,
            sizeMultiplier = 1f,
            brushSoftness = config.softness,
            brushOpacity = config.opacity,
            brushFlow = config.flow,
            brushSpacing = config.spacing,
            brushSmoothing = config.smoothing,
            brushRotation = config.rotation,
            brushRotationJitter = config.rotationJitter,
            sizeJitter = config.sizeJitter,
            scatterJitter = config.scatterJitter,
            brushTipUri = config.tipUri.takeIf { tip != null },
            brushTipBitmap = tip,
            brushTextureUri = config.textureUri.takeIf { mask != null },
            brushTextureMask = mask,
            velocityEnabled = config.velocityEnabled,
            velocitySizeAmount = config.velocitySize,
            velocityFlowAmount = config.velocityFlow,
            velocityScatterAmount = config.velocityScatter
        )
        return engine.renderPreview(state, widthPx, heightPx)
    }

    // ============================ History ============================

    /**
     * Specialized method for the FAB drag-to-move feature.
     * Silently removes the last history state and restores bitmaps.
     */
    fun abortCurrentStroke() = history.abortLastEntry()

    fun undo() {
        // Undo while a selection is floating cancels the move; a closed selection is kept
        // (it's a drawing mask now) and undo applies to strokes as usual
        if (session.value.floatingBitmap != null) { selection.cancel(); return }
        history.undo()
    }

    fun redo() {
        if (session.value.floatingBitmap != null) { selection.cancel(); return }
        history.redo()
    }

    // ============================ Selection ============================

    fun invertSelection() = selection.invert()
    fun liftSelection(cut: Boolean) = selection.lift(cut)
    fun duplicateSelection() = selection.duplicate()
    fun deleteSelection() = selection.delete()
    fun commitSelection() = selection.commit()
    fun cancelSelection() = selection.cancel()
    fun clearSelection() = selection.clear()
    fun setSelectionScale(scale: Float) = selection.setScale(scale)
    fun setSelectionRotation(degrees: Float) = selection.setRotation(degrees)

    fun importImageAsLayer(context: android.content.Context, uri: String) = selection.importImage(context, uri)

    // ============================ Layers ============================

    fun addLayer(name: String) = layers.add(name)
    fun deleteLayer(layer: LayerEntity) = layers.delete(layer)
    fun toggleLayerVisibility(layer: LayerEntity) = layers.toggleVisibility(layer)
    fun renameLayer(layer: LayerEntity, newName: String) = layers.rename(layer, newName)
    fun setLayerOpacity(layer: LayerEntity, opacity: Float) = layers.setOpacity(layer, opacity)
    fun persistLayerOpacity(layerId: Long) = layers.persistOpacity(layerId)
    fun duplicateLayer(layer: LayerEntity) = layers.duplicate(layer)
    fun mergeDown(layer: LayerEntity) = layers.mergeDown(layer)
    fun reorderLayers(fromIndex: Int, toIndex: Int) = layers.reorder(fromIndex, toIndex)
    fun clearLayer(id: Long) = layers.clear(id)

    fun selectLayer(id: Long) {
        // A floating selection deliberately survives a layer change: that IS how you paste
        // into another layer. finalizeSelection() would have stamped it back into the layer
        // you are leaving, which is why this only ever worked when switching to a brand new
        // layer (addLayer doesn't finalize). An unfinished lasso is still dropped, though -
        // a half-drawn path has no meaning on a different layer.
        selection.dropUnfinishedTrace()
        session.update { it.copy(activeLayerId = id) }
        viewModelScope.launch { persistence.saveBrushSettings() }
    }

    // ============================ Canvas viewport ============================

    fun updateZoom(s: Float) = viewport.zoom(s)
    fun updateTransform(zoom: Float, pan: Offset, rotation: Float) = viewport.transform(zoom, pan, rotation)
    fun fitToScreen(screenWidth: Float, screenHeight: Float, animate: Boolean = false) =
        viewport.fitToScreen(screenWidth, screenHeight, animate)
    fun requestFitToScreen() = viewport.requestFitToScreen()

    // ============================ Tool selection ============================

    fun setDrawingMode(mode: DrawingMode) {
        selection.finalizeActive()
        session.update { it.copy(drawingMode = mode, isEyeDropperMode = false) }
    }

    fun toggleDrawingMode() {
        selection.finalizeActive()
        session.update {
            val newMode = when (it.drawingMode) {
                is DrawingMode.Freehand -> DrawingMode.StraightLine
                is DrawingMode.StraightLine -> DrawingMode.Freehand
                is DrawingMode.Eraser -> DrawingMode.StraightLineEraser
                is DrawingMode.StraightLineEraser -> DrawingMode.Eraser
                else -> DrawingMode.StraightLine
            }
            it.copy(drawingMode = newMode, isEyeDropperMode = false)
        }
    }

    fun setBucketFillMode() {
        selection.finalizeActive()
        session.update {
            val newMode = if (it.drawingMode is DrawingMode.BucketFill) DrawingMode.Freehand else DrawingMode.BucketFill
            it.copy(drawingMode = newMode, isEyeDropperMode = false)
        }
    }

    fun toggleEraser() {
        selection.finalizeActive()
        session.update {
            val newMode = when (it.drawingMode) {
                is DrawingMode.Eraser -> DrawingMode.Freehand
                is DrawingMode.StraightLineEraser -> DrawingMode.StraightLine
                is DrawingMode.StraightLine -> DrawingMode.StraightLineEraser
                else -> DrawingMode.Eraser
            }
            it.copy(drawingMode = newMode, isEyeDropperMode = false)
        }
    }

    // ============================ Colour ============================

    private fun updateColorHistory(color: Color) {
        val current = session.value.colorHistory
        if (current.firstOrNull() == color) return
        val newHistory = (listOf(color) + current).distinct().take(6)
        session.update { it.copy(colorHistory = newHistory) }
        viewModelScope.launch {
            preferenceManager.setColorHistory(newHistory.map { String.format("#%06X", (0xFFFFFF and it.toArgb())) })
        }
    }

    fun toggleEyeDropper() {
        session.update { it.copy(isEyeDropperMode = !it.isEyeDropperMode) }
    }

    fun pickColorFromCanvas(pos: Offset) {
        val state = session.value
        val x = pos.x.toInt()
        val y = pos.y.toInt()

        val ordered = state.layers.sortedByDescending { it.zIndex }
        for (layer in ordered) {
            if (!layer.isVisible) continue
            val bitmap = session.layerBitmaps[layer.id] ?: continue
            if (x in 0 until bitmap.width && y in 0 until bitmap.height) {
                val pixel = bitmap.getPixel(x, y)
                if (android.graphics.Color.alpha(pixel) > 0) {
                    selectColor(Color(pixel))
                    return
                }
            }
        }
        // No layer has an opaque pixel here: the visible canvas is the plain white
        // background underneath (DrawingCanvas / exportProject both paint it white).
        selectColor(Color.White)
    }

    fun pickColorFromReference(relativePos: Offset) {
        val bitmap = session.value.referenceImage?.bitmap ?: return

        val x = (relativePos.x * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val y = (relativePos.y * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)

        selectColor(Color(bitmap.getPixel(x, y)))
    }

    // ============================ Brush settings ============================

    /**
     * Applies a brush-related state change and persists it as the project's last-used brush
     * setting. [persistPreference] is used by the handful of settings that are also saved as a
     * global default (via [preferenceManager]) rather than just per-project.
     */
    private fun updateBrushSetting(persistPreference: (suspend () -> Unit)? = null, update: (DrawingState) -> DrawingState) {
        session.update(update)
        if (persistPreference != null) viewModelScope.launch { persistPreference() }
        // Throttled: these arrive one per pointer event while a satellite gate is being swept,
        // and each unthrottled save was a project read plus a project write.
        persistence.scheduleBrushSettingsSave()
    }

    /** Applies a brush setting that is stored as a global default rather than per project. */
    private fun updatePreference(persist: suspend () -> Unit, update: (DrawingState) -> DrawingState) {
        session.update(update)
        viewModelScope.launch { persist() }
    }

    fun selectColor(color: Color) = updateBrushSetting { it.copy(selectedColor = color, isEyeDropperMode = false) }

    fun selectWidth(width: Float) = updateBrushSetting { it.copy(selectedWidth = width) }

    fun setBrushSoftness(softness: Float) = updateBrushSetting({ preferenceManager.setBrushSoftness(softness) }) { it.copy(brushSoftness = softness) }

    fun setBrushSmoothing(smoothing: Float) = updateBrushSetting({ preferenceManager.setBrushSmoothing(smoothing) }) { it.copy(brushSmoothing = smoothing) }

    fun setBrushOpacity(opacity: Float) = updateBrushSetting { it.copy(brushOpacity = opacity) }
    fun setBrushFlow(flow: Float) = updateBrushSetting { it.copy(brushFlow = flow) }
    fun setBrushSpacing(spacing: Float) = updateBrushSetting { it.copy(brushSpacing = spacing) }
    fun setBrushRotation(rotation: Float) = updateBrushSetting { it.copy(brushRotation = rotation) }

    fun setRotationJitter(jitter: Float) = updateBrushSetting({ preferenceManager.setRotationJitter(jitter) }) { it.copy(brushRotationJitter = jitter) }

    fun setSizeJitter(jitter: Float) = updateBrushSetting { it.copy(sizeJitter = jitter) }

    /** Scales the brush size past the slider's ceiling. 1x paints at the size as set. */
    fun setSizeMultiplier(multiplier: Float) = updateBrushSetting {
        it.copy(sizeMultiplier = multiplier.coerceIn(0f, SizeMultiplierScale.MAX))
    }

    fun setFlowJitter(amount: Float) = updatePreference({ preferenceManager.setFlowJitter(amount) }) { it.copy(flowJitter = amount) }

    fun setRotationFollow(amount: Float) = updatePreference({ preferenceManager.setRotationFollow(amount) }) { it.copy(rotationFollow = amount) }

    fun setScatterJitter(amount: Float) = updatePreference({ preferenceManager.setScatterJitter(amount) }) { it.copy(scatterJitter = amount) }

    fun setVelocitySize(amount: Float) = updatePreference({ preferenceManager.setVelocitySize(amount) }) { it.copy(velocitySizeAmount = amount) }

    fun setVelocityFlow(amount: Float) = updatePreference({ preferenceManager.setVelocityFlow(amount) }) { it.copy(velocityFlowAmount = amount) }

    fun setVelocityScatter(amount: Float) = updatePreference({ preferenceManager.setVelocityScatter(amount) }) { it.copy(velocityScatterAmount = amount) }

    fun setVelocityEnabled(enabled: Boolean) = updatePreference({ preferenceManager.setVelocityEnabled(enabled) }) { it.copy(velocityEnabled = enabled) }

    fun setCursorSensitivity(s: Float) = updateBrushSetting { it.copy(cursorSensitivity = s.coerceIn(0.1f, 1.0f)) }

    fun setFillTolerance(tolerance: Float) {
        viewModelScope.launch { preferenceManager.setFillTolerance(tolerance) }
    }

    fun setColorPickerSliderMode(isSlider: Boolean) {
        viewModelScope.launch { preferenceManager.setColorPickerSliderMode(isSlider) }
    }

    fun saveFabPosition(x: Float, y: Float) {
        viewModelScope.launch { preferenceManager.setFabPosition(x, y) }
    }

    /** Pins or unpins a tool on the quick-access satellite. */
    fun togglePinnedTool(tool: PinnableTool) {
        val next = PinnableTool.togglePin(session.value.pinnedTools, tool)
        viewModelScope.launch { preferenceManager.setPinnedTools(PinnableTool.toNames(next)) }
    }

    fun toggleLazyMode() {
        session.update { it.copy(isLazyModeActive = !it.isLazyModeActive) }
    }

    fun setLazyRadius(radius: Float) {
        session.update { it.copy(lazyRadius = radius) }
    }

    fun setBrushTip(context: android.content.Context, uri: String?) = brushAssets.setBrushTip(context, uri)

    fun setBrushTexture(context: android.content.Context, uri: String?) = brushAssets.setBrushTexture(context, uri)

    // ============================ Brush presets ============================

    fun saveCurrentAsCustomBrush(name: String, onSaved: () -> Unit = {}) = presets.saveCurrent(name, onSaved)
    fun updateSelectedBrush() = presets.updateSelected()
    fun renameCustomBrush(brush: BrushConfig, newName: String) = presets.rename(brush, newName)
    fun deleteCustomBrush(brush: BrushConfig) = presets.delete(brush)
    fun selectCustomBrush(brush: BrushConfig) = presets.select(brush)
    fun swapToPreviousBrush() = presets.swapToPrevious()

    fun createBrushFolder(name: String) = presets.createFolder(name)
    fun renameBrushFolder(folder: BrushFolder, newName: String) = presets.renameFolder(folder, newName)
    fun deleteBrushFolder(folder: BrushFolder) = presets.deleteFolder(folder)
    fun moveBrushToFolder(brush: BrushConfig, folderId: Long?) = presets.moveToFolder(brush, folderId)

    // ============================ Reference image ============================

    fun setReferenceImage(context: android.content.Context, uri: String) = referenceImage.set(context, uri)
    fun removeReferenceImage() = referenceImage.remove()
    fun updateReferenceImage(pan: Offset, zoom: Float, rotation: Float) = referenceImage.update(pan, zoom, rotation)

    // ============================ Saving & export ============================

    suspend fun saveProjectBrushSettings() = persistence.saveBrushSettings()

    fun flushPendingSaves() = persistence.flushNow()

    fun manualSave() = persistence.generateThumbnail(session.value)

    fun exportProject(context: android.content.Context, format: String) = persistence.export(context, format)

    override fun onCleared() {
        history.clearAll()
        brushAssets.release()
        // viewModelScope is already cancelled here; the flush runs on persistScope so the
        // last strokes aren't lost when leaving the screen
        persistence.flushNow()
    }
}
