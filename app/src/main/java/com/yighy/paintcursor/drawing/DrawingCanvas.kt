package com.yighy.paintcursor.drawing

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
fun DrawingCanvas(
    viewModel: DrawingViewModel,
    modifier: Modifier = Modifier
) {
    val canvasScale by remember(viewModel) { viewModel.uiState.map { it.canvasScale }.distinctUntilChanged() }.collectAsState(1f)
    val canvasOffset by remember(viewModel) { viewModel.uiState.map { it.canvasOffset }.distinctUntilChanged() }.collectAsState(Offset.Zero)
    val canvasRotation by remember(viewModel) { viewModel.uiState.map { it.canvasRotation }.distinctUntilChanged() }.collectAsState(0f)
    val canvasWidth by remember(viewModel) { viewModel.uiState.map { it.canvasWidth }.distinctUntilChanged() }.collectAsState(1080)
    val canvasHeight by remember(viewModel) { viewModel.uiState.map { it.canvasHeight }.distinctUntilChanged() }.collectAsState(1920)
    val fitToScreenTrigger by remember(viewModel) { viewModel.uiState.map { it.fitToScreenTrigger }.distinctUntilChanged() }.collectAsState(0)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds() 
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    var totalDrag = Offset.Zero
                    var isMultiTouch = false
                    
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1) {
                            isMultiTouch = true
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val rotation = event.calculateRotation()
                            viewModel.updateTransform(zoom, pan, rotation)
                            event.changes.forEach { it.consume() }
                        } else if (event.changes.size == 1) {
                            val change = event.changes[0]
                            if (change.pressed) {
                                val dragAmount = change.position - change.previousPosition
                                totalDrag += dragAmount
                                if (!isMultiTouch && dragAmount != Offset.Zero) {
                                    val rotatedDelta = dragAmount.rotate(-viewModel.uiState.value.canvasRotation)
                                    viewModel.moveCursor(rotatedDelta / viewModel.uiState.value.canvasScale)
                                    change.consume()
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    
                    if (!isMultiTouch && totalDrag.getDistance() < 10f) {
                        viewModel.togglePen()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val screenWidth = constraints.maxWidth.toFloat()
        val screenHeight = constraints.maxHeight.toFloat()
        val density = LocalDensity.current
        
        if (canvasWidth > 0 && canvasHeight > 0) {
            LaunchedEffect(canvasWidth, canvasHeight, screenWidth, screenHeight, fitToScreenTrigger) {
                viewModel.setCanvasSize(IntSize(canvasWidth, canvasHeight))
                if ((canvasScale == 1.0f && canvasOffset == Offset.Zero) || fitToScreenTrigger > 0) {
                    // Animate only when the user asked for it; the initial fit stays instant
                    viewModel.fitToScreen(screenWidth, screenHeight, animate = fitToScreenTrigger > 0)
                }
            }

            Box(
                modifier = Modifier
                    .wrapContentSize(unbounded = true)
                    .requiredSize((canvasWidth / density.density).dp, (canvasHeight / density.density).dp)
                    .graphicsLayer {
                        scaleX = canvasScale
                        scaleY = canvasScale
                        translationX = canvasOffset.x
                        translationY = canvasOffset.y
                        rotationZ = canvasRotation
                    }
                    .background(Color.White)
            ) {
                // Layer Content - Only recomposes when renderVersion or layers change
                CanvasLayer(viewModel)
                
                // Tool Preview Overlay (Straight Line, etc)
                ToolPreviewLayer(viewModel)

                // Selection outline & floating selection preview
                SelectionLayer(viewModel)

                // Cursor Overlay - Recomposes on every move
                CursorLayer(viewModel)
            }

            // Edge arrow pointing at the cursor when it's outside the viewport
            OffscreenCursorIndicator(viewModel)
        }
    }
}

@Composable
fun OffscreenCursorIndicator(viewModel: DrawingViewModel) {
    val enabled by remember(viewModel) { viewModel.uiState.map { it.showOffscreenCursorArrow }.distinctUntilChanged() }.collectAsState(false)
    if (!enabled) return

    val cursorPosition by remember(viewModel) { viewModel.uiState.map { it.cursorPosition }.distinctUntilChanged() }.collectAsState(Offset.Zero)
    val canvasScale by remember(viewModel) { viewModel.uiState.map { it.canvasScale }.distinctUntilChanged() }.collectAsState(1f)
    val canvasOffset by remember(viewModel) { viewModel.uiState.map { it.canvasOffset }.distinctUntilChanged() }.collectAsState(Offset.Zero)
    val canvasRotation by remember(viewModel) { viewModel.uiState.map { it.canvasRotation }.distinctUntilChanged() }.collectAsState(0f)
    val canvasWidth by remember(viewModel) { viewModel.uiState.map { it.canvasWidth }.distinctUntilChanged() }.collectAsState(1080)
    val canvasHeight by remember(viewModel) { viewModel.uiState.map { it.canvasHeight }.distinctUntilChanged() }.collectAsState(1920)

    Canvas(modifier = Modifier.fillMaxSize()) {
        // Screen position of the cursor: the canvas box is centered in the viewport and
        // graphicsLayer scales/rotates around its center, then translates by canvasOffset
        val rel = cursorPosition - Offset(canvasWidth / 2f, canvasHeight / 2f)
        val screenPos = Offset(size.width / 2f, size.height / 2f) + canvasOffset + rel.rotate(canvasRotation) * canvasScale

        if (screenPos.x in 0f..size.width && screenPos.y in 0f..size.height) return@Canvas

        // Anchor the arrow on the screen edge, aimed at the cursor
        val margin = 22.dp.toPx()
        val anchor = Offset(
            screenPos.x.coerceIn(margin, size.width - margin),
            screenPos.y.coerceIn(margin, size.height - margin)
        )
        val angleDeg = Math.toDegrees(
            atan2((screenPos.y - anchor.y).toDouble(), (screenPos.x - anchor.x).toDouble())
        ).toFloat()

        rotate(degrees = angleDeg, pivot = anchor) {
            // Dark backdrop disc so the arrow reads on any canvas content
            drawCircle(
                color = Color.Black.copy(alpha = 0.55f),
                radius = 13.dp.toPx(),
                center = anchor
            )
            val tip = 8.dp.toPx()
            val back = 4.5.dp.toPx()
            val half = 6.dp.toPx()
            val arrow = Path().apply {
                moveTo(anchor.x + tip, anchor.y)
                lineTo(anchor.x - back, anchor.y - half)
                lineTo(anchor.x - back, anchor.y + half)
                close()
            }
            drawPath(arrow, Color.White)
        }
    }
}

@Composable
fun CanvasLayer(viewModel: DrawingViewModel) {
    // Each field is collected separately with distinctUntilChanged so this composable only
    // recomposes when something it actually draws changes (not on every uiState update, e.g.
    // cursor-only moves while the pen is up).
    val renderVersion by remember(viewModel) { viewModel.uiState.map { it.renderVersion }.distinctUntilChanged() }.collectAsState(0)
    val layers by remember(viewModel) { viewModel.uiState.map { it.layers }.distinctUntilChanged() }.collectAsState(emptyList())
    val layerBitmaps by remember(viewModel) { viewModel.uiState.map { it.layerBitmaps }.distinctUntilChanged() }.collectAsState(emptyMap())
    val strokeBitmap by remember(viewModel) { viewModel.uiState.map { it.strokeBitmap }.distinctUntilChanged() }.collectAsState(null)
    val activeLayerId by remember(viewModel) { viewModel.uiState.map { it.activeLayerId }.distinctUntilChanged() }.collectAsState(-1L)
    val isPenDown by remember(viewModel) { viewModel.uiState.map { it.isPenDown }.distinctUntilChanged() }.collectAsState(false)
    val brushOpacity by remember(viewModel) { viewModel.uiState.map { it.brushOpacity }.distinctUntilChanged() }.collectAsState(1f)
    val drawingMode by remember(viewModel) { viewModel.uiState.map { it.drawingMode }.distinctUntilChanged() }.collectAsState(DrawingMode.Freehand)
    val brushTextureMask by remember(viewModel) { viewModel.uiState.map { it.brushTextureMask }.distinctUntilChanged() }.collectAsState(null)
    val selectionMask by remember(viewModel) { viewModel.uiState.map { it.selectionMask }.distinctUntilChanged() }.collectAsState(null)

    Canvas(modifier = Modifier.fillMaxSize()) {
        val _v = renderVersion
        layers.filter { it.isVisible }.forEach { layer ->
            val isActiveLayer = layer.id == activeLayerId
            val isEraser = drawingMode is DrawingMode.Eraser || drawingMode is DrawingMode.StraightLineEraser
            
            if (isActiveLayer && isPenDown && isEraser) {
                // Eraser mode: Blend the stroke with DST_OUT on this layer
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    val rect = android.graphics.RectF(0f, 0f, size.width, size.height)
                    val saveCount = native.saveLayer(rect, null)
                    
                    // Draw layer content
                    layerBitmaps[layer.id]?.let { bitmap ->
                        val p = android.graphics.Paint().apply { alpha = (layer.opacity * 255).toInt() }
                        native.drawBitmap(bitmap, null, rect, p)
                    }
                    
                    // Erase with stroke (handles both freehand and straight line)
                    strokeBitmap?.let {
                        val p = android.graphics.Paint().apply {
                            alpha = (brushOpacity * 255).toInt()
                            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
                        }
                        native.drawBitmap(it, null, rect, p)
                    }
                    
                    native.restoreToCount(saveCount)
                }
            } else {
                // Normal mode
                val isSelection = drawingMode.isSelectionTool()
                val sb = strokeBitmap
                val liveStroke = isActiveLayer && isPenDown && !isEraser && !isSelection && sb != null

                if (liveStroke && sb != null &&
                    layer.opacity >= 1f && brushOpacity >= 1f &&
                    brushTextureMask == null && selectionMask == null
                ) {
                    // Fast path for the common case (everything fully opaque, no masks):
                    // direct draws are pixel-identical to the saveLayer composition below
                    // and skip two full-screen offscreen buffers per frame
                    layerBitmaps[layer.id]?.let { drawImage(it.asImageBitmap()) }
                    drawImage(sb.asImageBitmap())
                } else if (liveStroke && sb != null) {
                    // Live stroke: compose the stroke INTO the layer content first, then
                    // apply the layer opacity to the whole. This is the exact same math
                    // as commitStrokeToLayer + normal display, so nothing shifts at
                    // pen-up (overlaps, semi-transparent layers, masks included).
                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        val rect = android.graphics.RectF(0f, 0f, size.width, size.height)
                        val outer = native.saveLayer(rect, android.graphics.Paint().apply {
                            alpha = (layer.opacity * 255).toInt()
                        })
                        layerBitmaps[layer.id]?.let { native.drawBitmap(it, null, rect, null) }

                        val inner = native.saveLayer(rect, android.graphics.Paint().apply {
                            alpha = (brushOpacity * 255).toInt()
                        })
                        native.drawBitmap(sb, null, rect, null)
                        brushTextureMask?.let { mask ->
                            native.drawRect(rect, android.graphics.Paint().apply {
                                shader = android.graphics.BitmapShader(mask, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
                                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                            })
                        }
                        selectionMask?.let { mask ->
                            native.drawBitmap(mask, null, rect, android.graphics.Paint().apply {
                                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                            })
                        }
                        native.restoreToCount(inner)
                        native.restoreToCount(outer)
                    }
                } else {
                    layerBitmaps[layer.id]?.let { bitmap ->
                        drawImage(image = bitmap.asImageBitmap(), alpha = layer.opacity)
                    }
                }
            }
        }
    }
}

@Composable
fun ToolPreviewLayer(viewModel: DrawingViewModel) {
    // Currently, straight line preview is handled via strokeBitmap in CanvasLayer 
    // for better accuracy (matching custom tips, textures, jitter, etc).
    // This layer can be used for other non-stroke overlays if needed.
}

@Composable
fun SelectionLayer(viewModel: DrawingViewModel) {
    val drawingMode by remember(viewModel) { viewModel.uiState.map { it.drawingMode }.distinctUntilChanged() }.collectAsState(DrawingMode.Freehand)
    val selectionPoints by remember(viewModel) { viewModel.uiState.map { it.selectionPoints }.distinctUntilChanged() }.collectAsState(emptyList())
    val isSelectionClosed by remember(viewModel) { viewModel.uiState.map { it.isSelectionClosed }.distinctUntilChanged() }.collectAsState(false)
    val floatingBitmap by remember(viewModel) { viewModel.uiState.map { it.floatingBitmap }.distinctUntilChanged() }.collectAsState(null)
    val floatingOrigin by remember(viewModel) { viewModel.uiState.map { it.floatingOrigin }.distinctUntilChanged() }.collectAsState(Offset.Zero)
    val floatingOffset by remember(viewModel) { viewModel.uiState.map { it.floatingOffset }.distinctUntilChanged() }.collectAsState(Offset.Zero)
    val floatingScale by remember(viewModel) { viewModel.uiState.map { it.floatingScale }.distinctUntilChanged() }.collectAsState(1f)
    val floatingRotation by remember(viewModel) { viewModel.uiState.map { it.floatingRotation }.distinctUntilChanged() }.collectAsState(0f)
    val canvasScale by remember(viewModel) { viewModel.uiState.map { it.canvasScale }.distinctUntilChanged() }.collectAsState(1f)
    val selectionMask by remember(viewModel) { viewModel.uiState.map { it.selectionMask }.distinctUntilChanged() }.collectAsState(null)

    if (selectionPoints.isEmpty() && floatingBitmap == null && selectionMask == null) return

    // Marching ants: phase loops over one full dash period (8 + 8)
    val antsPhase by rememberInfiniteTransition(label = "ants").animateFloat(
        initialValue = 0f,
        targetValue = 16f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing)),
        label = "antsPhase"
    )
    val floatingPaint = remember { Paint().apply { isAntiAlias = true; isFilterBitmap = true } }

    // Mask-based selections (wand/color/inverted) have no polygon: extract a border band
    // from the mask (mask minus its erosion) to draw a real outline instead of a heavy tint
    val maskEdge = remember(selectionMask, selectionPoints) {
        val mask = selectionMask
        if (mask == null || selectionPoints.isNotEmpty()) null else buildMaskEdge(mask)
    }

    // Dark veil over everything OUTSIDE the selection: shows at a glance which area is
    // protected (drawing tools are clipped to the selection)
    val outsideVeil = remember(selectionMask) {
        selectionMask?.let { buildOutsideVeil(it) }
    }

    // Soft fade-in each time a selection is lifted or an image is imported
    val floatingAppear = remember { Animatable(1f) }
    LaunchedEffect(floatingBitmap) {
        if (floatingBitmap != null) {
            floatingAppear.snapTo(0f)
            floatingAppear.animateTo(1f, tween(180))
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val invScale = 1f / canvasScale
        val sw = 1.5f * invScale
        val fb = floatingBitmap

        // Closed selection: dim everything outside it (the protected area), and for
        // mask-only selections draw a crisp outline from the mask border band.
        // Both are hidden while the pixels are floating.
        if (selectionMask != null && fb == null) {
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                outsideVeil?.let { veil ->
                    native.drawBitmap(veil, 0f, 0f, android.graphics.Paint().apply { alpha = 96 })
                }
                maskEdge?.let { edge ->
                    // Dark under-stroke then white on top: readable on any background
                    native.drawBitmap(edge, 1.5f * invScale, 1.5f * invScale, android.graphics.Paint().apply {
                        alpha = 170
                        colorFilter = android.graphics.PorterDuffColorFilter(android.graphics.Color.BLACK, PorterDuff.Mode.SRC_IN)
                    })
                    native.drawBitmap(edge, 0f, 0f, android.graphics.Paint().apply {
                        colorFilter = android.graphics.PorterDuffColorFilter(android.graphics.Color.WHITE, PorterDuff.Mode.SRC_IN)
                    })
                }
            }
        }

        // Floating pixels being moved/transformed
        if (fb != null) {
            floatingPaint.alpha = (floatingAppear.value * 255).toInt()
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                native.save()
                native.translate(floatingOffset.x + fb.width / 2f, floatingOffset.y + fb.height / 2f)
                native.rotate(floatingRotation)
                native.scale(floatingScale, floatingScale)
                native.drawBitmap(fb, -fb.width / 2f, -fb.height / 2f, floatingPaint)
                native.restore()
            }
        }

        // Selection outline (follows the floating transform once lifted)
        if (selectionPoints.size >= 2) {
            val displayPoints = if (fb != null) {
                val origCenter = floatingOrigin + Offset(fb.width / 2f, fb.height / 2f)
                val curCenter = floatingOffset + Offset(fb.width / 2f, fb.height / 2f)
                selectionPoints.map { p -> curCenter + ((p - origCenter) * floatingScale).rotate(floatingRotation) }
            } else {
                selectionPoints
            }

            val path = Path().apply {
                moveTo(displayPoints[0].x, displayPoints[0].y)
                for (i in 1 until displayPoints.size) lineTo(displayPoints[i].x, displayPoints[i].y)
                if (isSelectionClosed || fb != null || drawingMode is DrawingMode.SelectRect) close()
            }
            val dash = floatArrayOf(8f * invScale, 8f * invScale)
            drawPath(path, Color.White, style = Stroke(sw, pathEffect = PathEffect.dashPathEffect(dash, antsPhase * invScale)))
            drawPath(path, Color.Black, style = Stroke(sw, pathEffect = PathEffect.dashPathEffect(dash, (antsPhase + 8f) * invScale)))
        }
    }
}

@Composable
fun CursorLayer(viewModel: DrawingViewModel) {
    val cursorPosition by remember(viewModel) { viewModel.uiState.map { it.cursorPosition }.distinctUntilChanged() }.collectAsState(Offset.Zero)
    val brushPosition by remember(viewModel) { viewModel.uiState.map { it.brushPosition }.distinctUntilChanged() }.collectAsState(Offset.Zero)
    val canvasScale by remember(viewModel) { viewModel.uiState.map { it.canvasScale }.distinctUntilChanged() }.collectAsState(1f)
    val isPenDown by remember(viewModel) { viewModel.uiState.map { it.isPenDown }.distinctUntilChanged() }.collectAsState(false)
    val selectedWidth by remember(viewModel) { viewModel.uiState.map { it.selectedWidth }.distinctUntilChanged() }.collectAsState(20f)
    val cursorThickness by remember(viewModel) { viewModel.uiState.map { it.cursorThickness }.distinctUntilChanged() }.collectAsState(1.0f)
    val isLazyModeActive by remember(viewModel) { viewModel.uiState.map { it.isLazyModeActive }.distinctUntilChanged() }.collectAsState(false)
    val lazyRadius by remember(viewModel) { viewModel.uiState.map { it.lazyRadius }.distinctUntilChanged() }.collectAsState(50f)

    Canvas(modifier = Modifier.fillMaxSize()) {
        val invScale = 1f / canvasScale
        val cs = 8.dp.toPx() * invScale
        val sw = cursorThickness * invScale
        val cursorDrawColor = if (isPenDown) Color.Red else Color.White 
        val brushDrawColor = if (isPenDown) Color.Cyan else Color.White

        if (isLazyModeActive) {
            // Draw Lazy Rope
            drawLine(
                color = Color.White,
                start = brushPosition,
                end = cursorPosition,
                strokeWidth = sw,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f * invScale, 10f * invScale)),
                blendMode = BlendMode.Difference
            )
            
            // Draw Lazy Radius Circle
            drawCircle(
                color = Color.White,
                radius = lazyRadius,
                center = brushPosition,
                style = Stroke(
                    width = sw, 
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f * invScale, 5f * invScale))
                ),
                blendMode = BlendMode.Difference,
                alpha = 0.5f
            )

            // Brush Position Crosshair (The point where drawing actually happens)
            drawLine(brushDrawColor, Offset(brushPosition.x - cs * 0.5f, brushPosition.y), Offset(brushPosition.x + cs * 0.5f, brushPosition.y), sw, blendMode = BlendMode.Difference)
            drawLine(brushDrawColor, Offset(brushPosition.x, brushPosition.y - cs * 0.5f), Offset(brushPosition.x, brushPosition.y + cs * 0.5f), sw, blendMode = BlendMode.Difference)
        }

        // Main Cursor Crosshair (The "Target")
        drawLine(cursorDrawColor, Offset(cursorPosition.x - cs, cursorPosition.y), Offset(cursorPosition.x + cs, cursorPosition.y), sw, blendMode = BlendMode.Difference)
        drawLine(cursorDrawColor, Offset(cursorPosition.x, cursorPosition.y - cs), Offset(cursorPosition.x, cursorPosition.y + cs), sw, blendMode = BlendMode.Difference)
        
        // Brush Size Preview (Centered on brushPosition if lazy, otherwise cursorPosition)
        val previewCenter = if (isLazyModeActive) brushPosition else cursorPosition
        
        drawCircle(
            color = if (isLazyModeActive) brushDrawColor else cursorDrawColor,
            radius = (selectedWidth / 2f),
            center = previewCenter,
            style = Stroke(width = sw),
            blendMode = BlendMode.Difference
        )
    }
}

/** Dark layer covering everything the selection mask does NOT cover. */
private fun buildOutsideVeil(mask: Bitmap): Bitmap {
    val veil = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(veil)
    canvas.drawColor(android.graphics.Color.BLACK)
    canvas.drawBitmap(mask, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
    return veil
}

/**
 * Border band of a selection mask: the mask minus its morphological erosion
 * (intersection of the mask shifted in the four cardinal directions).
 */
private fun buildMaskEdge(mask: Bitmap): Bitmap {
    val e = 3f
    val din = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }

    val eroded = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
    val erodedCanvas = android.graphics.Canvas(eroded)
    erodedCanvas.drawBitmap(mask, 0f, 0f, null)
    erodedCanvas.drawBitmap(mask, e, 0f, din)
    erodedCanvas.drawBitmap(mask, -e, 0f, din)
    erodedCanvas.drawBitmap(mask, 0f, e, din)
    erodedCanvas.drawBitmap(mask, 0f, -e, din)

    val edge = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
    val edgeCanvas = android.graphics.Canvas(edge)
    edgeCanvas.drawBitmap(mask, 0f, 0f, null)
    edgeCanvas.drawBitmap(eroded, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
    eroded.recycle()
    return edge
}

fun Offset.rotate(degrees: Float): Offset {
    val rad = Math.toRadians(degrees.toDouble())
    val cos = cos(rad).toFloat()
    val sin = sin(rad).toFloat()
    return Offset(x * cos - y * sin, x * sin + y * cos)
}
