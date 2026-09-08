package com.yighy.pantograph.drawing

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

    /** The layer's colour at a point, or transparent off the edge and with no layer. */
    private fun sampleLayer(bitmap: Bitmap?, x: Float, y: Float): Int {
        if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) return 0
        return bitmap.getPixel(
            x.toInt().coerceIn(0, bitmap.width - 1),
            y.toInt().coerceIn(0, bitmap.height - 1)
        )
    }

    /** [from] moved [t] of the way towards [to], per channel, alpha included. */
    private fun mixArgb(from: Int, to: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        fun channel(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * f).toInt().coerceIn(0, 255)
        }
        return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /** Reused by the colour jitter so a stroke does not allocate one of these per stamp. */
    private val hsvScratch = FloatArray(3)

    /**
     * The colour the smudge brush is carrying, and whether it has picked any up yet.
     *
     * Sampled from the layer rather than from the stroke buffer, which holds only what this
     * stroke has laid down so far. That means smudging back over your own smear picks up what
     * was there before it - the layer does not change until pen-up - which is the honest limit
     * of doing this without compositing mid-stroke.
     */
    private var carriedColor: Int = 0
    private var carryingColor = false
    /**
     * Everything the cached stamp was built from.
     *
     * The whole brush rather than a hand-picked list of what was thought to matter. That list
     * was wrong every time a parameter was added: the stamp went on using the one it had last
     * built, so the studio preview showed the old shape and the canvas painted it, until some
     * unrelated change happened to move the key. Colour and the eraser flag ride along because
     * they change the stamp without being part of a preset.
     *
     * [DrawingState.toBrushConfig] is test-enforced to carry every parameter a preset has, so
     * this cannot quietly miss one. Rebuilding for a change that did not need it costs a single
     * small bitmap, and only ever between strokes - nothing in here moves during one.
     */
    private data class StampKey(val brush: BrushConfig)

    private var lastStampKey: StampKey? = null

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
        carryingColor = false
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
     * Stroke smoothing only bites while the pen is down, and so does sensitivity - unless the
     * Fine tool extends it to the raised pen as well. Also advances the smoothed speed the
     * velocity dynamics read.
     */
    fun smoothMovement(delta: Offset, state: DrawingState): Offset {
        // Engaged, not down: Draw Sensitivity is about precision work, and steering a path
        // point is precision work by any measure - it is the one gesture in the app where you
        // are aiming at something already on the canvas. Smoothing below stays keyed to the
        // pen, because it shapes a line; on a handle it would only put lag between the finger
        // and the point it is meant to be placing.
        //
        // The Fine tool hands it the raised pen too, so the scale never changes underneath the
        // finger. That is also the only way to place the cursor precisely: aiming at something
        // already on the canvas is precision work whether or not the pen happens to be down.
        val sensitivity =
            if (state.isPenEngaged || state.isFineCursor) state.cursorSensitivity else 1.0f
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
     * Repaints the buffer with a whole polyline, stamped end to end as one continuous stroke.
     *
     * Spacing is reset once and then left to accumulate across the joins, so the stamps carry
     * on through a corner instead of restarting at every vertex and clumping there. The seed is
     * derived from the segment index rather than fixed: fixed would give every segment the same
     * jitter and read as a repeat, while anything random would re-roll between the preview and
     * the commit and land a different stroke than the one shown.
     */
    fun drawPolyline(points: List<Offset>, state: DrawingState) {
        if (points.size < 2) return
        clearTarget()
        resetSpacing(state)
        for (i in 0 until points.size - 1) {
            drawSegment(points[i], points[i + 1], state, seed = i.toLong())
        }
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
        // Colour is no longer part of this: the stamp is built as a plain alpha mask and
        // tinted when it is laid down. Changing colour - or jittering it per stamp - therefore
        // costs nothing, where before every shade rebuilt the bitmap.
        val key = StampKey(state.toBrushConfig())

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
            // Off gives the hard stepped edge a pixel-art brush needs. The blur below is
            // skipped with it, since softening an edge is antialiasing by another name.
            isAntiAlias = state.antiAlias
            // Opaque white: what is kept here is the shape's alpha, and the colour arrives at
            // draw time. The eraser wants the same mask - the stroke buffer it fills is used as
            // one either way.
            color = android.graphics.Color.WHITE
            alpha = 255
        }

        // In raster coordinates: below the cap the scale is 1 and this is plain width/2, above
        // it the stamp is drawn into the smaller bitmap and grown back at draw time.
        var drawRadius = (width / 2f) / cachedStampScale
        if (state.brushSoftness > 0f && state.antiAlias) {
            val blurRadius = drawRadius * state.brushSoftness
            if (blurRadius > 0.1f) {
                paint.maskFilter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
                // Adjust radius to match ToolPreviewLayer logic (subtract full blur radius)
                drawRadius = max(1f, drawRadius - blurRadius)
            }
        }

        if (state.brushTipBitmap != null) {
            // Drawn as it is, colours and all: the tint at draw time replaces its RGB and keeps
            // its alpha, which is what the filter here used to do a step earlier.
            val rect = RectF(center - drawRadius, center - drawRadius, center + drawRadius, center + drawRadius)
            canvas.drawBitmap(state.brushTipBitmap, null, rect, Paint(paint))
        } else {
            // The ratio squashes the short axis, which is what gives rotation something to act
            // on: a circle turned is the same circle, an ellipse turned is a nib. Floored at one
            // pixel so a very thin tip still has something to stamp.
            val rx = drawRadius
            val ry = max(1f, drawRadius * state.tipRatio)
            val box = RectF(center - rx, center - ry, center + rx, center + ry)
            when (state.tipShape) {
                TipShape.Round -> canvas.drawOval(box, paint)
                TipShape.Square -> canvas.drawRect(box, paint)
            }
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
        sharedPaint.isAntiAlias = state.antiAlias
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

        // The stamp is a mask, so the colour is applied here rather than baked in. The eraser
        // only ever needs alpha - the buffer it fills is a mask either way - so black will do.
        val erasing = state.drawingMode is DrawingMode.Eraser ||
            state.drawingMode is DrawingMode.StraightLineEraser
        val baseColor = if (erasing) android.graphics.Color.BLACK else state.selectedColor.toArgb()
        val jittersColor = state.hueJitter > 0f || state.saturationJitter > 0f || state.valueJitter > 0f
        // Smudging an eraser has nothing to mean: the buffer it fills is a mask, and the colour
        // in it is never looked at.
        val smudging = state.smudge > 0f && !erasing
        val layerUnderStroke = if (smudging) state.layerBitmaps[state.activeLayerId] else null
        if (!jittersColor && !smudging) {
            sharedPaint.colorFilter = PorterDuffColorFilter(baseColor, PorterDuff.Mode.SRC_IN)
        }

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


                // Colour jitter, symmetric like the others so raising it varies the mark
                // without drifting its average hue. Only reached when something is set: the
                // filter is an allocation, and most brushes want the one made before the loop.
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

                // Smudge first, so what the jitter varies is the colour actually being laid
                // down rather than the one the brush was set to.
                var stampColor = baseColor
                var alphaScale = 1f
                if (smudging) {
                    val sampled = sampleLayer(layerUnderStroke, sx, sy)
                    carriedColor = if (!carryingColor) {
                        carryingColor = true
                        sampled
                    } else {
                        // Length is how much of the old colour survives, so a long smudge
                        // refreshes slowly and drags one colour further.
                        mixArgb(sampled, carriedColor, state.smudgeLength)
                    }
                    stampColor = mixArgb(baseColor, carriedColor, state.smudge)
                    // Picking up from bare canvas has to deposit nothing, or the smudge would
                    // invent opaque paint where there was none.
                    alphaScale = 1f + (android.graphics.Color.alpha(carriedColor) / 255f - 1f) * state.smudge
                    sharedPaint.colorFilter = PorterDuffColorFilter(stampColor, PorterDuff.Mode.SRC_IN)
                }

                sharedPaint.alpha = (baseFlow * flowVariation * alphaScale * 255f)
                    .toInt().coerceIn(0, 255)

                if (jittersColor) {
                    android.graphics.Color.colorToHSV(stampColor, hsvScratch)
                    if (state.hueJitter > 0f) {
                        val shift = (localRandom.nextFloat() * 2f - 1f) * state.hueJitter * 180f
                        hsvScratch[0] = ((hsvScratch[0] + shift) % 360f + 360f) % 360f
                    }
                    if (state.saturationJitter > 0f) {
                        hsvScratch[1] = (hsvScratch[1] +
                            (localRandom.nextFloat() * 2f - 1f) * state.saturationJitter).coerceIn(0f, 1f)
                    }
                    if (state.valueJitter > 0f) {
                        hsvScratch[2] = (hsvScratch[2] +
                            (localRandom.nextFloat() * 2f - 1f) * state.valueJitter).coerceIn(0f, 1f)
                    }
                    sharedPaint.colorFilter = PorterDuffColorFilter(
                        android.graphics.Color.HSVToColor(hsvScratch), PorterDuff.Mode.SRC_IN
                    )
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
