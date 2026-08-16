package com.yighy.paintcursor.drawing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import com.yighy.paintcursor.data.LayerEntity
import com.yighy.paintcursor.data.ProjectRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The selection tools: lasso, rectangle, wand and colour select, plus everything a selection can
 * then do - lift, move, scale, rotate, duplicate, delete, invert, commit.
 *
 * The source of truth is the mask in [DrawingState.selectionMask], not the traced polygon: it
 * covers pixel selections and inversions as well as shapes, and it doubles as the clip every
 * drawing tool honours while a selection stays closed. The polygon is kept only for the
 * marching-ants outline.
 *
 * A selection being *floating* means its pixels have left the layer and live in
 * [DrawingState.floatingBitmap] until committed. Whether the lift was a cut or a copy, and which
 * layer it came from, decides how many history entries the round trip needs - see
 * [SelectionCommitPolicy].
 */
class SelectionController(
    private val session: DrawingSession,
    private val engine: StrokeEngine,
    private val history: HistoryCoordinator,
    private val persistence: ProjectPersistence,
    private val repository: ProjectRepository,
    private val projectId: Long,
    private val scope: CoroutineScope
) {
    private var rectAnchor = Offset.Zero
    private var floatingFromCut = false

    /** Layer the floating selection was lifted from, so a paste onto a different one can be
     *  told apart from a move within the same layer (they need different history entries). */
    private var floatingSourceLayerId: Long? = null

    /** True while the floating selection holds an imported image whose layer isn't created yet. */
    private var pendingImport = false

    // ============================ Geometry helpers ============================

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

    // ============================ Pen gesture ============================

    /** Cursor movement while the pen is down: drags the floating pixels, or extends the trace. */
    fun updateDrag(pos: Offset, state: DrawingState) {
        if (state.floatingBitmap != null) {
            val delta = pos - state.brushPosition
            session.update { it.copy(floatingOffset = it.floatingOffset + delta) }
        } else if (!state.isSelectionClosed && state.selectionPoints.isNotEmpty()) {
            if (state.drawingMode is DrawingMode.SelectRect) {
                val minX = min(rectAnchor.x, pos.x)
                val maxX = max(rectAnchor.x, pos.x)
                val minY = min(rectAnchor.y, pos.y)
                val maxY = max(rectAnchor.y, pos.y)
                session.update { it.copy(selectionPoints = listOf(
                    Offset(minX, minY), Offset(maxX, minY), Offset(maxX, maxY), Offset(minX, maxY)
                )) }
            } else {
                if ((pos - state.selectionPoints.last()).getDistance() > 2f) {
                    session.update { it.copy(selectionPoints = it.selectionPoints + pos) }
                }
            }
        }
    }

    fun handlePen(down: Boolean, state: DrawingState) {
        if (state.isPenDown == down) return
        if (down) {
            engine.resetDistance()
            val pos = state.brushPosition
            when {
                // Pen down while floating: cursor drag now moves the selection
                state.floatingBitmap != null -> Unit
                // Pen down inside a closed selection: lift it so it can be moved
                state.isSelectionClosed && isPointInSelection(pos, state) -> lift(cut = true)
                // Wand/Color: a tap computes the pixel selection at the cursor. These finish
                // on the spot - there is no outline being traced and nothing to hold open -
                // so the pen must not latch. Tapping the canvas goes through togglePen(),
                // which has no matching release, and a latch there left the pen stuck down
                // until the next tap. (From the FAB it was invisible: that gesture always
                // issues its own pen-up when the finger lifts.)
                state.drawingMode is DrawingMode.SelectWand -> {
                    computeMagicSelection(pos, contiguous = true)
                    return
                }
                state.drawingMode is DrawingMode.SelectColor -> {
                    computeMagicSelection(pos, contiguous = false)
                    return
                }
                // Lasso/Rect: start tracing a new outline
                else -> {
                    rectAnchor = pos
                    session.update { it.copy(selectionPoints = listOf(pos), isSelectionClosed = false, selectionMask = null) }
                }
            }
            session.update { it.copy(isPenDown = true) }
        } else {
            val s = session.value
            val isTracing = (s.drawingMode is DrawingMode.SelectLasso || s.drawingMode is DrawingMode.SelectRect) &&
                s.floatingBitmap == null && !s.isSelectionClosed && s.selectionPoints.isNotEmpty()
            if (isTracing) {
                // Close the traced outline; discard degenerate selections
                val pts = s.selectionPoints
                val valid = pts.size >= 3 && polygonArea(pts) > 25f
                session.update { it.copy(
                    isPenDown = false,
                    isSelectionClosed = valid,
                    selectionPoints = if (valid) pts else emptyList(),
                    selectionMask = if (valid) buildMaskFromPath(pts, s) else null
                ) }
            } else {
                session.update { it.copy(isPenDown = false) }
            }
            engine.endStroke()
        }
    }

    /**
     * Wand (contiguous flood by color similarity) and Color (every similar pixel on the
     * active layer) selections. Both use the fill tolerance setting.
     */
    private fun computeMagicSelection(pos: Offset, contiguous: Boolean) {
        val state = session.value
        val layerBitmap = session.layerBitmaps[state.activeLayerId] ?: return
        val startX = pos.x.toInt().coerceIn(0, layerBitmap.width - 1)
        val startY = pos.y.toInt().coerceIn(0, layerBitmap.height - 1)
        val tolerance = state.fillTolerance.toInt()

        scope.launch(Dispatchers.Default) {
            val w = layerBitmap.width
            val h = layerBitmap.height
            val pixels = IntArray(w * h)
            layerBitmap.getPixels(pixels, 0, w, 0, 0, w, h)

            val maskPixels = MagicSelect.computeMask(pixels, w, h, startX, startY, tolerance, contiguous)

            val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            mask.setPixels(maskPixels, 0, w, 0, 0, w, h)

            withContext(Dispatchers.Main) {
                session.update { it.copy(
                    selectionMask = mask,
                    isSelectionClosed = true,
                    selectionPoints = emptyList(),
                    renderVersion = it.renderVersion + 1
                ) }
            }
        }
    }

    // ============================ Operations ============================

    /** Inverts the selected area (mask-based, so it works for every selection tool). */
    fun invert() {
        val state = session.value
        val mask = state.selectionMask ?: return
        if (state.floatingBitmap != null) return
        val inverted = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(inverted)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.drawBitmap(mask, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        session.update { it.copy(
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
    fun lift(cut: Boolean) {
        val state = session.value
        if (!state.isSelectionClosed || state.floatingBitmap != null) return
        val mask = state.selectionMask ?: return
        val layerBitmap = session.layerBitmaps[state.activeLayerId] ?: return

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
        if (right < 0) { clear(); return }
        right = (right + 1).coerceAtMost(layerBitmap.width)
        bottom = (bottom + 1).coerceAtMost(layerBitmap.height)

        val floatBitmap = Bitmap.createBitmap(right - left, bottom - top, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(floatBitmap)
        canvas.translate(-left.toFloat(), -top.toFloat())
        canvas.drawBitmap(layerBitmap, 0f, 0f, null)
        canvas.drawBitmap(mask, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })

        if (cut) {
            // Full layer: this one entry must also cover the later paste, which can land anywhere
            history.save(mapOf(state.activeLayerId to SnapshotSpec.FullMutated))
            Canvas(layerBitmap).drawBitmap(mask, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        }
        floatingFromCut = cut
        floatingSourceLayerId = state.activeLayerId

        val origin = Offset(left.toFloat(), top.toFloat())
        session.update { it.copy(
            floatingBitmap = floatBitmap,
            floatingOrigin = origin,
            // Nudge duplicates so the copy is immediately visible
            floatingOffset = if (cut) origin else origin + Offset(24f, 24f),
            floatingScale = 1f,
            floatingRotation = 0f,
            renderVersion = it.renderVersion + 1
        ) }
    }

    fun duplicate() {
        if (session.value.floatingBitmap != null) return
        lift(cut = false)
    }

    fun delete() {
        val state = session.value
        if (state.floatingBitmap != null) {
            // A cut selection is already gone from the layer (history saved at lift);
            // a duplicated copy never touched it. Dropping the floating bitmap is enough.
            clear()
            return
        }
        if (!state.isSelectionClosed) return
        val mask = state.selectionMask ?: return
        val layerBitmap = session.layerBitmaps[state.activeLayerId] ?: return
        history.save(mapOf(state.activeLayerId to SnapshotSpec.FullMutated))
        Canvas(layerBitmap).drawBitmap(mask, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        clear()
        persistence.saveLayerNow(state.activeLayerId)
        persistence.touchProject()
    }

    /**
     * Imports an image as a floating selection. The user can move/transform it with the
     * regular selection controls; the new layer is only created when the import is applied,
     * so cancelling leaves no empty layer behind.
     */
    fun importImage(context: android.content.Context, uri: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val decoded = decodeImage(context, uri)
                val bitmap = decoded.bitmap ?: return@launch
                val state = session.value

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
                    finalizeActive()
                    session.update { it.copy(drawingMode = DrawingMode.SelectRect, isEyeDropperMode = false) }
                    pendingImport = true
                    floatingFromCut = false

                    val origin = Offset((state.canvasWidth - scaled.width) / 2f, (state.canvasHeight - scaled.height) / 2f)
                    val w = scaled.width.toFloat()
                    val h = scaled.height.toFloat()
                    session.update { it.copy(
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
                android.util.Log.e("SelectionController", "Failed to import image as layer", e)
            }
        }
    }

    private fun commitImportedLayer(state: DrawingState, floatBitmap: Bitmap) {
        pendingImport = false
        scope.launch {
            history.save(emptyMap()) // a brand-new layer is added; existing bitmaps stay untouched
            val zIndex = (session.value.layers.maxOfOrNull { it.zIndex } ?: 0) + 1
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

            session.layerBitmaps[newLayerId] = layerBitmap
            session.update { it.copy(
                activeLayerId = newLayerId,
                layerBitmaps = session.layerBitmaps.toMap(),
                // Leave the selection tool unless the user already switched to something else
                drawingMode = if (it.drawingMode is DrawingMode.SelectRect) DrawingMode.Freehand else it.drawingMode
            ) }
            clear()
            persistence.saveLayerNow(newLayerId)
            persistence.touchProject()
        }
    }

    /**
     * Canvas-space bounding box of the floating bitmap under its current translate/rotate/scale
     * transform (mirrors the Canvas ops in [commit]), padded for filtering bleed.
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
    fun commit() {
        val state = session.value
        val floatBitmap = state.floatingBitmap ?: return
        if (pendingImport) {
            commitImportedLayer(state, floatBitmap)
            return
        }
        val layerBitmap = session.layerBitmaps[state.activeLayerId]
        if (layerBitmap != null) {
            // A cut already saved history at lift time, so the whole move undoes as one step.
            // A duplicate only paints inside the transformed floating rect - snapshot just that.
            // A cut that lands on a *different* layer still needs its own entry: the lift-time
            // snapshot covers the source layer only, so without this the paste would survive
            // an undo that had already restored the pixels it came from.
            if (SelectionCommitPolicy.needsTargetSnapshot(
                    fromCut = floatingFromCut,
                    sourceLayerId = floatingSourceLayerId,
                    targetLayerId = state.activeLayerId
                )
            ) {
                val bounds = floatingPasteBounds(state, floatBitmap)
                history.save(mapOf(state.activeLayerId to SnapshotSpec.Region(bounds)))
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
            persistence.saveLayerNow(state.activeLayerId)
            persistence.touchProject()
        }
        clear()
    }

    /** Cancels a floating selection: cut pixels are restored via the history entry saved at lift. */
    fun cancel() {
        val wasImport = pendingImport
        if (session.value.floatingBitmap != null && floatingFromCut) {
            history.abortLastEntry()
        }
        clear()
        if (wasImport) {
            session.update { if (it.drawingMode is DrawingMode.SelectRect) it.copy(drawingMode = DrawingMode.Freehand) else it }
        }
    }

    fun clear() {
        floatingFromCut = false
        floatingSourceLayerId = null
        pendingImport = false
        session.update { it.copy(
            selectionPoints = emptyList(),
            isSelectionClosed = false,
            selectionMask = null,
            floatingBitmap = null,
            floatingScale = 1f,
            floatingRotation = 0f,
            renderVersion = it.renderVersion + 1
        ) }
    }

    fun setScale(scale: Float) {
        session.update { it.copy(floatingScale = scale.coerceIn(0.1f, 3f)) }
    }

    fun setRotation(degrees: Float) {
        session.update { it.copy(floatingRotation = degrees.coerceIn(-180f, 180f)) }
    }

    /**
     * Called when switching tools: commits any floating selection and drops an unfinished
     * trace. A CLOSED selection is deliberately kept - it acts as a mask that clips every
     * drawing tool until the user deselects.
     */
    fun finalizeActive() {
        val state = session.value
        if (state.floatingBitmap != null) {
            commit()
        } else if (!state.isSelectionClosed && state.selectionPoints.isNotEmpty()) {
            clear()
        }
    }

    /**
     * Drops an unfinished trace when the active layer changes. A floating selection deliberately
     * survives: that IS how you paste into another layer. A half-drawn lasso has no meaning on a
     * different layer, though.
     */
    fun dropUnfinishedTrace() {
        val state = session.value
        if (state.floatingBitmap == null && !state.isSelectionClosed && state.selectionPoints.isNotEmpty()) {
            clear()
        }
    }
}
