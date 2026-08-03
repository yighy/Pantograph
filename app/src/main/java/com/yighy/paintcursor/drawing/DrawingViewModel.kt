package com.yighy.paintcursor.drawing

import android.graphics.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yighy.paintcursor.data.LayerEntity
import com.yighy.paintcursor.data.ProjectRepository
import com.yighy.paintcursor.data.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.*
import kotlin.math.*

class DrawingViewModel(
    private val repository: ProjectRepository,
    private val projectId: Long,
    private val internalFilesDir: File,
    private val preferenceManager: PreferenceManager,
    private val context: android.content.Context
) : ViewModel() {
    private val _uiState = MutableStateFlow(DrawingState())
    val uiState: StateFlow<DrawingState> = _uiState.asStateFlow()

    private val layerBitmaps = mutableMapOf<Long, Bitmap>()
    private var strokeBitmap: Bitmap? = null
    private var strokeCanvas: Canvas? = null
    private val historyManager = DrawingHistoryManager(context.cacheDir, repository, projectId, viewModelScope)
    private val customBrushManager = CustomBrushManager(repository)

    private var smoothedVelocity = Offset.Zero
    private var distanceSinceLastStamp = 0f
    private var currentStrokeDistance = 0f
    private val random = Random()

    // Velocity dynamics: smoothed cursor speed -> normalized 0..1.
    // velocityDynamicsActive gates effects whose "inverted" direction acts at LOW speed
    // (negative scatter), which must not fire outside a velocity-enabled freehand stroke.
    private var smoothedSpeed = 0f
    private var currentVelocityNorm = 0f
    private var velocityDynamicsActive = false

    // Running fit-to-screen animation; cancelled as soon as the user grabs the canvas
    private var canvasAnimJob: Job? = null

    // Bézier Smoothing state
    private var lastPoint = Offset.Zero
    private var lastMidPoint = Offset.Zero

    // Selection tool state
    private var rectAnchor = Offset.Zero
    private var floatingFromCut = false
    // True while the floating selection holds an imported image whose layer isn't created yet
    private var pendingImport = false

    // Optimization: Object pooling and Pre-rendered stamp
    private val sharedPaint = Paint().apply { isAntiAlias = true }
    private val stampRect = RectF()
    private var cachedStampBitmap: Bitmap? = null
    private var lastStampKey: String = ""

    // Bounding box (canvas coords) of every stamp drawn into strokeBitmap since its last
    // erase. Bounds the history snapshot at commit so undo only stores the touched region.
    private val strokeDirtyRect = RectF()
    private var strokeDirtyValid = false

    init {
        loadProject()
        observeSettings()
    }

    private fun observeSettings() {
        preferenceManager.historyLimit
            .onEach { limit -> _uiState.update { it.copy(historyLimit = limit) } }
            .launchIn(viewModelScope)
        
        preferenceManager.fabDragThreshold
            .onEach { threshold -> _uiState.update { it.copy(fabDragThreshold = threshold) } }
            .launchIn(viewModelScope)

        preferenceManager.colorHistory
            .onEach { history -> 
                val colors = history.mapNotNull { 
                    try { Color(android.graphics.Color.parseColor(it)) } catch(e: Exception) { null }
                }
                _uiState.update { it.copy(colorHistory = colors) }
            }
            .launchIn(viewModelScope)

        preferenceManager.colorPickerIsSliderMode
            .onEach { isSlider -> _uiState.update { it.copy(isColorPickerSliderMode = isSlider) } }
            .launchIn(viewModelScope)

        preferenceManager.cursorThickness
            .onEach { thickness -> _uiState.update { it.copy(cursorThickness = thickness) } }
            .launchIn(viewModelScope)

        preferenceManager.fillTolerance
            .onEach { tolerance -> _uiState.update { it.copy(fillTolerance = tolerance) } }
            .launchIn(viewModelScope)

        preferenceManager.rotationDynamics
            .onEach { enabled -> _uiState.update { it.copy(brushRotationDynamics = enabled) } }
            .launchIn(viewModelScope)

        preferenceManager.rotationJitter
            .onEach { jitter -> _uiState.update { it.copy(brushRotationJitter = jitter) } }
            .launchIn(viewModelScope)

        preferenceManager.velocitySize
            .onEach { amount -> _uiState.update { it.copy(velocitySizeAmount = amount) } }
            .launchIn(viewModelScope)

        preferenceManager.velocityFlow
            .onEach { amount -> _uiState.update { it.copy(velocityFlowAmount = amount) } }
            .launchIn(viewModelScope)

        preferenceManager.velocityScatter
            .onEach { amount -> _uiState.update { it.copy(velocityScatterAmount = amount) } }
            .launchIn(viewModelScope)

        preferenceManager.velocityEnabled
            .onEach { enabled -> _uiState.update { it.copy(velocityEnabled = enabled) } }
            .launchIn(viewModelScope)

        preferenceManager.offscreenCursorArrow
            .onEach { enabled -> _uiState.update { it.copy(showOffscreenCursorArrow = enabled) } }
            .launchIn(viewModelScope)
    }

    fun setVelocitySize(amount: Float) {
        _uiState.update { it.copy(velocitySizeAmount = amount) }
        viewModelScope.launch { preferenceManager.setVelocitySize(amount) }
    }

    fun setVelocityFlow(amount: Float) {
        _uiState.update { it.copy(velocityFlowAmount = amount) }
        viewModelScope.launch { preferenceManager.setVelocityFlow(amount) }
    }

    fun setVelocityScatter(amount: Float) {
        _uiState.update { it.copy(velocityScatterAmount = amount) }
        viewModelScope.launch { preferenceManager.setVelocityScatter(amount) }
    }

    fun setVelocityEnabled(enabled: Boolean) {
        _uiState.update { it.copy(velocityEnabled = enabled) }
        viewModelScope.launch { preferenceManager.setVelocityEnabled(enabled) }
    }

    fun setColorPickerSliderMode(isSlider: Boolean) {
        viewModelScope.launch {
            preferenceManager.setColorPickerSliderMode(isSlider)
        }
    }

    fun saveFabPosition(x: Float, y: Float) {
        viewModelScope.launch {
            preferenceManager.setFabPosition(x, y)
        }
    }

    private fun loadProject() {
        viewModelScope.launch {
            val project = repository.getProjectById(projectId) ?: return@launch
            _uiState.update { 
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
                    brushRotationDynamics = project.lastBrushRotationDynamics,
                    brushRotationJitter = project.lastBrushRotationJitter,
                    sizeJitter = project.lastSizeJitter,
                    brushTipUri = project.lastBrushTipUri,
                    brushTextureUri = project.lastBrushTextureUri,
                    cursorSensitivity = project.lastCursorSensitivity
                )
            }

            // Load saved project brush bitmaps if they exist
            project.lastBrushTipUri?.let { setBrushTip(context, it) }
            project.lastBrushTextureUri?.let { setBrushTexture(context, it) }
            
            // Load saved reference image
            project.referenceImageUri?.let { uri ->
                loadSavedReferenceImage(uri, Offset(project.referenceImageOffsetX, project.referenceImageOffsetY), project.referenceImageScale, project.referenceImageRotation)
            }
            
            launch {
                repository.getLayersForProject(projectId).collectLatest { layers ->
                    if (layers.isEmpty()) return@collectLatest
                    
                    val currentActiveId = _uiState.value.activeLayerId
                    val savedActiveId = project.lastActiveLayerId
                    val newActiveId = when {
                        // Session already has a valid active layer: keep it
                        currentActiveId != -1L && layers.any { it.id == currentActiveId } -> currentActiveId
                        // Fresh open: restore the layer the user was on last time
                        savedActiveId != -1L && layers.any { it.id == savedActiveId } -> savedActiveId
                        // New/legacy project: default to the topmost layer, not the bottom
                        else -> layers.last().id
                    }

                    layers.forEach { layer ->
                        if (!layerBitmaps.containsKey(layer.id)) {
                            val bitmap = loadLayerBitmap(layer, project.width, project.height)
                            layerBitmaps[layer.id] = bitmap
                        }
                    }

                    _uiState.update { state ->
                        state.copy(
                            layers = layers,
                            activeLayerId = newActiveId,
                            layerBitmaps = layerBitmaps.toMap()
                        ) 
                    }
                }
            }

            launch {
                repository.allCustomBrushes.collect { brushes ->
                    _uiState.update { it.copy(customBrushes = brushes.map { b ->
                        BrushConfig(
                            id = b.id.toString(),
                            name = b.name,
                            size = b.size,
                            softness = b.softness,
                            opacity = b.opacity,
                            flow = b.flow,
                            spacing = b.spacing,
                            smoothing = b.smoothing,
                            rotation = b.rotation,
                            rotationDynamics = b.rotationDynamics,
                            rotationJitter = b.rotationJitter,
                            sizeJitter = b.sizeJitter,
                            tipUri = b.tipUri,
                            textureUri = b.textureUri,
                            velocityEnabled = b.velocityEnabled,
                            velocitySize = b.velocitySize,
                            velocityFlow = b.velocityFlow,
                            velocityScatter = b.velocityScatter
                        )
                    }) }
                }
            }
        }
    }

    private suspend fun loadLayerBitmap(layer: LayerEntity, width: Int, height: Int): Bitmap {
        return withContext(Dispatchers.IO) {
            val file = layer.imagePath?.let { File(it) }
            if (file != null && file.exists()) {
                val options = BitmapFactory.Options().apply { inMutable = true }
                BitmapFactory.decodeFile(file.absolutePath, options) ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            } else {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }
        }
    }

    fun setCanvasSize(size: IntSize) {
        if (size.width <= 0 || size.height <= 0) return
        
        // Re-initialize stroke bitmap if size changed
        if (strokeBitmap == null || strokeBitmap?.width != size.width || strokeBitmap?.height != size.height) {
            strokeBitmap?.recycle()
            strokeBitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            strokeCanvas = Canvas(strokeBitmap!!)
            _uiState.update { it.copy(strokeBitmap = strokeBitmap) }
        }

        if (_uiState.value.cursorPosition == Offset.Zero) {
            val center = Offset(size.width / 2f, size.height / 2f)
            _uiState.update { it.copy(cursorPosition = center, brushPosition = center) }
        }
    }

    fun moveCursor(delta: Offset) {
        val currentState = _uiState.value
        val sensitivity = if (currentState.isPenDown) currentState.cursorSensitivity else 1.0f
        val targetVelocity = delta * sensitivity
        
        val smoothingFactor = if (currentState.isPenDown) {
            1.0f - (currentState.brushSmoothing * 0.92f)
        } else {
            1.0f
        }
        smoothedVelocity = smoothedVelocity * (1f - smoothingFactor) + targetVelocity * smoothingFactor

        // Velocity dynamics: only freehand strokes react to speed (shape tools stay uniform)
        velocityDynamicsActive = currentState.isPenDown && currentState.velocityEnabled &&
            (currentState.drawingMode is DrawingMode.Freehand || currentState.drawingMode is DrawingMode.Eraser)
        currentVelocityNorm = if (velocityDynamicsActive) {
            smoothedSpeed = smoothedSpeed * 0.6f + smoothedVelocity.getDistance() * 0.4f
            velocityNorm(smoothedSpeed)
        } else 0f

        val newX = (currentState.cursorPosition.x + smoothedVelocity.x).coerceIn(0f, currentState.canvasWidth.toFloat())
        val newY = (currentState.cursorPosition.y + smoothedVelocity.y).coerceIn(0f, currentState.canvasHeight.toFloat())
        val newPosition = Offset(newX, newY)

        // Calculate Lazy Position
        val newBrushPosition = if (currentState.isLazyModeActive) {
            val vector = newPosition - currentState.brushPosition
            val distance = vector.getDistance()
            if (distance > currentState.lazyRadius) {
                newPosition - (vector / distance) * currentState.lazyRadius
            } else {
                currentState.brushPosition
            }
        } else {
            newPosition
        }

        var needsRedraw = false
        if (currentState.isPenDown) {
            if (currentState.isEyeDropperMode) {
                pickColorFromCanvas(newBrushPosition)
            } else if (currentState.drawingMode.isSelectionTool()) {
                updateSelectionDrag(newBrushPosition, currentState)
            } else if (currentState.drawingMode is DrawingMode.Gradient) {
                val start = currentState.currentPath?.points?.get(0) ?: newBrushPosition

                // Live gradient preview: selectedColor at the start point fading to
                // transparent at the cursor; clipping/opacity applied at commit
                strokeBitmap?.eraseColor(android.graphics.Color.TRANSPARENT)
                strokeCanvas?.let { canvas ->
                    val paint = Paint().apply {
                        isAntiAlias = true
                        shader = LinearGradient(
                            start.x, start.y, newBrushPosition.x, newBrushPosition.y,
                            currentState.selectedColor.toArgb(), android.graphics.Color.TRANSPARENT,
                            Shader.TileMode.CLAMP
                        )
                    }
                    canvas.drawRect(0f, 0f, currentState.canvasWidth.toFloat(), currentState.canvasHeight.toFloat(), paint)
                }

                _uiState.update { it.copy(
                    cursorPosition = newPosition,
                    brushPosition = newBrushPosition,
                    currentPath = it.currentPath?.copy(points = listOf(start, newBrushPosition)),
                    renderVersion = it.renderVersion + 1
                )}
                // Keep the stroke distance up to date: the FAB uses it to tell
                // "drawing" apart from "dragging the button" (see HoverDrawButton)
                currentStrokeDistance += (newBrushPosition - currentState.brushPosition).getDistance()
                return
            } else if (currentState.drawingMode !is DrawingMode.BucketFill) {
                if (currentState.drawingMode is DrawingMode.Freehand || currentState.drawingMode is DrawingMode.Eraser) {
                    // Bézier Smoothing implementation
                    val midPoint = Offset((lastPoint.x + newBrushPosition.x) / 2f, (lastPoint.y + newBrushPosition.y) / 2f)
                    
                    // Draw curve from last mid-point to current mid-point using lastPoint as control
                    drawBezierSegment(lastMidPoint, lastPoint, midPoint, currentState)
                    
                    lastMidPoint = midPoint
                    lastPoint = newBrushPosition
                    needsRedraw = true
                    _uiState.update { it.copy(renderVersion = it.renderVersion + 1) }
                } else if (currentState.drawingMode is DrawingMode.StraightLine || currentState.drawingMode is DrawingMode.StraightLineEraser) {
                    val start = currentState.currentPath?.points?.get(0) ?: newBrushPosition
                    
                    // Update stroke bitmap for real-time accurate preview
                    strokeBitmap?.eraseColor(android.graphics.Color.TRANSPARENT)
                    strokeDirtyValid = false
                    distanceSinceLastStamp = max(1f, currentState.selectedWidth * currentState.brushSpacing)
                    drawStampStroke(start, newBrushPosition, currentState, seed = 0L)

                    _uiState.update { it.copy(
                        cursorPosition = newPosition,
                        brushPosition = newBrushPosition,
                        currentPath = it.currentPath?.copy(points = listOf(start, newBrushPosition)),
                        renderVersion = it.renderVersion + 1
                    )}
                    // Keep the stroke distance up to date: the FAB uses it to tell
                    // "drawing" apart from "dragging the button" (see HoverDrawButton)
                    currentStrokeDistance += (newBrushPosition - currentState.brushPosition).getDistance()
                    return
                }
            }
        }

        _uiState.update { state ->
            state.copy(
                cursorPosition = newPosition,
                brushPosition = newBrushPosition,
                renderVersion = if (needsRedraw) state.renderVersion + 1 else state.renderVersion
            )
        }
        if (currentState.isPenDown) {
            currentStrokeDistance += (newBrushPosition - currentState.brushPosition).getDistance()
        }
    }

    private fun drawBezierSegment(p0: Offset, p1: Offset, p2: Offset, state: DrawingState) {
        // Approximate length of the quadratic Bézier curve
        val chord = (p2 - p0).getDistance()
        val num = (p0 - p1).getDistance() + (p1 - p2).getDistance()
        val approxLength = (chord + num) / 2f
        
        if (approxLength <= 0f) return
        
        val step = max(1f, state.selectedWidth * state.brushSpacing)
        val numSteps = (approxLength / step).toInt().coerceAtLeast(1)
        
        var prevPoint = p0
        for (i in 1..numSteps) {
            val t = i.toFloat() / numSteps
            // Quadratic Bézier formula: (1-t)^2*p0 + 2(1-t)t*p1 + t^2*p2
            val x = (1-t).pow(2) * p0.x + 2 * (1-t) * t * p1.x + t.pow(2) * p2.x
            val y = (1-t).pow(2) * p0.y + 2 * (1-t) * t * p1.y + t.pow(2) * p2.y
            val currentPoint = Offset(x, y)
            
            drawStampStroke(prevPoint, currentPoint, state)
            prevPoint = currentPoint
        }
    }

    fun getCurrentStrokeDistance(): Float = currentStrokeDistance

    /**
     * Maps smoothed cursor speed (px/event) to 0..1. The per-stamp size/flow/scatter
     * factors are derived from this in [drawStampStroke].
     */
    private fun velocityNorm(speed: Float): Float = (speed / 40f).coerceIn(0f, 1f)

    private fun updateStampCache(state: DrawingState) {
        val isEraser = state.drawingMode is DrawingMode.Eraser || state.drawingMode is DrawingMode.StraightLineEraser
        val key = "${state.selectedWidth}_${state.brushSoftness}_" +
                  "${state.selectedColor.toArgb()}_${state.brushTipUri}_$isEraser"
        
        if (key == lastStampKey && cachedStampBitmap != null) return
        
        lastStampKey = key
        // Calculate canvas size for the stamp - needs to account for potential blur padding
        // selectedWidth is the diameter. We use 1.5x to be safe for BlurMaskFilter
        val padding = if (state.brushSoftness > 0f) state.selectedWidth * 0.5f else 2f
        val canvasSize = (state.selectedWidth + padding).toInt().coerceAtLeast(1)
        
        val bitmap = Bitmap.createBitmap(canvasSize, canvasSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = canvasSize / 2f
        
        // Texture is NOT baked into the stamp: it's applied canvas-anchored over the whole
        // stroke at composition time (see applyTextureToStroke), so it reads as paper grain.
        val paint = Paint().apply {
            isAntiAlias = true
            // Eraser tip needs to be solid black for the alpha mask logic later
            color = if (isEraser) android.graphics.Color.BLACK else state.selectedColor.toArgb()
            alpha = 255
        }

        var drawRadius = state.selectedWidth / 2f
        if (state.brushSoftness > 0f) {
            val blurRadius = drawRadius * state.brushSoftness
            if (blurRadius > 0.1f) {
                paint.maskFilter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
                // Adjust radius to match ToolPreviewLayer logic (subtract full blur radius)
                drawRadius = max(1f, drawRadius - blurRadius)
            }
        }

        if (state.brushTipBitmap != null) {
            val tipPaint = Paint(paint)
            if (!isEraser) {
                tipPaint.colorFilter = PorterDuffColorFilter(state.selectedColor.toArgb(), PorterDuff.Mode.SRC_IN)
            }
            val rect = RectF(center - drawRadius, center - drawRadius, center + drawRadius, center + drawRadius)
            canvas.drawBitmap(state.brushTipBitmap, null, rect, tipPaint)
        } else {
            canvas.drawCircle(center, center, drawRadius, paint)
        }
        
        cachedStampBitmap?.recycle()
        cachedStampBitmap = bitmap
    }

    private fun drawStampStroke(from: Offset, to: Offset, state: DrawingState, seed: Long? = null, targetCanvas: Canvas? = null, randomSource: Random? = null) {
        val canvas = targetCanvas ?: strokeCanvas ?: return

        updateStampCache(state)
        val stamp = cachedStampBitmap ?: return

        sharedPaint.reset()
        sharedPaint.isAntiAlias = true
        sharedPaint.isFilterBitmap = true
        // Only use flow here, opacity is applied during composition.
        // Velocity dynamics use a multiplicative scale (3^x) so +100% and -100% have the
        // same perceptual strength (x3 vs /3). Flow saturates at fully opaque, so positive
        // values are only visible when the base flow is below 100%.
        val velocityFlowFactor = 3f.pow(state.velocityFlowAmount * currentVelocityNorm)
        sharedPaint.alpha = (state.brushFlow * velocityFlowFactor * 255).toInt().coerceIn(0, 255)
        
        // Use SRC_OVER even for eraser here, we are building the "stroke mask"
        sharedPaint.xfermode = null

        val dist = (to - from).getDistance()
        val step = max(1f, state.selectedWidth * state.brushSpacing)
        
        val segmentAngle = if (dist > 0.1f) {
            Math.toDegrees(atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble())).toFloat()
        } else {
            state.brushRotation
        }

        // randomSource lets a caller share ONE random across many short segments.
        // Recreating Random(seed) with sequential seeds is a trap: java.util.Random's
        // first outputs are nearly identical for consecutive seeds, so per-segment
        // jitter would look constant instead of random.
        val localRandom = randomSource ?: if (seed != null) Random(seed) else random

        var remainingDist = distanceSinceLastStamp + dist
        if (remainingDist >= step) {
            var currentPosInSegment = step - distanceSinceLastStamp
            while (currentPosInSegment <= dist) {
                val t = if (dist == 0f) 1f else currentPosInSegment / dist
                val px = from.x + (to.x - from.x) * t
                val py = from.y + (to.y - from.y) * t
                
                val jitter = if (state.sizeJitter > 0f) (1f - (localRandom.nextFloat() * state.sizeJitter)) else 1f

                // Velocity scatter: offset the stamp perpendicular to the stroke direction.
                // Signed: positive scatters fast strokes, negative scatters slow strokes.
                var sx = px
                var sy = py
                if (velocityDynamicsActive && state.velocityScatterAmount != 0f) {
                    val scatterNorm = if (state.velocityScatterAmount >= 0f) currentVelocityNorm else 1f - currentVelocityNorm
                    val amp = abs(state.velocityScatterAmount) * scatterNorm * state.selectedWidth
                    val offsetDist = (localRandom.nextFloat() * 2f - 1f) * amp
                    val perpRad = Math.toRadians((segmentAngle + 90f).toDouble())
                    sx += (cos(perpRad) * offsetDist).toFloat()
                    sy += (sin(perpRad) * offsetDist).toFloat()
                }

                canvas.save()
                canvas.translate(sx, sy)
                
                val finalRotation = if (state.brushRotationDynamics) {
                    segmentAngle + state.brushRotation
                } else {
                    state.brushRotation
                }
                
                val rotJitter = if (state.brushRotationJitter > 0f) {
                    (localRandom.nextFloat() * 2f - 1f) * state.brushRotationJitter
                } else 0f
                
                canvas.rotate(finalRotation + rotJitter)
                
                // CRITICAL FIX: Draw at the correct scale to match selectedWidth
                // The cache is 1.5x larger than selectedWidth, so we draw it at 1.0x its size
                val stampWidth = stamp.width.toFloat()
                // Symmetric multiplicative scale: +100% = x3 at full speed, -100% = /3
                val velocitySizeFactor = 3f.pow(state.velocitySizeAmount * currentVelocityNorm)
                val drawSize = stampWidth * jitter * velocitySizeFactor
                val halfSize = drawSize / 2f
                stampRect.set(-halfSize, -halfSize, halfSize, halfSize)
                canvas.drawBitmap(stamp, null, stampRect, sharedPaint)

                canvas.restore()

                if (targetCanvas == null) {
                    // 1.45 > sqrt(2): bounding radius of the stamp square under any rotation
                    val reach = halfSize * 1.45f
                    if (strokeDirtyValid) {
                        strokeDirtyRect.union(sx - reach, sy - reach, sx + reach, sy + reach)
                    } else {
                        strokeDirtyRect.set(sx - reach, sy - reach, sx + reach, sy + reach)
                        strokeDirtyValid = true
                    }
                }
                currentPosInSegment += step
            }
            distanceSinceLastStamp = dist - (currentPosInSegment - step)
        } else {
            distanceSinceLastStamp += dist
        }
    }

    /**
     * Multiplies the stroke alpha by the tiled texture mask, in canvas coordinates (the
     * shader has an identity local matrix, so the grain stays anchored to the canvas
     * no matter how the stroke was painted).
     */
    private fun applyTextureMask(canvas: Canvas, width: Int, height: Int, state: DrawingState) {
        val mask = state.brushTextureMask ?: return
        val paint = Paint().apply {
            shader = BitmapShader(mask, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    /** White areas of the texture keep paint, dark areas cut it out (luminance -> alpha). */
    private fun buildLuminanceMask(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val lum = (0.299f * android.graphics.Color.red(c) +
                       0.587f * android.graphics.Color.green(c) +
                       0.114f * android.graphics.Color.blue(c)).toInt()
            val alpha = lum * android.graphics.Color.alpha(c) / 255
            pixels[i] = alpha shl 24
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * Renders the current brush onto an S-curve using the exact same stamp engine as real
     * strokes (updateStampCache/drawStampStroke) - a single code path, so the Brush Studio
     * preview can't drift from actual rendering. A synthetic speed profile (slow ends,
     * fast middle) demonstrates the velocity dynamics.
     */
    fun renderBrushPreview(widthPx: Int, heightPx: Int): Bitmap {
        val state = _uiState.value
        val stroke = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(stroke)

        val brushRadius = state.selectedWidth / 2f
        val hPad = (brushRadius + 60f).coerceAtMost(widthPx * 0.3f)
        val vPad = (brushRadius + 40f).coerceAtMost(heightPx * 0.4f)
        val amplitude = (heightPx / 2f - vPad).coerceAtLeast(0f)

        val points = (0..60).map { t ->
            val f = t / 60f
            Offset(
                hPad + (widthPx - 2 * hPad) * f,
                heightPx / 2f + sin(f * PI.toFloat() * 2f) * amplitude
            )
        }

        // Save engine state so an in-progress stroke can't be disturbed
        val savedDistance = distanceSinceLastStamp
        val savedVelocityNorm = currentVelocityNorm
        val savedVelocityActive = velocityDynamicsActive
        distanceSinceLastStamp = max(1f, state.selectedWidth * state.brushSpacing)
        velocityDynamicsActive = state.velocityEnabled

        // One shared random for the whole preview stroke: deterministic (stable image
        // across recompositions) yet properly varied stamp-to-stamp for the jitters
        val previewRandom = Random(42L)
        for (i in 0 until points.size - 1) {
            val f = i.toFloat() / (points.size - 1)
            // Synthetic speed profile (slow ends, fast middle) to demo the velocity dynamics
            currentVelocityNorm = if (state.velocityEnabled) {
                velocityNorm(sin(f * PI.toFloat()) * 40f)
            } else 0f
            drawStampStroke(points[i], points[i + 1], state, targetCanvas = canvas, randomSource = previewRandom)
        }

        distanceSinceLastStamp = savedDistance
        currentVelocityNorm = savedVelocityNorm
        velocityDynamicsActive = savedVelocityActive

        applyTextureMask(canvas, widthPx, heightPx, state)

        // Opacity is normally applied when compositing the stroke - bake it into the preview
        if (state.brushOpacity < 1f) {
            val out = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(stroke, 0f, 0f, Paint().apply { alpha = (state.brushOpacity * 255).toInt() })
            stroke.recycle()
            return out
        }
        return stroke
    }

    fun setPenDown(down: Boolean) {
        val state = _uiState.value
        
        // Priority to Eye Dropper
        if (down && state.isEyeDropperMode) {
            pickColorFromCanvas(state.brushPosition)
            return
        }

        if (state.drawingMode.isSelectionTool()) {
            handleSelectionPen(down, state)
            return
        }

        if (state.drawingMode is DrawingMode.BucketFill) {
            if (down) {
                // Flood fill mutates scattered pixels, so the whole layer is frozen
                saveHistoryState(mapOf(state.activeLayerId to SnapshotSpec.FullMutated))
                updateColorHistory(state.selectedColor)
                performFloodFill(state.brushPosition, state.selectedColor.copy(alpha = state.brushOpacity))
            }
            return
        }

        if (state.isPenDown == down) return

        if (down) {
            // Start of stroke. History is saved at pen-up (commit time), once the stroke's
            // bounding box is known, so only the touched region gets snapshotted.
            strokeBitmap?.eraseColor(android.graphics.Color.TRANSPARENT)
            strokeDirtyValid = false

            updateColorHistory(state.selectedColor)
            distanceSinceLastStamp = max(1f, state.selectedWidth * state.brushSpacing)
            currentStrokeDistance = 0f
            smoothedSpeed = 0f
            currentVelocityNorm = 0f
            velocityDynamicsActive = false

            lastPoint = state.brushPosition
            lastMidPoint = state.brushPosition

            _uiState.update {
                if (state.drawingMode !is DrawingMode.BucketFill && state.drawingMode !is DrawingMode.StraightLine && state.drawingMode !is DrawingMode.Gradient) {
                    drawStampStroke(it.brushPosition, it.brushPosition, it)
                }
                it.copy(
                    isPenDown = true,
                    strokeBitmap = strokeBitmap, // Ensure UI has the latest reference
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
            saveHistoryState(strokeSnapshotSpec(_uiState.value))
            commitStrokeToLayer()

            smoothedVelocity = Offset.Zero
            _uiState.update { it.copy(isPenDown = false, currentPath = null) }
            saveLayerToFile(state.activeLayerId)
            updateProjectTimestamp()
        }
    }

    /**
     * Snapshot spec for the stroke about to be committed: the active layer cropped to the
     * stroke's dirty rect. Gradient paints the whole canvas, so it snapshots the full layer.
     */
    private fun strokeSnapshotSpec(state: DrawingState): Map<Long, SnapshotSpec> {
        val layerBitmap = layerBitmaps[state.activeLayerId] ?: return emptyMap()
        if (state.drawingMode is DrawingMode.Gradient) {
            return mapOf(state.activeLayerId to SnapshotSpec.FullMutated)
        }
        if (!strokeDirtyValid) return emptyMap()
        val r = Rect()
        strokeDirtyRect.roundOut(r)
        r.inset(-2, -2) // antialiasing/filtering bleed margin
        if (!r.intersect(0, 0, layerBitmap.width, layerBitmap.height)) return emptyMap()
        return mapOf(state.activeLayerId to SnapshotSpec.Region(r))
    }

    private fun commitStrokeToLayer() {
        val state = _uiState.value
        val layerBitmap = layerBitmaps[state.activeLayerId] ?: return
        val stroke = strokeBitmap ?: return

        // Canvas-anchored paper grain: mask the whole stroke once before compositing.
        // The stroke bitmap is erased at the next pen-down, so mutating it here is safe.
        applyTextureMask(Canvas(stroke), stroke.width, stroke.height, state)

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

    // ============================ Selection Tool ============================

    private fun buildSelectionPath(points: List<Offset>): Path {
        val path = Path()
        if (points.isEmpty()) return path
        path.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
        path.close()
        return path
    }

    private fun polygonArea(points: List<Offset>): Float {
        if (points.size < 3) return 0f
        var sum = 0f
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) / 2f
    }

    private fun isPointInSelection(pos: Offset, state: DrawingState): Boolean {
        val mask = state.selectionMask ?: return false
        val x = pos.x.toInt()
        val y = pos.y.toInt()
        if (x !in 0 until mask.width || y !in 0 until mask.height) return false
        return android.graphics.Color.alpha(mask.getPixel(x, y)) > 0
    }

    private fun buildMaskFromPath(points: List<Offset>, state: DrawingState): Bitmap {
        val mask = Bitmap.createBitmap(state.canvasWidth, state.canvasHeight, Bitmap.Config.ARGB_8888)
        Canvas(mask).drawPath(buildSelectionPath(points), Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
        })
        return mask
    }

    private fun updateSelectionDrag(pos: Offset, state: DrawingState) {
        if (state.floatingBitmap != null) {
            val delta = pos - state.brushPosition
            _uiState.update { it.copy(floatingOffset = it.floatingOffset + delta) }
        } else if (!state.isSelectionClosed && state.selectionPoints.isNotEmpty()) {
            if (state.drawingMode is DrawingMode.SelectRect) {
                val minX = min(rectAnchor.x, pos.x)
                val maxX = max(rectAnchor.x, pos.x)
                val minY = min(rectAnchor.y, pos.y)
                val maxY = max(rectAnchor.y, pos.y)
                _uiState.update { it.copy(selectionPoints = listOf(
                    Offset(minX, minY), Offset(maxX, minY), Offset(maxX, maxY), Offset(minX, maxY)
                )) }
            } else {
                if ((pos - state.selectionPoints.last()).getDistance() > 2f) {
                    _uiState.update { it.copy(selectionPoints = it.selectionPoints + pos) }
                }
            }
        }
    }

    private fun handleSelectionPen(down: Boolean, state: DrawingState) {
        if (state.isPenDown == down) return
        if (down) {
            currentStrokeDistance = 0f
            val pos = state.brushPosition
            when {
                // Pen down while floating: cursor drag now moves the selection
                state.floatingBitmap != null -> Unit
                // Pen down inside a closed selection: lift it so it can be moved
                state.isSelectionClosed && isPointInSelection(pos, state) -> liftSelection(cut = true)
                // Wand/Color: a tap computes the pixel selection at the cursor
                state.drawingMode is DrawingMode.SelectWand -> computeMagicSelection(pos, contiguous = true)
                state.drawingMode is DrawingMode.SelectColor -> computeMagicSelection(pos, contiguous = false)
                // Lasso/Rect: start tracing a new outline
                else -> {
                    rectAnchor = pos
                    _uiState.update { it.copy(selectionPoints = listOf(pos), isSelectionClosed = false, selectionMask = null) }
                }
            }
            _uiState.update { it.copy(isPenDown = true) }
        } else {
            val s = _uiState.value
            val isTracing = (s.drawingMode is DrawingMode.SelectLasso || s.drawingMode is DrawingMode.SelectRect) &&
                s.floatingBitmap == null && !s.isSelectionClosed && s.selectionPoints.isNotEmpty()
            if (isTracing) {
                // Close the traced outline; discard degenerate selections
                val pts = s.selectionPoints
                val valid = pts.size >= 3 && polygonArea(pts) > 25f
                _uiState.update { it.copy(
                    isPenDown = false,
                    isSelectionClosed = valid,
                    selectionPoints = if (valid) pts else emptyList(),
                    selectionMask = if (valid) buildMaskFromPath(pts, s) else null
                ) }
            } else {
                _uiState.update { it.copy(isPenDown = false) }
            }
            smoothedVelocity = Offset.Zero
        }
    }

    /**
     * Wand (contiguous flood by color similarity) and Color (every similar pixel on the
     * active layer) selections. Both use the fill tolerance setting.
     */
    private fun computeMagicSelection(pos: Offset, contiguous: Boolean) {
        val state = _uiState.value
        val layerBitmap = layerBitmaps[state.activeLayerId] ?: return
        val startX = pos.x.toInt().coerceIn(0, layerBitmap.width - 1)
        val startY = pos.y.toInt().coerceIn(0, layerBitmap.height - 1)
        val tolerance = state.fillTolerance.toInt()

        viewModelScope.launch(Dispatchers.Default) {
            val w = layerBitmap.width
            val h = layerBitmap.height
            val pixels = IntArray(w * h)
            layerBitmap.getPixels(pixels, 0, w, 0, 0, w, h)

            val target = pixels[startY * w + startX]
            val tA = android.graphics.Color.alpha(target)
            val tR = android.graphics.Color.red(target)
            val tG = android.graphics.Color.green(target)
            val tB = android.graphics.Color.blue(target)

            fun matches(c: Int): Boolean {
                val a = android.graphics.Color.alpha(c)
                if (abs(a - tA) > tolerance) return false
                // Fully transparent pixels have meaningless RGB: alpha match is enough
                if (tA <= tolerance && a <= tolerance) return true
                return abs(android.graphics.Color.red(c) - tR) <= tolerance &&
                       abs(android.graphics.Color.green(c) - tG) <= tolerance &&
                       abs(android.graphics.Color.blue(c) - tB) <= tolerance
            }

            val white = android.graphics.Color.WHITE
            val maskPixels = IntArray(w * h)
            if (contiguous) {
                val visited = java.util.BitSet(w * h)
                val queue: Queue<Int> = LinkedList()
                queue.add(startY * w + startX)
                visited.set(startY * w + startX)
                while (queue.isNotEmpty()) {
                    val index = queue.remove()
                    if (!matches(pixels[index])) continue
                    maskPixels[index] = white
                    val px = index % w
                    val py = index / w
                    if (px > 0 && !visited.get(index - 1)) { visited.set(index - 1); queue.add(index - 1) }
                    if (px < w - 1 && !visited.get(index + 1)) { visited.set(index + 1); queue.add(index + 1) }
                    if (py > 0 && !visited.get(index - w)) { visited.set(index - w); queue.add(index - w) }
                    if (py < h - 1 && !visited.get(index + w)) { visited.set(index + w); queue.add(index + w) }
                }
            } else {
                for (i in pixels.indices) {
                    if (matches(pixels[i])) maskPixels[i] = white
                }
            }

            val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            mask.setPixels(maskPixels, 0, w, 0, 0, w, h)

            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(
                    selectionMask = mask,
                    isSelectionClosed = true,
                    selectionPoints = emptyList(),
                    renderVersion = it.renderVersion + 1
                ) }
            }
        }
    }

    /** Inverts the selected area (mask-based, so it works for every selection tool). */
    fun invertSelection() {
        val state = _uiState.value
        val mask = state.selectionMask ?: return
        if (state.floatingBitmap != null) return
        val inverted = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(inverted)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.drawBitmap(mask, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        _uiState.update { it.copy(
            selectionMask = inverted,
            // The polygon outline no longer describes the area: the tinted overlay takes over
            selectionPoints = emptyList(),
            renderVersion = it.renderVersion + 1
        ) }
    }

    /**
     * Extracts the selected pixels into a floating bitmap. With [cut] the pixels are
     * removed from the layer (move); without, they are copied (duplicate).
     */
    fun liftSelection(cut: Boolean) {
        val state = _uiState.value
        if (!state.isSelectionClosed || state.floatingBitmap != null) return
        val mask = state.selectionMask ?: return
        val layerBitmap = layerBitmaps[state.activeLayerId] ?: return

        // Bounds of the selected area (mask alpha)
        val w = mask.width
        val h = mask.height
        val maskPixels = IntArray(w * h)
        mask.getPixels(maskPixels, 0, w, 0, 0, w, h)
        var left = w; var top = h; var right = -1; var bottom = -1
        for (i in maskPixels.indices) {
            if (maskPixels[i] ushr 24 != 0) {
                val px = i % w
                val py = i / w
                if (px < left) left = px
                if (px > right) right = px
                if (py < top) top = py
                if (py > bottom) bottom = py
            }
        }
        if (right < 0) { clearSelection(); return }
        right = (right + 1).coerceAtMost(layerBitmap.width)
        bottom = (bottom + 1).coerceAtMost(layerBitmap.height)

        val floatBitmap = Bitmap.createBitmap(right - left, bottom - top, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(floatBitmap)
        canvas.translate(-left.toFloat(), -top.toFloat())
        canvas.drawBitmap(layerBitmap, 0f, 0f, null)
        canvas.drawBitmap(mask, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })

        if (cut) {
            // Full layer: this one entry must also cover the later paste, which can land anywhere
            saveHistoryState(mapOf(state.activeLayerId to SnapshotSpec.FullMutated))
            Canvas(layerBitmap).drawBitmap(mask, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        }
        floatingFromCut = cut

        val origin = Offset(left.toFloat(), top.toFloat())
        _uiState.update { it.copy(
            floatingBitmap = floatBitmap,
            floatingOrigin = origin,
            // Nudge duplicates so the copy is immediately visible
            floatingOffset = if (cut) origin else origin + Offset(24f, 24f),
            floatingScale = 1f,
            floatingRotation = 0f,
            renderVersion = it.renderVersion + 1
        ) }
    }

    fun duplicateSelection() {
        if (_uiState.value.floatingBitmap != null) return
        liftSelection(cut = false)
    }

    fun deleteSelection() {
        val state = _uiState.value
        if (state.floatingBitmap != null) {
            // A cut selection is already gone from the layer (history saved at lift);
            // a duplicated copy never touched it. Dropping the floating bitmap is enough.
            clearSelection()
            return
        }
        if (!state.isSelectionClosed) return
        val mask = state.selectionMask ?: return
        val layerBitmap = layerBitmaps[state.activeLayerId] ?: return
        saveHistoryState(mapOf(state.activeLayerId to SnapshotSpec.FullMutated))
        Canvas(layerBitmap).drawBitmap(mask, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        clearSelection()
        saveLayerToFile(state.activeLayerId)
        updateProjectTimestamp()
    }

    /**
     * Imports an image as a floating selection. The user can move/transform it with the
     * regular selection controls; the new layer is only created when the import is applied,
     * so cancelling leaves no empty layer behind.
     */
    fun importImageAsLayer(context: android.content.Context, uri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val decoded = decodeReferenceImage(context, uri)
                val bitmap = decoded.bitmap ?: return@launch
                val state = _uiState.value

                // Fit oversized images inside the canvas, keeping aspect ratio
                val fit = min(1f, min(state.canvasWidth / decoded.width, state.canvasHeight / decoded.height))
                val scaled = if (fit < 1f) {
                    Bitmap.createScaledBitmap(
                        bitmap,
                        (decoded.width * fit).toInt().coerceAtLeast(1),
                        (decoded.height * fit).toInt().coerceAtLeast(1),
                        true
                    )
                } else bitmap

                withContext(Dispatchers.Main) {
                    // Enables the selection move/transform controls; finalizes any active selection
                    setDrawingMode(DrawingMode.SelectRect)
                    pendingImport = true
                    floatingFromCut = false

                    val origin = Offset((state.canvasWidth - scaled.width) / 2f, (state.canvasHeight - scaled.height) / 2f)
                    val w = scaled.width.toFloat()
                    val h = scaled.height.toFloat()
                    _uiState.update { it.copy(
                        selectionPoints = listOf(origin, origin + Offset(w, 0f), origin + Offset(w, h), origin + Offset(0f, h)),
                        isSelectionClosed = true,
                        floatingBitmap = scaled,
                        floatingOrigin = origin,
                        floatingOffset = origin,
                        floatingScale = 1f,
                        floatingRotation = 0f,
                        renderVersion = it.renderVersion + 1
                    ) }
                }
            } catch (e: Exception) {
                android.util.Log.e("DrawingViewModel", "Failed to import image as layer", e)
            }
        }
    }

    private fun commitImportedLayer(state: DrawingState, floatBitmap: Bitmap) {
        pendingImport = false
        viewModelScope.launch {
            saveHistoryState(emptyMap()) // a brand-new layer is added; existing bitmaps stay untouched
            val zIndex = (_uiState.value.layers.maxOfOrNull { it.zIndex } ?: 0) + 1
            val newLayerId = repository.insertLayer(LayerEntity(projectId = projectId, name = "Imported Image", zIndex = zIndex))

            val layerBitmap = Bitmap.createBitmap(state.canvasWidth, state.canvasHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(layerBitmap)
            canvas.save()
            canvas.translate(state.floatingOffset.x + floatBitmap.width / 2f, state.floatingOffset.y + floatBitmap.height / 2f)
            canvas.rotate(state.floatingRotation)
            canvas.scale(state.floatingScale, state.floatingScale)
            canvas.drawBitmap(floatBitmap, -floatBitmap.width / 2f, -floatBitmap.height / 2f, Paint().apply {
                isAntiAlias = true
                isFilterBitmap = true
            })
            canvas.restore()

            layerBitmaps[newLayerId] = layerBitmap
            _uiState.update { it.copy(
                activeLayerId = newLayerId,
                layerBitmaps = layerBitmaps.toMap(),
                // Leave the selection tool unless the user already switched to something else
                drawingMode = if (it.drawingMode is DrawingMode.SelectRect) DrawingMode.Freehand else it.drawingMode
            ) }
            clearSelection()
            saveLayerToFile(newLayerId)
            updateProjectTimestamp()
        }
    }

    /**
     * Canvas-space bounding box of the floating bitmap under its current translate/rotate/scale
     * transform (mirrors the Canvas ops in [commitSelection]), padded for filtering bleed.
     */
    private fun floatingPasteBounds(state: DrawingState, floatBitmap: Bitmap): Rect {
        val m = Matrix()
        m.setTranslate(state.floatingOffset.x + floatBitmap.width / 2f, state.floatingOffset.y + floatBitmap.height / 2f)
        m.preRotate(state.floatingRotation)
        m.preScale(state.floatingScale, state.floatingScale)
        m.preTranslate(-floatBitmap.width / 2f, -floatBitmap.height / 2f)
        val mapped = RectF(0f, 0f, floatBitmap.width.toFloat(), floatBitmap.height.toFloat())
        m.mapRect(mapped)
        val out = Rect()
        mapped.roundOut(out)
        out.inset(-2, -2)
        return out
    }

    /** Draws the floating selection back onto the active layer with its current transform. */
    fun commitSelection() {
        val state = _uiState.value
        val floatBitmap = state.floatingBitmap ?: return
        if (pendingImport) {
            commitImportedLayer(state, floatBitmap)
            return
        }
        val layerBitmap = layerBitmaps[state.activeLayerId]
        if (layerBitmap != null) {
            // A cut already saved history at lift time, so the whole move undoes as one step.
            // A duplicate only paints inside the transformed floating rect - snapshot just that.
            if (!floatingFromCut) {
                val bounds = floatingPasteBounds(state, floatBitmap)
                saveHistoryState(mapOf(state.activeLayerId to SnapshotSpec.Region(bounds)))
            }
            val canvas = Canvas(layerBitmap)
            canvas.save()
            canvas.translate(state.floatingOffset.x + floatBitmap.width / 2f, state.floatingOffset.y + floatBitmap.height / 2f)
            canvas.rotate(state.floatingRotation)
            canvas.scale(state.floatingScale, state.floatingScale)
            canvas.drawBitmap(floatBitmap, -floatBitmap.width / 2f, -floatBitmap.height / 2f, Paint().apply {
                isAntiAlias = true
                isFilterBitmap = true
            })
            canvas.restore()
            saveLayerToFile(state.activeLayerId)
            updateProjectTimestamp()
        }
        clearSelection()
    }

    /** Cancels a floating selection: cut pixels are restored via the history entry saved at lift. */
    fun cancelSelection() {
        val wasImport = pendingImport
        if (_uiState.value.floatingBitmap != null && floatingFromCut) {
            abortCurrentStroke()
        }
        clearSelection()
        if (wasImport) {
            _uiState.update { if (it.drawingMode is DrawingMode.SelectRect) it.copy(drawingMode = DrawingMode.Freehand) else it }
        }
    }

    fun clearSelection() {
        floatingFromCut = false
        pendingImport = false
        _uiState.update { it.copy(
            selectionPoints = emptyList(),
            isSelectionClosed = false,
            selectionMask = null,
            floatingBitmap = null,
            floatingScale = 1f,
            floatingRotation = 0f,
            renderVersion = it.renderVersion + 1
        ) }
    }

    fun setSelectionScale(scale: Float) {
        _uiState.update { it.copy(floatingScale = scale.coerceIn(0.1f, 3f)) }
    }

    fun setSelectionRotation(degrees: Float) {
        _uiState.update { it.copy(floatingRotation = degrees.coerceIn(-180f, 180f)) }
    }

    /**
     * Called when switching tools: commits any floating selection and drops an unfinished
     * trace. A CLOSED selection is deliberately kept - it acts as a mask that clips every
     * drawing tool until the user deselects.
     */
    private fun finalizeSelection() {
        val state = _uiState.value
        if (state.floatingBitmap != null) {
            commitSelection()
        } else if (!state.isSelectionClosed && state.selectionPoints.isNotEmpty()) {
            clearSelection()
        }
    }

    // ========================================================================

    private fun updateColorHistory(color: Color) {
        _uiState.update { state ->
            if (state.colorHistory.firstOrNull() == color) return@update state
            val newHistory = (listOf(color) + state.colorHistory).distinct().take(6)
            viewModelScope.launch {
                preferenceManager.setColorHistory(newHistory.map { String.format("#%06X", (0xFFFFFF and it.toArgb())) })
            }
            state.copy(colorHistory = newHistory)
        }
    }

    fun togglePen() {
        setPenDown(!_uiState.value.isPenDown)
    }

    private fun commitCurrentPath() {
        val state = _uiState.value
        val path = state.currentPath ?: return
        if (path.points.size < 2) return

        distanceSinceLastStamp = max(1f, state.selectedWidth * state.brushSpacing)
        currentVelocityNorm = 0f
        velocityDynamicsActive = false

        // Ensure the stroke is drawn into the temporary bitmap
        strokeBitmap?.eraseColor(android.graphics.Color.TRANSPARENT)
        strokeDirtyValid = false
        drawStampStroke(path.points.first(), path.points.last(), state, seed = 0L)

        _uiState.update { it.copy(renderVersion = it.renderVersion + 1) }
    }

    private fun performFloodFill(pos: Offset, color: Color) {
        viewModelScope.launch(Dispatchers.Default) {
            val activeId = _uiState.value.activeLayerId
            val bitmap = layerBitmaps[activeId] ?: return@launch
            
            val x = pos.x.toInt().coerceIn(0, bitmap.width - 1)
            val y = pos.y.toInt().coerceIn(0, bitmap.height - 1)
            val targetColor = bitmap.getPixel(x, y)
            val replacementColor = color.toArgb()
            
            if (targetColor == replacementColor) return@launch

            floodFillAlgorithm(bitmap, x, y, targetColor, replacementColor)
            
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(renderVersion = it.renderVersion + 1) }
                saveLayerToFile(activeId)
                updateProjectTimestamp()
            }
        }
    }

    fun setFillTolerance(tolerance: Float) {
        viewModelScope.launch {
            preferenceManager.setFillTolerance(tolerance)
        }
    }

    private fun floodFillAlgorithm(bitmap: Bitmap, x: Int, y: Int, targetColor: Int, replacementColor: Int) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val queue: Queue<Point> = LinkedList()
        queue.add(Point(x, y))

        val tolerance = _uiState.value.fillTolerance
        val visited = java.util.BitSet(width * height)

        // Active selection: the fill can't leak outside the selected area
        val maskPixels = _uiState.value.selectionMask?.let { mask ->
            IntArray(width * height).also { mask.getPixels(it, 0, width, 0, 0, width, height) }
        }

        val targetA = android.graphics.Color.alpha(targetColor)
        val targetR = android.graphics.Color.red(targetColor)
        val targetG = android.graphics.Color.green(targetColor)
        val targetB = android.graphics.Color.blue(targetColor)

        while (queue.isNotEmpty()) {
            val p = queue.remove()
            val px = p.x
            val py = p.y

            if (px < 0 || px >= width || py < 0 || py >= height) continue
            
            val index = py * width + px
            if (visited.get(index)) continue
            if (maskPixels != null && (maskPixels[index] ushr 24) == 0) continue

            val color = pixels[index]
            
            val diffA = abs(android.graphics.Color.alpha(color) - targetA)
            val diffR = abs(android.graphics.Color.red(color) - targetR)
            val diffG = abs(android.graphics.Color.green(color) - targetG)
            val diffB = abs(android.graphics.Color.blue(color) - targetB)
            
            val maxDiff = max(max(diffR, diffG), diffB) // Simple tolerance check
            
            if (maxDiff <= tolerance && diffA <= tolerance) {
                pixels[index] = replacementColor
                visited.set(index)

                queue.add(Point(px + 1, py))
                queue.add(Point(px - 1, py))
                queue.add(Point(px, py + 1))
                queue.add(Point(px, py - 1))
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    /**
     * Pushes one undo entry. [specs] lists, per layer, which pixels the upcoming operation
     * will overwrite or remove (see [SnapshotSpec]); metadata-only operations pass an empty
     * map. Must be called before the operation mutates the layer bitmaps.
     */
    private fun saveHistoryState(specs: Map<Long, SnapshotSpec>) {
        val state = _uiState.value
        if (state.projectId == -1L) return
        historyManager.saveState(layerBitmaps, state.layers, state.activeLayerId, state.historyLimit, specs)
        _uiState.update { it.copy(canUndo = true, canRedo = false) }
    }

    /**
     * Serializes undo/redo/abort: each operation waits for the previous restore to complete,
     * so the inverse entry is always captured from a fully applied state (region deltas pasted
     * out of order would corrupt pixels, not just skip a step).
     */
    private var historyJob: Job? = null

    private fun launchHistoryOp(block: suspend () -> Unit) {
        val previous = historyJob
        historyJob = viewModelScope.launch(Dispatchers.Main) {
            previous?.join()
            block()
        }
    }

    /**
     * Specialized method for the FAB drag-to-move feature.
     * Silently removes the last history state and restores bitmaps.
     */
    fun abortCurrentStroke() {
        launchHistoryOp {
            val lastState = historyManager.popUndo() ?: return@launchHistoryOp
            applyHistoryState(lastState)
            _uiState.update { it.copy(canUndo = historyManager.hasUndo) }
        }
    }

    fun undo() {
        // Undo while a selection is floating cancels the move; a closed selection is kept
        // (it's a drawing mask now) and undo applies to strokes as usual
        if (_uiState.value.floatingBitmap != null) { cancelSelection(); return }
        launchHistoryOp {
            val prevState = historyManager.popUndo() ?: return@launchHistoryOp
            val state = _uiState.value
            historyManager.pushToRedo(
                historyManager.captureInverse(layerBitmaps, state.layers, state.activeLayerId, prevState)
            )
            applyHistoryState(prevState)
        }
    }

    fun redo() {
        if (_uiState.value.floatingBitmap != null) { cancelSelection(); return }
        launchHistoryOp {
            val nextState = historyManager.popRedo() ?: return@launchHistoryOp
            val state = _uiState.value
            historyManager.pushToUndo(
                historyManager.captureInverse(layerBitmaps, state.layers, state.activeLayerId, nextState)
            )
            applyHistoryState(nextState)
        }
    }

    private suspend fun applyHistoryState(history: HistoryState) {
        // If any snapshot is unrecoverable, leave the canvas untouched rather than corrupting it
        val restored = historyManager.restoreBitmaps(history) ?: return

        historyManager.syncLayersWithDatabase(history)

        // Drop layers this state doesn't have (reverts an add/duplicate/import)
        val targetIds = history.layersMetadata.map { it.id }.toSet()
        layerBitmaps.keys.retainAll(targetIds)

        val restorePaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        history.snapshots.forEach { (layerId, snap) ->
            val pixels = restored[layerId] ?: return@forEach
            val existing = layerBitmaps[layerId]
            if (existing == null) {
                // The reverted operation removed this layer; bring its full bitmap back
                if (snap.isFullLayer && layerId in targetIds) layerBitmaps[layerId] = pixels
            } else {
                // Replace exactly the frozen region, transparent pixels included
                Canvas(existing).drawBitmap(pixels, snap.left.toFloat(), snap.top.toFloat(), restorePaint)
            }
        }

        // Safety net: a layer present in metadata but without pixels gets a blank bitmap
        val st = _uiState.value
        if (st.canvasWidth > 0 && st.canvasHeight > 0) {
            history.layersMetadata.forEach { meta ->
                if (meta.id !in layerBitmaps) {
                    layerBitmaps[meta.id] = Bitmap.createBitmap(st.canvasWidth, st.canvasHeight, Bitmap.Config.ARGB_8888)
                }
            }
        }

        _uiState.update { it.copy(
            layers = history.layersMetadata,
            activeLayerId = history.activeLayerId,
            layerBitmaps = layerBitmaps.toMap(),
            renderVersion = it.renderVersion + 1,
            canUndo = historyManager.hasUndo,
            canRedo = historyManager.hasRedo
        ) }

        // Re-save only the layers this restore actually touched
        history.snapshots.keys.forEach { id ->
            layerBitmaps[id]?.let { saveLayerToFileAsync(id, it) }
        }
        updateProjectTimestamp()
    }

    private suspend fun saveLayerToFileAsync(layerId: Long, bitmap: Bitmap) {
        withContext(Dispatchers.IO) {
            val file = File(internalFilesDir, "layer_${layerId}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val layer = _uiState.value.layers.find { it.id == layerId }
            if (layer != null) {
                repository.updateLayer(layer.copy(imagePath = file.absolutePath))
            }
        }
    }

    private fun saveLayerToFile(layerId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val bitmap = layerBitmaps[layerId] ?: return@launch
            val layer = _uiState.value.layers.find { it.id == layerId } ?: return@launch
            val file = File(internalFilesDir, "layer_${layerId}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            repository.updateLayer(layer.copy(imagePath = file.absolutePath))
            generateThumbnail(_uiState.value)
        }
    }

    private fun generateThumbnail(state: DrawingState) {
        viewModelScope.launch(Dispatchers.Default) {
            if (state.canvasWidth <= 0 || state.canvasHeight <= 0) return@launch
            val thumb = Bitmap.createBitmap(state.canvasWidth, state.canvasHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(thumb)
            canvas.drawColor(android.graphics.Color.WHITE)
            state.layers.filter { it.isVisible }.forEach { layer ->
                layerBitmaps[layer.id]?.let {
                    val paint = Paint().apply { alpha = (layer.opacity * 255).toInt() }
                    canvas.drawBitmap(it, 0f, 0f, paint)
                }
            }
            val thumbFile = File(internalFilesDir, "thumb_${projectId}.png")
            FileOutputStream(thumbFile).use { out -> thumb.compress(Bitmap.CompressFormat.PNG, 90, out) }
            repository.updateProjectThumbnail(projectId, thumbFile.absolutePath)
        }
    }

    private fun updateProjectTimestamp() {
        viewModelScope.launch {
            repository.updateProjectTimestamp(projectId)
            saveProjectBrushSettings()
        }
    }

    suspend fun saveProjectBrushSettings() {
        val state = _uiState.value
        val project = repository.getProjectById(projectId) ?: return
        val ref = state.referenceImage
        repository.updateProject(project.copy(
            lastCursorSensitivity = state.cursorSensitivity,
            lastActiveLayerId = state.activeLayerId,
            lastBrushSize = state.selectedWidth,
            lastBrushSoftness = state.brushSoftness,
            lastBrushOpacity = state.brushOpacity,
            lastBrushFlow = state.brushFlow,
            lastBrushSpacing = state.brushSpacing,
            lastBrushSmoothing = state.brushSmoothing,
            lastBrushColor = state.selectedColor.toArgb(),
            lastBrushRotation = state.brushRotation,
            lastBrushRotationDynamics = state.brushRotationDynamics,
            lastBrushRotationJitter = state.brushRotationJitter,
            lastSizeJitter = state.sizeJitter,
            lastBrushTipUri = state.brushTipUri,
            lastBrushTextureUri = state.brushTextureUri,
            
            referenceImageUri = ref?.uri,
            referenceImageOffsetX = ref?.offset?.x ?: 0f,
            referenceImageOffsetY = ref?.offset?.y ?: 0f,
            referenceImageScale = ref?.scale ?: 1f,
            referenceImageRotation = ref?.rotation ?: 0f
        ))
    }

    /**
     * Applies a brush-related state change and persists it as the project's last-used brush
     * setting. [persistPreference] is used by the handful of settings that are also saved as a
     * global default (via [preferenceManager]) rather than just per-project.
     */
    private fun updateBrushSetting(persistPreference: (suspend () -> Unit)? = null, update: (DrawingState) -> DrawingState) {
        _uiState.update(update)
        viewModelScope.launch {
            persistPreference?.invoke()
            saveProjectBrushSettings()
        }
    }

    fun selectColor(color: Color) = updateBrushSetting { it.copy(selectedColor = color, isEyeDropperMode = false) }

    fun toggleEyeDropper() {
        _uiState.update { it.copy(isEyeDropperMode = !it.isEyeDropperMode) }
    }

    fun toggleLazyMode() {
        _uiState.update { it.copy(isLazyModeActive = !it.isLazyModeActive) }
    }

    fun setLazyRadius(radius: Float) {
        _uiState.update { it.copy(lazyRadius = radius) }
    }

    fun pickColorFromCanvas(pos: Offset) {
        val state = _uiState.value
        val x = pos.x.toInt()
        val y = pos.y.toInt()

        val layers = state.layers.sortedByDescending { it.zIndex }
        for (layer in layers) {
            if (!layer.isVisible) continue
            val bitmap = layerBitmaps[layer.id] ?: continue
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
        val state = _uiState.value
        val ref = state.referenceImage ?: return
        val bitmap = ref.bitmap ?: return
        
        val x = (relativePos.x * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val y = (relativePos.y * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        
        val pixel = bitmap.getPixel(x, y)
        selectColor(Color(pixel))
    }
    
    fun selectWidth(width: Float) = updateBrushSetting { it.copy(selectedWidth = width) }

    fun setBrushSoftness(softness: Float) = updateBrushSetting({ preferenceManager.setBrushSoftness(softness) }) { it.copy(brushSoftness = softness) }

    fun setBrushSmoothing(smoothing: Float) = updateBrushSetting({ preferenceManager.setBrushSmoothing(smoothing) }) { it.copy(brushSmoothing = smoothing) }

    fun setBrushOpacity(opacity: Float) = updateBrushSetting { it.copy(brushOpacity = opacity) }
    fun setBrushFlow(flow: Float) = updateBrushSetting { it.copy(brushFlow = flow) }
    fun setBrushSpacing(spacing: Float) = updateBrushSetting { it.copy(brushSpacing = spacing) }
    fun setBrushRotation(rotation: Float) = updateBrushSetting { it.copy(brushRotation = rotation) }

    fun setRotationDynamics(enabled: Boolean) = updateBrushSetting({ preferenceManager.setRotationDynamics(enabled) }) { it.copy(brushRotationDynamics = enabled) }

    fun setRotationJitter(jitter: Float) = updateBrushSetting({ preferenceManager.setRotationJitter(jitter) }) { it.copy(brushRotationJitter = jitter) }

    fun setSizeJitter(jitter: Float) = updateBrushSetting { it.copy(sizeJitter = jitter) }

    fun updateZoom(s: Float) {
        canvasAnimJob?.cancel()
        _uiState.update { state ->
            val oldScale = state.canvasScale
            val newScale = (oldScale * s).coerceIn(0.1f, 15f)
            val center = Offset(state.canvasWidth / 2f, state.canvasHeight / 2f)
            val relPivot = state.cursorPosition - center
            val rotatedPivotOld = relPivot.rotate(state.canvasRotation) * oldScale
            val rotatedPivotNew = relPivot.rotate(state.canvasRotation) * newScale
            val diff = rotatedPivotNew - rotatedPivotOld
            state.copy(canvasScale = newScale, canvasOffset = state.canvasOffset - diff)
        }
    }

    fun updateTransform(zoom: Float, pan: Offset, rotation: Float) {
        canvasAnimJob?.cancel()
        _uiState.update { state ->
            val oldScale = state.canvasScale
            val newScale = (oldScale * zoom).coerceIn(0.01f, 25f)
            
            val oldRot = state.canvasRotation
            val newRot = (oldRot + rotation)
            
            val center = Offset(state.canvasWidth / 2f, state.canvasHeight / 2f)
            val relPivot = state.cursorPosition - center
            
            // Screen position of the pivot before transformation (relative to screen center)
            val screenPivotOld = relPivot.rotate(oldRot) * oldScale
            
            // Screen position of the pivot after scale/rotation change (relative to screen center)
            val screenPivotNew = relPivot.rotate(newRot) * newScale
            
            // Adjust offset to keep the screen position constant (compensating for the shift)
            val pivotAdjustment = screenPivotNew - screenPivotOld
            
            state.copy(
                canvasScale = newScale,
                canvasRotation = newRot % 360f,
                canvasOffset = state.canvasOffset - pivotAdjustment + pan
            )
        }
    }

    fun fitToScreen(screenWidth: Float, screenHeight: Float, animate: Boolean = false) {
        val state = _uiState.value
        if (state.canvasWidth <= 0 || state.canvasHeight <= 0) return

        val scaleX = screenWidth / state.canvasWidth
        val scaleY = screenHeight / state.canvasHeight
        val targetScale = min(scaleX, scaleY) * 0.95f

        canvasAnimJob?.cancel()
        if (!animate) {
            _uiState.update { it.copy(
                canvasScale = targetScale,
                canvasOffset = Offset.Zero,
                canvasRotation = 0f,
                fitToScreenTrigger = 0 // Reset trigger
            ) }
            return
        }

        val startScale = state.canvasScale
        val startOffset = state.canvasOffset
        val startRotation = state.canvasRotation
        // Rotate back along the shortest path (350° eases to 360°, not all the way back to 0°)
        var rotNorm = startRotation % 360f
        if (rotNorm > 180f) rotNorm -= 360f
        if (rotNorm < -180f) rotNorm += 360f
        val targetRotation = startRotation - rotNorm

        _uiState.update { it.copy(fitToScreenTrigger = 0) }
        canvasAnimJob = viewModelScope.launch {
            // Time-driven loop: Animatable.animateTo needs a Compose MonotonicFrameClock,
            // which viewModelScope doesn't have (it crashes). The easing itself is pure math.
            val durationMs = 400L
            val startTime = android.os.SystemClock.uptimeMillis()
            while (true) {
                val fraction = ((android.os.SystemClock.uptimeMillis() - startTime).toFloat() / durationMs).coerceIn(0f, 1f)
                val t = FastOutSlowInEasing.transform(fraction)
                _uiState.update { it.copy(
                    canvasScale = startScale + (targetScale - startScale) * t,
                    canvasOffset = startOffset * (1f - t),
                    canvasRotation = startRotation + (targetRotation - startRotation) * t
                ) }
                if (fraction >= 1f) break
                delay(16)
            }
            // Land exactly on the target, with rotation normalized to a clean 0
            _uiState.update { it.copy(canvasScale = targetScale, canvasOffset = Offset.Zero, canvasRotation = 0f) }
        }
    }

    fun requestFitToScreen() {
        _uiState.update { it.copy(fitToScreenTrigger = it.fitToScreenTrigger + 1) }
    }

    fun setDrawingMode(mode: DrawingMode) {
        finalizeSelection()
        _uiState.update { it.copy(drawingMode = mode, isEyeDropperMode = false) }
    }

    fun toggleDrawingMode() {
        finalizeSelection()
        _uiState.update { 
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
        finalizeSelection()
        _uiState.update {
            val newMode = if (it.drawingMode is DrawingMode.BucketFill) DrawingMode.Freehand else DrawingMode.BucketFill
            it.copy(drawingMode = newMode, isEyeDropperMode = false) 
        } 
    }
    fun toggleEraser() {
        finalizeSelection()
        _uiState.update {
            val newMode = when (it.drawingMode) {
                is DrawingMode.Eraser -> DrawingMode.Freehand
                is DrawingMode.StraightLineEraser -> DrawingMode.StraightLine
                is DrawingMode.StraightLine -> DrawingMode.StraightLineEraser
                else -> DrawingMode.Eraser
            }
            it.copy(drawingMode = newMode, isEyeDropperMode = false) 
        } 
    }
    fun selectLayer(id: Long) {
        finalizeSelection()
        _uiState.update { it.copy(activeLayerId = id) }
        viewModelScope.launch { saveProjectBrushSettings() }
    }
    fun setCursorSensitivity(s: Float) = updateBrushSetting { it.copy(cursorSensitivity = s.coerceIn(0.1f, 1.0f)) }

    fun addLayer(name: String) {
        saveHistoryState(emptyMap()) // adding a layer doesn't mutate any existing bitmap
        viewModelScope.launch {
            val zIndex = (_uiState.value.layers.maxOfOrNull { it.zIndex } ?: 0) + 1
            val newLayerId = repository.insertLayer(LayerEntity(projectId = projectId, name = name, zIndex = zIndex))
            // A freshly created layer is what the user wants to draw on next
            _uiState.update { it.copy(activeLayerId = newLayerId) }
            updateProjectTimestamp()
        }
    }

    fun deleteLayer(layer: LayerEntity) {
        if (_uiState.value.layers.size <= 1) return
        // The deleted layer's bitmap is kept by reference (never mutated) so undo can re-add it
        saveHistoryState(mapOf(layer.id to SnapshotSpec.FullByRef))
        viewModelScope.launch {
            repository.deleteLayer(layer)
            layerBitmaps.remove(layer.id)
            updateProjectTimestamp()
        }
    }

    fun toggleLayerVisibility(layer: LayerEntity) {
        saveHistoryState(emptyMap()) // only flips a metadata flag, no bitmap pixels change
        viewModelScope.launch {
            repository.updateLayer(layer.copy(isVisible = !layer.isVisible))
            updateProjectTimestamp()
        }
    }

    fun renameLayer(layer: LayerEntity, newName: String) {
        if (newName.isBlank()) return
        saveHistoryState(emptyMap()) // renaming is metadata-only
        viewModelScope.launch {
            repository.updateLayer(layer.copy(name = newName))
            updateProjectTimestamp()
        }
    }

    /** Live opacity update (state only); persisted on slider release via [persistLayerOpacity]. */
    fun setLayerOpacity(layer: LayerEntity, opacity: Float) {
        _uiState.update { state ->
            val updatedLayers = state.layers.map {
                if (it.id == layer.id) it.copy(opacity = opacity) else it
            }
            state.copy(layers = updatedLayers)
        }
    }

    fun persistLayerOpacity(layerId: Long) {
        val layer = _uiState.value.layers.find { it.id == layerId } ?: return
        viewModelScope.launch {
            repository.updateLayer(layer)
            updateProjectTimestamp()
        }
    }

    fun duplicateLayer(layer: LayerEntity) {
        saveHistoryState(emptyMap()) // creates a new bitmap, doesn't mutate an existing one
        viewModelScope.launch {
            val zIndex = (_uiState.value.layers.maxOfOrNull { it.zIndex } ?: 0) + 1
            val newLayerId = repository.insertLayer(LayerEntity(projectId = projectId, name = "${layer.name} Copy", zIndex = zIndex, opacity = layer.opacity))
            val originalBitmap = layerBitmaps[layer.id]
            if (originalBitmap != null) {
                val newBitmap = originalBitmap.copy(originalBitmap.config ?: Bitmap.Config.ARGB_8888, true)
                layerBitmaps[newLayerId] = newBitmap
                saveLayerToFile(newLayerId)
            }
            updateProjectTimestamp()
        }
    }

    fun mergeDown(layer: LayerEntity) {
        val layers = _uiState.value.layers
        val index = layers.indexOfFirst { it.id == layer.id }
        if (index <= 0) return
        val targetLayer = layers[index - 1]

        // Target is drawn into in place (full copy); source is removed untouched (by ref)
        saveHistoryState(mapOf(targetLayer.id to SnapshotSpec.FullMutated, layer.id to SnapshotSpec.FullByRef))
        viewModelScope.launch {
            val sourceBitmap = layerBitmaps[layer.id] ?: return@launch
            val targetBitmap = layerBitmaps[targetLayer.id] ?: return@launch
            val canvas = Canvas(targetBitmap)
            val paint = Paint().apply { alpha = (layer.opacity * 255).toInt() }
            canvas.drawBitmap(sourceBitmap, 0f, 0f, paint)
            repository.deleteLayer(layer)
            layerBitmaps.remove(layer.id)
            saveLayerToFile(targetLayer.id)
            updateProjectTimestamp()
        }
    }

    fun reorderLayers(fromIndex: Int, toIndex: Int) {
        val layers = _uiState.value.layers.toMutableList()
        if (fromIndex !in layers.indices || toIndex !in layers.indices) return

        saveHistoryState(emptyMap()) // reordering only changes z-index metadata
        val item = layers.removeAt(fromIndex)
        layers.add(toIndex, item)
        viewModelScope.launch {
            layers.forEachIndexed { index, layer -> repository.updateLayerZIndex(layer.id, index) }
            updateProjectTimestamp()
        }
    }

    fun clearLayer(id: Long) {
        saveHistoryState(mapOf(id to SnapshotSpec.FullMutated)) // erased in place right below
        layerBitmaps[id]?.eraseColor(android.graphics.Color.TRANSPARENT)
        _uiState.update { it.copy(renderVersion = it.renderVersion + 1) }
        saveLayerToFile(id)
        updateProjectTimestamp()
    }

    fun manualSave() { generateThumbnail(_uiState.value) }
    
    private class DecodedImage(val bitmap: Bitmap?, val width: Float, val height: Float, val aspectRatio: Float)

    /** Shared decode step for [setReferenceImage] and [loadSavedReferenceImage]. Throws on failure - callers decide how to degrade. */
    private fun decodeReferenceImage(resolverContext: android.content.Context, uri: String): DecodedImage {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = false }
        val stream = resolverContext.contentResolver.openInputStream(android.net.Uri.parse(uri))
        val bitmap = BitmapFactory.decodeStream(stream, null, options)
        stream?.close()
        val width = options.outWidth.toFloat()
        val height = options.outHeight.toFloat()
        return DecodedImage(bitmap, width, height, if (height > 0) width / height else 1f)
    }

    fun setReferenceImage(context: android.content.Context, uri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val decoded = decodeReferenceImage(context, uri)
                val state = _uiState.value

                val scale = if (decoded.width > 0) (state.canvasWidth * 0.6f) / decoded.width else 1f
                val offsetX = (state.canvasWidth - decoded.width * scale) / 2f
                val offsetY = (state.canvasHeight - decoded.height * scale) / 2f

                _uiState.update { it.copy(
                    referenceImage = ReferenceImage(
                        uri = uri,
                        offset = Offset(offsetX, offsetY),
                        scale = 1.0f,
                        aspectRatio = decoded.aspectRatio,
                        bitmap = decoded.bitmap
                    )
                ) }
                saveProjectBrushSettings()
            } catch (e: Exception) {
                android.util.Log.e("DrawingViewModel", "Failed to load reference image", e)
                _uiState.update { it.copy(referenceImage = ReferenceImage(uri = uri, offset = Offset(100f, 100f), bitmap = null)) }
            }
        }
    }

    private fun loadSavedReferenceImage(uri: String, offset: Offset, scale: Float, rotation: Float) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val decoded = decodeReferenceImage(context, uri)
                _uiState.update { it.copy(
                    referenceImage = ReferenceImage(
                        uri = uri,
                        offset = offset,
                        scale = scale,
                        rotation = rotation,
                        aspectRatio = decoded.aspectRatio,
                        bitmap = decoded.bitmap
                    )
                ) }
            } catch (e: Exception) {
                android.util.Log.e("DrawingViewModel", "Failed to reload saved reference image", e)
            }
        }
    }

    fun removeReferenceImage() {
        _uiState.update { it.copy(referenceImage = null) }
        viewModelScope.launch { saveProjectBrushSettings() }
    }

    fun updateReferenceImage(pan: Offset, zoom: Float, rotation: Float) {
        _uiState.update { state ->
            val ref = state.referenceImage ?: return@update state
            state.copy(referenceImage = ref.copy(
                offset = ref.offset + pan, 
                scale = (ref.scale * zoom).coerceIn(0.1f, 10f), 
                rotation = ref.rotation + rotation
            ))
        }
        // Save debounced or on specific interval might be better, but let's do it simple for now
        viewModelScope.launch { saveProjectBrushSettings() }
    }

    fun exportProject(context: android.content.Context, format: String) {
        viewModelScope.launch(Dispatchers.Default) {
            val state = _uiState.value
            val width = state.canvasWidth
            val height = state.canvasHeight
            if (width <= 0 || height <= 0) return@launch

            val exportBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(exportBitmap)
            canvas.drawColor(android.graphics.Color.WHITE)

            state.layers.filter { it.isVisible }.forEach { layer ->
                layerBitmaps[layer.id]?.let {
                    val paint = Paint().apply { alpha = (layer.opacity * 255).toInt() }
                    canvas.drawBitmap(it, 0f, 0f, paint)
                }
            }

            val fileName = "Export_${state.projectName}_${System.currentTimeMillis()}.$format"
            val resolver = context.contentResolver
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/$format")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES)
                }
            }

            try {
                val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let {
                    resolver.openOutputStream(it).use { out ->
                        if (out != null) {
                            val compressFormat = if (format == "png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                            exportBitmap.compress(compressFormat, 100, out)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("DrawingViewModel", "Export failed", e)
            }
        }
    }

    fun saveCurrentAsCustomBrush(name: String) {
        viewModelScope.launch {
            val id = customBrushManager.saveAsNew(name, _uiState.value) ?: return@launch
            _uiState.update { it.copy(selectedCustomBrushId = id.toString()) }
        }
    }

    fun updateSelectedBrush() {
        val state = _uiState.value
        val brushId = state.selectedCustomBrushId?.toLongOrNull() ?: return
        val currentBrush = state.customBrushes.find { it.id == state.selectedCustomBrushId } ?: return

        viewModelScope.launch { customBrushManager.updateExisting(brushId, currentBrush.name, state) }
    }

    fun renameCustomBrush(brush: BrushConfig, newName: String) {
        viewModelScope.launch { customBrushManager.rename(brush, newName, _uiState.value.customBrushes) }
    }

    fun deleteCustomBrush(brush: BrushConfig) {
        viewModelScope.launch { customBrushManager.delete(brush) }
    }

    fun selectCustomBrush(brush: BrushConfig) {
        _uiState.update { 
            it.copy(
                selectedCustomBrushId = brush.id,
                selectedWidth = brush.size,
                brushSoftness = brush.softness,
                brushOpacity = brush.opacity,
                brushFlow = brush.flow,
                brushSpacing = brush.spacing,
                brushSmoothing = brush.smoothing,
                brushRotation = brush.rotation,
                brushRotationDynamics = brush.rotationDynamics,
                brushRotationJitter = brush.rotationJitter,
                sizeJitter = brush.sizeJitter,
                brushTipUri = brush.tipUri,
                brushTextureUri = brush.textureUri,
                velocityEnabled = brush.velocityEnabled,
                velocitySizeAmount = brush.velocitySize,
                velocityFlowAmount = brush.velocityFlow,
                velocityScatterAmount = brush.velocityScatter,
                isEyeDropperMode = false
            )
        }

        // Reload Bitmaps from the new URIs
        setBrushTip(context, brush.tipUri)
        setBrushTexture(context, brush.textureUri)

        viewModelScope.launch {
            preferenceManager.setRotationDynamics(brush.rotationDynamics)
            preferenceManager.setRotationJitter(brush.rotationJitter)
            preferenceManager.setVelocityEnabled(brush.velocityEnabled)
            preferenceManager.setVelocitySize(brush.velocitySize)
            preferenceManager.setVelocityFlow(brush.velocityFlow)
            preferenceManager.setVelocityScatter(brush.velocityScatter)
            saveProjectBrushSettings()
        }
    }

    /** Shared decode step for [setBrushTip] and [setBrushTexture]: both just land in a different pair of state fields. */
    private fun loadBrushBitmap(context: android.content.Context, uri: String?, label: String, onLoaded: (uri: String?, bitmap: Bitmap?) -> Unit) {
        if (uri == null) {
            onLoaded(null, null)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(android.net.Uri.parse(uri))
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                if (bitmap != null) {
                    onLoaded(uri, bitmap)
                }
            } catch (e: Exception) {
                android.util.Log.e("DrawingViewModel", "Failed to load $label", e)
            }
        }
    }

    fun setBrushTip(context: android.content.Context, uri: String?) =
        loadBrushBitmap(context, uri, "brush tip") { u, b -> _uiState.update { it.copy(brushTipUri = u, brushTipBitmap = b) } }

    fun setBrushTexture(context: android.content.Context, uri: String?) =
        loadBrushBitmap(context, uri, "brush texture") { u, b ->
            val mask = b?.let { buildLuminanceMask(it) }
            _uiState.update { it.copy(brushTextureUri = u, brushTextureBitmap = b, brushTextureMask = mask) }
        }

    override fun onCleared() {
        historyManager.clearAll()
    }
}
