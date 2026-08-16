package com.yighy.paintcursor.drawing

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Everything that turns cursor movement into pixels: spacing, the pre-rendered stamp, the
 * jitters, the velocity dynamics and the Bézier smoothing between pointer samples.
 *
 * The engine paints into its own full-canvas *stroke bitmap* rather than straight into a layer,
 * so opacity, texture and the selection clip can be applied once to the finished stroke. It
 * never reads or writes [DrawingState]: the caller passes the state it should paint with, which
 * is what lets the Brush Studio render a preview from an arbitrary brush without disturbing an
 * in-progress stroke.
 */
class StrokeEngine {

    /** Full-canvas scratch buffer the current stroke accumulates into. */
    var strokeBitmap: Bitmap? = null
        private set
    private var strokeCanvas: Canvas? = null

    // Object pooling and pre-rendered stamp
    private val sharedPaint = Paint().apply { isAntiAlias = true }
    private val stampRect = RectF()
    private var cachedStampBitmap: Bitmap? = null
    private var lastStampKey: String = ""

    /** How far the cached stamp must be scaled when drawn; above 1 only past the raster cap. */
    private var cachedStampScale = 1f

    private var distanceSinceLastStamp = 0f
    private val random = Random()

    // Bézier smoothing state: the previous sample and the previous midpoint between samples
    private var lastPoint = Offset.Zero
    private var lastMidPoint = Offset.Zero

    private var smoothedVelocity = Offset.Zero

    // Velocity dynamics: smoothed cursor speed -> normalized 0..1.
    // velocityDynamicsActive gates effects whose "inverted" direction acts at LOW speed
    // (negative scatter), which must not fire outside a velocity-enabled freehand stroke.
    private var smoothedSpeed = 0f
    private var currentVelocityNorm = 0f
    private var velocityDynamicsActive = false

    // Bounding box (canvas coords) of every stamp drawn into strokeBitmap since its last
    // erase. Bounds the history snapshot at commit so undo only stores the touched region.
    private val strokeDirtyRect = RectF()
    private var strokeDirtyValid = false

    /** Distance painted since pen-down. The FAB reads it to tell drawing from dragging itself. */
    var strokeDistance = 0f
        private set

    // ============================ Stroke buffer ============================

    /**
     * Points the engine at a canvas of [width] x [height], reallocating the stroke buffer if the
     * size changed. Returns true when a new bitmap was created, so the caller can publish the
     * new reference to the UI.
     */
    fun resizeTarget(width: Int, height: Int): Boolean {
        val current = strokeBitmap
        if (current != null && current.width == width && current.height == height) return false
        current?.recycle()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        strokeBitmap = bitmap
        strokeCanvas = Canvas(bitmap)
        return true
    }

    /** Wipes the stroke buffer and forgets the dirty region. */
    fun clearTarget() {
        strokeBitmap?.eraseColor(android.graphics.Color.TRANSPARENT)
        strokeDirtyValid = false
    }

    /** Spacing counter primed so the very next sample lands a stamp immediately. */
    fun resetSpacing(state: DrawingState) {
        distanceSinceLastStamp = max(1f, state.effectiveWidth * state.brushSpacing)
    }

    /**
     * Starts a stroke at [pos]: clears the buffer and resets spacing, smoothing and velocity.
     * Call at pen-down, before the first [extendSmoothed] / [drawSegment].
     */
    fun beginStroke(pos: Offset, state: DrawingState) {
        clearTarget()
        resetSpacing(state)
        strokeDistance = 0f
        smoothedSpeed = 0f
        currentVelocityNorm = 0f
        velocityDynamicsActive = false
        lastPoint = pos
        lastMidPoint = pos
    }

    /** Call at pen-up: drops the carried-over smoothing so the next stroke starts clean. */
    fun endStroke() {
        smoothedVelocity = Offset.Zero
    }

    /**
     * Bounding box of everything stamped since the last clear, rounded out with a margin for
     * antialiasing bleed and clipped to [width] x [height]. Null when nothing was painted, or
     * when the stroke fell entirely outside the canvas.
     */
    fun dirtyRegion(width: Int, height: Int): Rect? {
        if (!strokeDirtyValid) return null
        val r = Rect()
        strokeDirtyRect.roundOut(r)
        r.inset(-2, -2)
        if (!r.intersect(0, 0, width, height)) return null
        return r
    }

    // ============================ Cursor movement ============================

    /**
     * Feeds one pointer delta in and returns the canvas-space movement to apply to the cursor.
     * Sensitivity and stroke smoothing only bite while the pen is down - a raised pen tracks the
     * finger one-to-one. Also advances the smoothed speed the velocity dynamics read.
     */
    fun smoothMovement(delta: Offset, state: DrawingState): Offset {
        val sensitivity = if (state.isPenDown) state.cursorSensitivity else 1.0f
        val targetVelocity = delta * sensitivity

        val smoothingFactor = if (state.isPenDown) {
            1.0f - (state.brushSmoothing * 0.92f)
        } else {
            1.0f
        }
        smoothedVelocity = smoothedVelocity * (1f - smoothingFactor) + targetVelocity * smoothingFactor

        // Velocity dynamics: only freehand strokes react to speed (shape tools stay uniform)
        velocityDynamicsActive = state.isPenDown && state.velocityEnabled &&
            (state.drawingMode is DrawingMode.Freehand || state.drawingMode is DrawingMode.Eraser)
        currentVelocityNorm = if (velocityDynamicsActive) {
            smoothedSpeed = smoothedSpeed * 0.6f + smoothedVelocity.getDistance() * 0.4f
            velocityNorm(smoothedSpeed)
        } else 0f

        return smoothedVelocity
    }

    fun addDistance(amount: Float) {
        strokeDistance += amount
    }

    /** Zeroes the painted-distance counter without touching the buffer, for gestures that don't paint. */
    fun resetDistance() {
        strokeDistance = 0f
    }

    /**
     * Maps smoothed cursor speed (px/event) to 0..1. The per-stamp size/flow/scatter
     * factors are derived from this in [drawSegment].
     */
    private fun velocityNorm(speed: Float): Float = (speed / 40f).coerceIn(0f, 1f)

    // ============================ Painting ============================

    /**
     * Freehand: extends the stroke to [pos], smoothing the corner at the previous sample into a
     * quadratic Bézier between the surrounding midpoints.
     */
    fun extendSmoothed(pos: Offset, state: DrawingState) {
        val midPoint = Offset((lastPoint.x + pos.x) / 2f, (lastPoint.y + pos.y) / 2f)
        drawBezier(lastMidPoint, lastPoint, midPoint, state)
        lastMidPoint = midPoint
        lastPoint = pos
    }

    /**
     * Repaints the buffer with a single straight segment. Used for the live preview of the
     * straight-line tools and for their commit, hence the fixed seed: the same line must not
     * re-jitter differently between the preview and the stroke that lands.
     */
    fun drawStraightLine(from: Offset, to: Offset, state: DrawingState) {
        clearTarget()
        resetSpacing(state)
        drawSegment(from, to, state, seed = 0L)
    }

    /**
     * Repaints the buffer with the live gradient preview: [state]'s colour at [from] fading to
     * transparent at [to]. Clipping and opacity are applied at commit like any other stroke.
     */
    fun drawGradient(from: Offset, to: Offset, state: DrawingState) {
        strokeBitmap?.eraseColor(android.graphics.Color.TRANSPARENT)
        val canvas = strokeCanvas ?: return
        val paint = Paint().apply {
            isAntiAlias = true
            shader = LinearGradient(
                from.x, from.y, to.x, to.y,
                state.selectedColor.toArgb(), android.graphics.Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, state.canvasWidth.toFloat(), state.canvasHeight.toFloat(), paint)
    }

    private fun drawBezier(p0: Offset, p1: Offset, p2: Offset, state: DrawingState) {
        // Approximate length of the quadratic Bézier curve
        val chord = (p2 - p0).getDistance()
        val num = (p0 - p1).getDistance() + (p1 - p2).getDistance()
        val approxLength = (chord + num) / 2f

        if (approxLength <= 0f) return

        val step = max(1f, state.effectiveWidth * state.brushSpacing)
        val numSteps = (approxLength / step).toInt().coerceAtLeast(1)

        var prevPoint = p0
        for (i in 1..numSteps) {
            val t = i.toFloat() / numSteps
            // Quadratic Bézier formula: (1-t)^2*p0 + 2(1-t)t*p1 + t^2*p2
            val x = (1 - t).pow(2) * p0.x + 2 * (1 - t) * t * p1.x + t.pow(2) * p2.x
            val y = (1 - t).pow(2) * p0.y + 2 * (1 - t) * t * p1.y + t.pow(2) * p2.y
            val currentPoint = Offset(x, y)

            drawSegment(prevPoint, currentPoint, state, seed = null)
            prevPoint = currentPoint
        }
    }

    private fun updateStampCache(state: DrawingState) {
        val isEraser = state.drawingMode is DrawingMode.Eraser || state.drawingMode is DrawingMode.StraightLineEraser
        val width = state.effectiveWidth
        val key = "${width}_${state.brushSoftness}_" +
                  "${state.selectedColor.toArgb()}_${state.brushTipUri}_$isEraser"

        if (key == lastStampKey && cachedStampBitmap != null) return

        lastStampKey = key
        // Calculate canvas size for the stamp - needs to account for potential blur padding
        // effectiveWidth is the diameter. We use 1.5x to be safe for BlurMaskFilter
        val padding = if (state.brushSoftness > 0f) width * 0.5f else 2f
        // Capped so a brush at the top of the multiplier can't ask for a bitmap the size of
        // a wall; anything past the cap is made up by drawing the stamp scaled - see StampRaster.
        val stampSize = width + padding
        val canvasSize = StampRaster.rasterSize(stampSize)
        cachedStampScale = StampRaster.drawScale(stampSize)

        val bitmap = Bitmap.createBitmap(canvasSize, canvasSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = canvasSize / 2f

        // Texture is NOT baked into the stamp: it's applied canvas-anchored over the whole
        // stroke at composition time (see applyTextureMask), so it reads as paper grain.
        val paint = Paint().apply {
            isAntiAlias = true
            // Eraser tip needs to be solid black for the alpha mask logic later
            color = if (isEraser) android.graphics.Color.BLACK else state.selectedColor.toArgb()
            alpha = 255
        }

        // In raster coordinates: below the cap the scale is 1 and this is plain width/2, above
        // it the stamp is drawn into the smaller bitmap and grown back at draw time.
        var drawRadius = (width / 2f) / cachedStampScale
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

    /**
     * Lays stamps along the segment [from]..[to] at the brush's spacing, applying the jitters and
     * velocity dynamics per stamp. Paints into the stroke buffer unless [targetCanvas] is given
     * (previews), in which case the dirty region is left alone.
     */
    fun drawSegment(
        from: Offset,
        to: Offset,
        state: DrawingState,
        seed: Long? = null,
        targetCanvas: Canvas? = null,
        randomSource: Random? = null
    ) {
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
        val baseFlow = state.brushFlow * velocityFlowFactor
        sharedPaint.alpha = (baseFlow * 255).toInt().coerceIn(0, 255)

        // Use SRC_OVER even for eraser here, we are building the "stroke mask"
        sharedPaint.xfermode = null

        val width = state.effectiveWidth
        val dist = (to - from).getDistance()
        val step = max(1f, width * state.brushSpacing)

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

        val remainingDist = distanceSinceLastStamp + dist
        if (remainingDist >= step) {
            var currentPosInSegment = step - distanceSinceLastStamp
            while (currentPosInSegment <= dist) {
                val t = if (dist == 0f) 1f else currentPosInSegment / dist
                val px = from.x + (to.x - from.x) * t
                val py = from.y + (to.y - from.y) * t

                // Was one-sided - it could only ever shrink the stamp, so raising the jitter
                // quietly thinned the brush, halving its average size at full setting. Now
                // symmetric, like the rotation and scatter jitters already were.
                val jitter = BrushJitter.sizeFactor(localRandom.nextFloat(), state.sizeJitter)

                // Flow jitter is symmetric around the set flow, so raising it varies the
                // density without darkening or lightening the stroke overall.
                val flowVariation = if (state.flowJitter > 0f) {
                    1f + (localRandom.nextFloat() * 2f - 1f) * state.flowJitter
                } else 1f
                sharedPaint.alpha = (baseFlow * flowVariation * 255f).toInt().coerceIn(0, 255)

                var sx = px
                var sy = py

                // Scatter jitter: a constant spray around the stroke, unlike the velocity
                // scatter below. Omnidirectional rather than perpendicular - the two are
                // different effects, and confining a plain scatter to one axis reads as a
                // wobble along the line instead of a spread around it. Scaled by brush width
                // so it stays proportional when the size changes.
                if (state.scatterJitter > 0f) {
                    val amp = state.scatterJitter * width
                    val angleRad = localRandom.nextFloat() * 2f * Math.PI
                    val radius = localRandom.nextFloat() * amp
                    sx += (cos(angleRad) * radius).toFloat()
                    sy += (sin(angleRad) * radius).toFloat()
                }

                // Velocity scatter: offset the stamp perpendicular to the stroke direction.
                // Signed: positive scatters fast strokes, negative scatters slow strokes.
                if (velocityDynamicsActive && state.velocityScatterAmount != 0f) {
                    val scatterNorm = if (state.velocityScatterAmount >= 0f) currentVelocityNorm else 1f - currentVelocityNorm
                    val amp = abs(state.velocityScatterAmount) * scatterNorm * width
                    val offsetDist = (localRandom.nextFloat() * 2f - 1f) * amp
                    val perpRad = Math.toRadians((segmentAngle + 90f).toDouble())
                    sx += (cos(perpRad) * offsetDist).toFloat()
                    sy += (sin(perpRad) * offsetDist).toFloat()
                }

                canvas.save()
                canvas.translate(sx, sy)

                // Partial follow: 0 keeps the stamp's fixed angle, 1 locks it to the path,
                // and everything between leans the stamp into the direction of travel.
                val finalRotation = state.brushRotation + segmentAngle * state.rotationFollow

                val rotJitter = if (state.brushRotationJitter > 0f) {
                    (localRandom.nextFloat() * 2f - 1f) * state.brushRotationJitter
                } else 0f

                canvas.rotate(finalRotation + rotJitter)

                // CRITICAL FIX: Draw at the correct scale to match selectedWidth
                // The cache is 1.5x larger than selectedWidth, so we draw it at 1.0x its size
                // cachedStampScale is 1 unless the multiplier pushed the stamp past the raster
                // cap, in which case it is what grows the capped bitmap back to full size.
                val stampWidth = stamp.width.toFloat() * cachedStampScale
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
    fun applyTextureMask(canvas: Canvas, width: Int, height: Int, state: DrawingState) {
        val mask = state.brushTextureMask ?: return
        val paint = Paint().apply {
            shader = BitmapShader(mask, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    // ============================ Previews ============================

    /**
     * Renders [state]'s brush onto an S-curve using the exact same stamp engine as real strokes
     * - a single code path, so the Brush Studio preview and the brush list thumbnails can't
     * drift from actual rendering. A synthetic speed profile (slow ends, fast middle)
     * demonstrates the velocity dynamics.
     *
     * The engine's own stroke state is saved and restored around the render, so this is safe to
     * call in the middle of a stroke. It does rebuild the shared stamp cache, so the next real
     * stamp pays for one rebuild - callers are expected to remember the result per preset rather
     * than re-render on every recomposition.
     */
    fun renderPreview(state: DrawingState, widthPx: Int, heightPx: Int): Bitmap {
        // A preview always renders as a paint tool, whatever is selected on the canvas.
        // The eraser's stamp is deliberately solid black - it is a mask, not a colour (see
        // updateStampCache) - so with the eraser in hand every thumbnail came out black
        // regardless of the colour asked for. Against a light panel that merely looks like a
        // colour choice; against a dark one the stroke disappears, which is why it read as a
        // night-mode bug rather than as the eraser leaking into the preview.
        val brush = when (state.drawingMode) {
            is DrawingMode.Eraser, is DrawingMode.StraightLineEraser ->
                state.copy(drawingMode = DrawingMode.Freehand)
            else -> state
        }

        val stroke = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(stroke)

        val brushRadius = brush.effectiveWidth / 2f
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
        distanceSinceLastStamp = max(1f, brush.effectiveWidth * brush.brushSpacing)
        velocityDynamicsActive = brush.velocityEnabled

        // One shared random for the whole preview stroke: deterministic (stable image
        // across recompositions) yet properly varied stamp-to-stamp for the jitters
        val previewRandom = Random(42L)
        for (i in 0 until points.size - 1) {
            val f = i.toFloat() / (points.size - 1)
            // Synthetic speed profile (slow ends, fast middle) to demo the velocity dynamics
            currentVelocityNorm = if (brush.velocityEnabled) {
                velocityNorm(sin(f * PI.toFloat()) * 40f)
            } else 0f
            drawSegment(points[i], points[i + 1], brush, targetCanvas = canvas, randomSource = previewRandom)
        }

        distanceSinceLastStamp = savedDistance
        currentVelocityNorm = savedVelocityNorm
        velocityDynamicsActive = savedVelocityActive

        applyTextureMask(canvas, widthPx, heightPx, brush)

        // Opacity is normally applied when compositing the stroke - bake it into the preview
        if (brush.brushOpacity < 1f) {
            val out = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(stroke, 0f, 0f, Paint().apply { alpha = (brush.brushOpacity * 255).toInt() })
            stroke.recycle()
            return out
        }
        return stroke
    }
}
