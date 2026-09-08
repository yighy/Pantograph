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
import androidx.datastore.preferences.core.Preferences
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/**
 * How long a brush setting has to hold still before it is written to the preference store.
 *
 * Long enough that a slider drag costs one write instead of one per frame, short enough that
 * nothing is at risk in the gap: a setting is already live in [DrawingSession] the moment it
 * changes, and the wait only governs when it reaches disk.
 */
private const val PREFERENCE_SETTLE_MS = 250L

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

    /**
     * Where the pointer stood when the current stroke began. History is pushed at pen-up, by
     * which time the cursor sits at the far end of the stroke - and the far end is no use to
     * someone who undid it in order to draw it again.
     */
    private var strokeStartAnchor: CursorAnchor? = null

    /**
     * How long the recoil takes. Shorter than undo's, because it runs once per stroke rather
     * than once in a while - but not instant: seeing the cursor travel is what stops you losing
     * it, where a teleport between two marks reads as it having jumped somewhere at random.
     */
    private val RECOIL_MS = 110L

    /**
     * Whether the press in progress has put anything on the undo stack yet.
     *
     * [abortCurrentStroke] used to pop regardless, on the assumption that every press ends by
     * pushing something - true when the only way to refuse the pen was to be in a mode that
     * the button already knew not to abort. A locked layer refuses it too, and then the pop
     * took back whatever happened to be on top instead: locking a layer and then moving the
     * button quietly unlocked it again.
     */
    private var pressPushedEntry = false

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
        // Closes the loop the constructors cannot: history has to exist before the layer
        // controller, and only the layer controller can put a trace anywhere.
        history.onStrokeUndone = { layers.leaveTrace(it) }
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
            // The brush itself is not read from here any more - it is global, and the
            // mirrors in observeSettings put it in hand. Only what genuinely belongs to this
            // project is restored: its size, the colour it was left on, and the cursor feel.
            session.update {
                it.copy(
                    projectId = projectId,
                    projectName = project.name,
                    canvasWidth = project.width,
                    canvasHeight = project.height,
                    selectedColor = Color(project.lastBrushColor),
                    cursorSensitivity = project.lastCursorSensitivity
                )
            }

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

        preferenceManager.undoRestoresCursor
            .onEach { enabled -> session.update { it.copy(undoRestoresCursor = enabled) } }
            .launchIn(viewModelScope)

        preferenceManager.keepUndoneStrokes
            .onEach { enabled -> session.update { it.copy(keepUndoneStrokes = enabled) } }
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

        preferenceManager.fillGrow
            .onEach { grow -> session.update { it.copy(fillGrow = grow) } }
            .launchIn(viewModelScope)

        // The brush settings that are also global preferences. Walked from one list rather
        // than spelled out here, because BrushPresetController has to write the same set back
        // when a preset is loaded - see MirroredBrushSetting for what a mismatch costs.
        MirroredBrushSetting.observeAll(preferenceManager, session, viewModelScope)

        // The tip and the texture belong to the brush like the rest, but they cannot go through
        // MirroredBrushSetting: that walks pure state, and these have to be decoded before they
        // mean anything. Same mirror, done by hand.
        preferenceManager.brushTipUri
            .onEach { uri -> brushAssets.setBrushTip(context, uri) }
            .launchIn(viewModelScope)

        preferenceManager.brushTextureUri
            .onEach { uri -> brushAssets.setBrushTexture(context, uri) }
            .launchIn(viewModelScope)

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
        history.cancelCursorGlide()
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

        // The path tool drags a point rather than painting, and does it with the pen up - so
        // this cannot live in the isPenDown block below.
        if (state.drawingMode is DrawingMode.Path && state.grabbedPathPoint in state.pathPoints.indices) {
            val moved = state.pathPoints.toMutableList()
            moved[state.grabbedPathPoint] = moved[state.grabbedPathPoint].copy(position = newBrushPosition)
            session.update { it.copy(
                cursorPosition = newPosition,
                brushPosition = newBrushPosition,
                pathPoints = moved
            ) }
            repaintPathPreview()
            return
        }

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
        // In the path tool the pen is a hold, so there is no latched state to invert. The
        // canvas tap has no press and release of its own to offer, so it alternates instead:
        // hold the button for a placement you can adjust, tap the canvas for a quick one.
        val state = session.value
        if (state.drawingMode is DrawingMode.Path) {
            if (state.grabbedPathPoint >= 0) dropPathPoint() else pressPathPoint(state)
            return
        }
        setPenDown(!state.isPenDown)
    }

    fun setPenDown(down: Boolean) {
        val state = session.value
        // Per press, and before the guards below, several of which return without pushing.
        if (down) pressPushedEntry = false

        // Priority to Eye Dropper
        if (down && state.isEyeDropperMode) {
            pickColorFromCanvas(state.brushPosition)
            return
        }

        // Everything past the eyedropper above writes to the active layer - the selection
        // tools included, since what they arm ends in moving or deleting its pixels. Reading
        // a colour off a locked layer stays allowed, which is why the guard sits here and not
        // at the top of the function.
        if (down && state.layers.find { it.id == state.activeLayerId }?.isLocked == true) return

        // The path tool spends the pen on placing points, so the pen never actually goes
        // down: isPenDown stays false, which keeps the satellites live and the canvas pannable
        // while a path is open - all of which you want, since building one takes a while.
        if (state.drawingMode is DrawingMode.Path) {
            if (down) pressPathPoint(state) else dropPathPoint()
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
                pressPushedEntry = true
                updateColorHistory(state.selectedColor)
                performFloodFill(state.brushPosition, state.selectedColor.copy(alpha = state.brushOpacity))
            }
            return
        }

        if (state.isPenDown == down) return

        if (down) {
            // Start of stroke. History is saved at pen-up (commit time), once the stroke's
            // bounding box is known, so only the touched region gets snapshotted.
            history.cancelCursorGlide()
            strokeStartAnchor = CursorAnchor(state.cursorPosition, state.brushPosition)
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
            history.save(strokeSnapshotSpec(session.value), strokeStartAnchor)
            pressPushedEntry = true
            // Spent before the anchor is dropped. Sending the cursor back to where the stroke
            // began leaves the next mark to be measured from somewhere meaningful, instead of
            // from the far end of the one just made - which is nowhere in particular.
            if (state.isRecoilActive) {
                // Does not yield to cursor movement: the hand that was steering is still on
                // the screen at pen-up, and its next move used to cancel this before it had
                // travelled anywhere.
                strokeStartAnchor?.let {
                    history.glideCursorTo(it, durationMs = RECOIL_MS, yieldsToMovement = false)
                }
            }
            strokeStartAnchor = null
            commitStrokeToLayer()
            // After the composite, not before: that is where the texture mask and the selection
            // clip are applied, and a trace taken earlier would not be the stroke that landed.
            if (state.keepUndoneStrokes) captureStrokeTrace(state)?.let { history.attachStrokeTrace(it) }

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

    /**
     * The stroke on its own, cropped to the box it touched, for undo to leave behind.
     *
     * Null for the two cases where a trace would lie about what happened: an eraser stroke's
     * bitmap is the shape that was *removed*, so painting it back would put brush colour over
     * the hole it made; and a gradient reports no dirty region because it repaints everything,
     * leaving nothing to crop to and a full-canvas copy per undo to hold.
     */
    private fun captureStrokeTrace(state: DrawingState): StrokeTrace? {
        if (state.drawingMode is DrawingMode.Eraser || state.drawingMode is DrawingMode.StraightLineEraser) return null
        val stroke = engine.strokeBitmap ?: return null
        val r = engine.dirtyRegion(stroke.width, stroke.height) ?: return null
        if (r.width() <= 0 || r.height() <= 0) return null
        return StrokeTrace(
            Bitmap.createBitmap(stroke, r.left, r.top, r.width(), r.height()),
            r.left,
            r.top
        )
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

            FloodFill.fill(
                pixels, width, height, x, y, targetColor, replacementColor,
                state.fillTolerance, maskPixels, state.fillGrow.toInt()
            )
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
    fun abortCurrentStroke() {
        if (!pressPushedEntry) return
        pressPushedEntry = false
        history.abortLastEntry()
    }

    /**
     * Both refuse to run mid-stroke.
     *
     * The pen is a toggle here, not a hold, so the toolbar stays reachable with a stroke open
     * underneath it. Restoring pixels at that point would paste over a layer the live stroke
     * has not been composited into yet, and the entry for that stroke is not pushed until
     * pen-up - so the undo would take back the wrong thing and then be overwritten by a
     * commit that no longer matches anything on the stack.
     *
     * Guarded here rather than at each caller: the mode gate already refuses, but the toolbar
     * buttons and the screen-reader action did not, and the next caller would have to
     * remember.
     */
    fun undo() {
        if (session.value.isPenDown) return
        // Undo while a selection is floating cancels the move; a closed selection is kept
        // (it's a drawing mask now) and undo applies to strokes as usual
        if (session.value.floatingBitmap != null) { selection.cancel(); return }
        history.undo()
    }

    fun redo() {
        if (session.value.isPenDown) return
        if (session.value.floatingBitmap != null) { selection.cancel(); return }
        history.redo()
    }

    // ============================ Path tool ============================

    /**
     * Pressing the pen picks up the point under the cursor, or makes one there and picks that
     * up. Releasing puts it down.
     *
     * The point rides the cursor for as long as the button is held, so placing one is press,
     * steer, release - you are never asked to be accurate before you can see the result. A
     * relative cursor is not something you land on a target in one go.
     */
    private fun pressPathPoint(state: DrawingState) {
        val hit = state.hoveredPathPoint
        if (hit >= 0) {
            session.update { it.copy(grabbedPathPoint = hit, pathPointsFromPress = 0) }
            return
        }

        // The very first press drops an anchor and starts the point after it in the same
        // motion, so one hold draws a straight line exactly the way the straight-line tool
        // does. Otherwise that first hold would have nothing to show for itself: a lone point
        // has no curve to preview, and you would be aiming the start of a line you cannot see.
        val added = if (state.pathPoints.isEmpty()) 2 else 1
        val points = state.pathPoints.toMutableList()
        repeat(added) { points.add(PathPoint(state.brushPosition)) }
        session.update { it.copy(
            pathPoints = points,
            grabbedPathPoint = points.lastIndex,
            pathPointsFromPress = added
        ) }
        repaintPathPreview()
    }

    private fun dropPathPoint() {
        val state = session.value
        if (state.grabbedPathPoint < 0) return

        // Landing one end on the other joins them. The held point is removed rather than left
        // stacked on its twin: two handles at the same spot cannot be told apart or picked up
        // separately, so keeping both would leave one of them permanently out of reach.
        if (state.pathClosingCandidate) {
            val points = state.pathPoints.toMutableList()
            points.removeAt(state.grabbedPathPoint)
            session.update { it.copy(
                pathPoints = points,
                pathClosed = true,
                grabbedPathPoint = -1,
                pathPointsFromPress = 0
            ) }
            repaintPathPreview()
            return
        }
        session.update { it.copy(grabbedPathPoint = -1, pathPointsFromPress = 0) }
    }

    /**
     * Joins the two ends, or parts them again.
     *
     * Reopening does not hand back the point the join swallowed - you merged two ends into
     * one, and undoing the join does not split it. It is here mostly so a close is never a
     * trap, and so ends that are nowhere near each other can still be joined without dragging
     * one across the whole drawing to reach the other.
     */
    fun togglePathClosed() {
        if (session.value.pathPoints.size < 3) return
        session.update { it.copy(pathClosed = !it.pathClosed) }
        repaintPathPreview()
    }

    /**
     * Abandons the press, taking the point back with it if the press is what created it.
     *
     * For the button's own drag-to-reposition: that gesture starts as a press like any other,
     * so by the time it declares itself a drag a point has already been placed. Leaving it
     * there would strand one every time the button is moved, and there is no way to remove a
     * single point once it exists.
     */
    fun abortPathPress() {
        val state = session.value
        if (state.grabbedPathPoint < 0) return
        if (state.pathPointsFromPress <= 0) {
            dropPathPoint()
            return
        }
        // Everything this press added, which is both points when it was the first one - leaving
        // the anchor behind would strand a path that never got its second end.
        val points = state.pathPoints.toMutableList()
        repeat(state.pathPointsFromPress) { if (points.isNotEmpty()) points.removeAt(points.lastIndex) }
        session.update { it.copy(pathPoints = points, grabbedPathPoint = -1, pathPointsFromPress = 0) }
        repaintPathPreview()
    }

    /**
     * Flips the point under the cursor between rounded and sharp.
     *
     * Acts on the held point if there is one, otherwise on whatever is under the cursor, so it
     * works the same whether you are mid-drag or just passing over.
     */
    fun togglePathPointCorner() {
        val state = session.value
        val index = if (state.grabbedPathPoint >= 0) state.grabbedPathPoint else state.hoveredPathPoint
        if (index !in state.pathPoints.indices) return
        val points = state.pathPoints.toMutableList()
        points[index] = points[index].copy(isCorner = !points[index].isCorner)
        session.update { it.copy(pathPoints = points) }
        repaintPathPreview()
    }

    /** Throws away the pending path without touching the layer. */
    fun cancelPath() {
        if (session.value.pathPoints.isEmpty()) return
        engine.clearTarget()
        session.update { it.copy(
            pathPoints = emptyList(),
            grabbedPathPoint = -1,
            pathPointsFromPress = 0,
            pathClosed = false,
            strokeBitmap = engine.strokeBitmap,
            renderVersion = it.renderVersion + 1
        ) }
    }

    /**
     * Stamps the curve onto the active layer and clears the path.
     *
     * Deliberately the same tail as a finished stroke - one history entry, one trace for undo
     * to leave behind, one scheduled save. A path is a stroke that took a while to describe,
     * and nothing downstream should be able to tell the difference.
     */
    fun commitPath() {
        val state = session.value
        if (state.pathPoints.size < 2) return
        if (state.layers.find { it.id == state.activeLayerId }?.isLocked == true) return

        engine.drawPolyline(PathGeometry.flatten(state.pathPoints, state.pathClosed), state)
        history.save(strokeSnapshotSpec(session.value))
        commitStrokeToLayer()
        if (state.keepUndoneStrokes) captureStrokeTrace(state)?.let { history.attachStrokeTrace(it) }

        engine.clearTarget()
        session.update { it.copy(
            pathPoints = emptyList(),
            grabbedPathPoint = -1,
            pathPointsFromPress = 0,
            pathClosed = false,
            strokeBitmap = engine.strokeBitmap,
            renderVersion = it.renderVersion + 1
        ) }
        persistence.scheduleLayerSave(state.activeLayerId)
        persistence.touchProject()
    }

    private fun repaintPathPreview() {
        val state = session.value
        if (state.pathPoints.size < 2) {
            engine.clearTarget()
        } else {
            engine.drawPolyline(PathGeometry.flatten(state.pathPoints, state.pathClosed), state)
        }
        session.update { it.copy(
            strokeBitmap = engine.strokeBitmap,
            renderVersion = it.renderVersion + 1
        ) }
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
    fun toggleLayerLock(layer: LayerEntity) = layers.toggleLock(layer)
    fun discardTraces() = layers.discardTraces()

    /** Backs the readout chip, which knows the active layer is locked but not which one it is. */
    fun unlockActiveLayer() {
        val state = session.value
        state.layers.find { it.id == state.activeLayerId && it.isLocked }?.let { layers.toggleLock(it) }
    }
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
        viewModelScope.launch { persistence.saveProjectSettings() }
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
        // Walking away from the path tool abandons whatever was being built. Carrying it over
        // would leave a curve on screen with no tool able to finish it.
        if (session.value.drawingMode is DrawingMode.Path && mode !is DrawingMode.Path) cancelPath()
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
     * Applies a setting that genuinely belongs to this project, and schedules its row.
     *
     * Only the colour and the cursor feel are left in this category. Every brush property is a
     * global now and goes through [updatePreference] instead - routing one through here used to
     * mean that nudging the spacing wrote the colour back to the database.
     */
    private fun updateProjectSetting(update: (DrawingState) -> DrawingState) {
        session.update(update)
        // Throttled: these arrive one per pointer event while a satellite gate is being swept,
        // and each unthrottled save was a project read plus a project write.
        persistence.scheduleProjectSettingsSave()
    }

    /**
     * Preference writes waiting out [PREFERENCE_SETTLE_MS], the newest one per store key.
     *
     * A slider calls its setter once per frame, and each of those was a DataStore edit - which
     * rewrites and fsyncs the whole preference file. Sixty of those a second is work the drag
     * has to share the device with. Keyed on the row each one lands in, so the newest value of
     * a setting replaces the pending one instead of queueing behind it, and two settings moved
     * in the same breath can't displace each other.
     */
    private val pendingPreferences = mutableMapOf<Preferences.Key<*>, suspend () -> Unit>()
    private var preferenceFlushJob: Job? = null

    /**
     * Holds [persist] until the setting stops moving.
     *
     * Restarted on each change rather than left to tick, so a slider that has settled is
     * written promptly while one still under the thumb is not written at all.
     */
    private fun persistPreference(key: Preferences.Key<*>, persist: suspend () -> Unit) {
        synchronized(pendingPreferences) { pendingPreferences[key] = persist }
        preferenceFlushJob?.cancel()
        // persistScope, not viewModelScope: see flushPreferences at onCleared.
        preferenceFlushJob = persistScope.launch {
            delay(PREFERENCE_SETTLE_MS)
            flushPreferences()
        }
    }

    /**
     * Writes everything still waiting.
     *
     * NonCancellable because the next change cancels this job to restart the wait: a flush
     * caught halfway through would have emptied the map of writes it never made, and the
     * setting they carried would be gone.
     */
    private suspend fun flushPreferences() = withContext(NonCancellable) {
        val due = synchronized(pendingPreferences) {
            pendingPreferences.values.toList().also { pendingPreferences.clear() }
        }
        due.forEach { it() }
    }

    /**
     * Applies a brush setting, which is stored as a global default rather than per project.
     *
     * [key] is the row the write lands in, and is what the pending write is filed under - so it
     * is the store's own key rather than a name invented here, which two settings could collide
     * on without anything noticing.
     */
    private fun updatePreference(
        key: Preferences.Key<*>,
        persist: suspend () -> Unit,
        update: (DrawingState) -> DrawingState
    ) {
        session.update(update)
        persistPreference(key, persist)
    }

    fun selectColor(color: Color) = updateProjectSetting { it.copy(selectedColor = color, isEyeDropperMode = false) }

    fun selectWidth(width: Float) = updatePreference(PreferenceManager.BRUSH_SIZE_KEY, { preferenceManager.setBrushSize(width) }) { it.copy(selectedWidth = width) }

    fun setBrushSoftness(softness: Float) = updatePreference(PreferenceManager.BRUSH_SOFTNESS_KEY, { preferenceManager.setBrushSoftness(softness) }) { it.copy(brushSoftness = softness) }

    fun setBrushSmoothing(smoothing: Float) = updatePreference(PreferenceManager.BRUSH_SMOOTHING_KEY, { preferenceManager.setBrushSmoothing(smoothing) }) { it.copy(brushSmoothing = smoothing) }

    fun setBrushOpacity(opacity: Float) = updatePreference(PreferenceManager.BRUSH_OPACITY_KEY, { preferenceManager.setBrushOpacity(opacity) }) { it.copy(brushOpacity = opacity) }
    fun setBrushFlow(flow: Float) = updatePreference(PreferenceManager.BRUSH_FLOW_KEY, { preferenceManager.setBrushFlow(flow) }) { it.copy(brushFlow = flow) }
    fun setBrushSpacing(spacing: Float) = updatePreference(PreferenceManager.BRUSH_SPACING_KEY, { preferenceManager.setBrushSpacing(spacing) }) { it.copy(brushSpacing = spacing) }
    fun setBrushRotation(rotation: Float) = updatePreference(PreferenceManager.BRUSH_ROTATION_KEY, { preferenceManager.setBrushRotation(rotation) }) { it.copy(brushRotation = rotation) }

    fun setSmudge(v: Float) = updatePreference(PreferenceManager.SMUDGE_KEY, { preferenceManager.setSmudge(v) }) { it.copy(smudge = v) }
    fun setSmudgeLength(v: Float) = updatePreference(PreferenceManager.SMUDGE_LENGTH_KEY, { preferenceManager.setSmudgeLength(v) }) { it.copy(smudgeLength = v) }

    fun setHueJitter(v: Float) = updatePreference(PreferenceManager.HUE_JITTER_KEY, { preferenceManager.setHueJitter(v) }) { it.copy(hueJitter = v) }
    fun setSaturationJitter(v: Float) = updatePreference(PreferenceManager.SATURATION_JITTER_KEY, { preferenceManager.setSaturationJitter(v) }) { it.copy(saturationJitter = v) }
    fun setValueJitter(v: Float) = updatePreference(PreferenceManager.VALUE_JITTER_KEY, { preferenceManager.setValueJitter(v) }) { it.copy(valueJitter = v) }

    fun setAntiAlias(on: Boolean) = updatePreference(PreferenceManager.ANTI_ALIAS_KEY, { preferenceManager.setAntiAlias(on) }) { it.copy(antiAlias = on) }

    fun setTipShape(shape: TipShape) = updatePreference(PreferenceManager.TIP_SHAPE_KEY, { preferenceManager.setTipShape(shape.name) }) { it.copy(tipShape = shape) }

    /**
     * Squashes the built-in tip. Never reaches zero: a tip with no width at all has nothing to
     * stamp, and the stroke would simply stop appearing.
     */
    fun setTipRatio(ratio: Float) {
        val clamped = ratio.coerceIn(0.05f, 1f)
        updatePreference(PreferenceManager.TIP_RATIO_KEY, { preferenceManager.setTipRatio(clamped) }) { it.copy(tipRatio = clamped) }
    }

    fun setRotationJitter(jitter: Float) = updatePreference(PreferenceManager.ROTATION_JITTER_KEY, { preferenceManager.setRotationJitter(jitter) }) { it.copy(brushRotationJitter = jitter) }

    fun setSizeJitter(jitter: Float) = updatePreference(PreferenceManager.SIZE_JITTER_KEY, { preferenceManager.setSizeJitter(jitter) }) { it.copy(sizeJitter = jitter) }

    /** Scales the brush size past the slider's ceiling. 1x paints at the size as set. */
    fun setSizeMultiplier(multiplier: Float) {
        val clamped = multiplier.coerceIn(0f, SizeMultiplierScale.MAX)
        updatePreference(PreferenceManager.SIZE_MULTIPLIER_KEY, { preferenceManager.setSizeMultiplier(clamped) }) { it.copy(sizeMultiplier = clamped) }
    }

    fun setFlowJitter(amount: Float) = updatePreference(PreferenceManager.FLOW_JITTER_KEY, { preferenceManager.setFlowJitter(amount) }) { it.copy(flowJitter = amount) }

    fun setRotationFollow(amount: Float) = updatePreference(PreferenceManager.ROTATION_FOLLOW_KEY, { preferenceManager.setRotationFollow(amount) }) { it.copy(rotationFollow = amount) }

    fun setScatterJitter(amount: Float) = updatePreference(PreferenceManager.SCATTER_JITTER_KEY, { preferenceManager.setScatterJitter(amount) }) { it.copy(scatterJitter = amount) }

    fun setVelocitySize(amount: Float) = updatePreference(PreferenceManager.VELOCITY_SIZE_KEY, { preferenceManager.setVelocitySize(amount) }) { it.copy(velocitySizeAmount = amount) }

    fun setVelocityFlow(amount: Float) = updatePreference(PreferenceManager.VELOCITY_FLOW_KEY, { preferenceManager.setVelocityFlow(amount) }) { it.copy(velocityFlowAmount = amount) }

    fun setVelocityScatter(amount: Float) = updatePreference(PreferenceManager.VELOCITY_SCATTER_KEY, { preferenceManager.setVelocityScatter(amount) }) { it.copy(velocityScatterAmount = amount) }

    fun setVelocityEnabled(enabled: Boolean) = updatePreference(PreferenceManager.VELOCITY_ENABLED_KEY, { preferenceManager.setVelocityEnabled(enabled) }) { it.copy(velocityEnabled = enabled) }

    fun setCursorSensitivity(s: Float) = updateProjectSetting { it.copy(cursorSensitivity = s.coerceIn(0.1f, 1.0f)) }

    // These two wrote to the store and waited for the observer to hand the value back, which
    // was the only thing moving their sliders - so the thumb could not travel faster than the
    // disk. They now go through updatePreference like every other setting: live in the session
    // at once, on disk when the drag settles.
    fun setFillTolerance(tolerance: Float) = updatePreference(
        PreferenceManager.FILL_TOLERANCE_KEY,
        { preferenceManager.setFillTolerance(tolerance) }
    ) { it.copy(fillTolerance = tolerance) }

    fun setFillGrow(pixels: Float) {
        val clamped = pixels.coerceIn(0f, 8f)
        updatePreference(
            PreferenceManager.FILL_GROW_KEY,
            { preferenceManager.setFillGrow(clamped) }
        ) { it.copy(fillGrow = clamped) }
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

    /** Strips the screen back to the button, its satellites and the chips - and back again. */
    fun toggleFullscreen() {
        session.update { it.copy(isFullscreen = !it.isFullscreen) }
    }

    /** Arms or disarms the cursor springing back to each stroke's starting point. */
    fun toggleRecoil() {
        session.update { it.copy(isRecoilActive = !it.isRecoilActive) }
    }

    fun toggleLazyMode() {
        session.update { it.copy(isLazyModeActive = !it.isLazyModeActive) }
    }

    fun setLazyRadius(radius: Float) {
        session.update { it.copy(lazyRadius = radius) }
    }

    // Writing the preference is enough: the observers above decode it and put it in hand.
    fun setBrushTip(context: android.content.Context, uri: String?) {
        viewModelScope.launch { preferenceManager.setBrushTipUri(uri) }
    }

    fun setBrushTexture(context: android.content.Context, uri: String?) {
        viewModelScope.launch { preferenceManager.setBrushTextureUri(uri) }
    }

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

    suspend fun saveProjectBrushSettings() = persistence.saveProjectSettings()

    fun flushPendingSaves() = persistence.flushNow()

    fun manualSave() = persistence.generateThumbnail(session.value)

    fun exportProject(context: android.content.Context, format: String) = persistence.export(context, format)

    override fun onCleared() {
        history.clearAll()
        brushAssets.release()
        // viewModelScope is already cancelled here; the flush runs on persistScope so the
        // last strokes aren't lost when leaving the screen
        persistence.flushNow()
        // Same reason: a setting nudged on the way out has not waited out its delay yet, and
        // these are globals now - the next project would open with the old value.
        preferenceFlushJob?.cancel()
        persistScope.launch { flushPreferences() }
    }
}
