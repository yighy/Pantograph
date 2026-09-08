package com.yighy.pantograph.drawing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.geometry.lerp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Drives undo/redo against [DrawingHistoryManager] and puts the restored pixels back on the
 * canvas.
 *
 * The manager owns the stacks and the snapshots; this owns the ordering and the application.
 * Every operation goes through [launchHistoryOp], which chains them: an inverse entry has to be
 * captured from a fully applied state, because region deltas pasted out of order corrupt pixels
 * rather than merely skipping a step.
 */
class HistoryCoordinator(
    private val session: DrawingSession,
    private val manager: DrawingHistoryManager,
    private val persistence: ProjectPersistence,
    private val scope: CoroutineScope
) {
    private var historyJob: Job? = null

    /**
     * The cursor glide runs outside [launchHistoryOp] on purpose. Pixels have to come back the
     * instant undo is pressed, and chaining a 220ms animation into that queue would make a held
     * undo crawl. Kept separately and restarted on each move, a burst of undos reads as the
     * cursor walking back through the work rather than as a series of jumps.
     */
    private var cursorJob: Job? = null

    /**
     * Whether the glide in flight gives way to the user driving the cursor.
     *
     * Undo's does: it runs while nothing else is happening, so a finger arriving means the user
     * wants control back. Recoil's must not. The finger steering the cursor is usually still
     * down when the pen comes up - not having to lift it is the whole gesture - so its next
     * move is not somebody taking over, it is the same movement that was already under way.
     */
    private var glideYieldsToMovement = true

    /**
     * Where an undone stroke goes when it is being kept. Assigned by the ViewModel rather than
     * injected: leaving the trace needs the layer controller, which needs this class to exist
     * first, and no construction order satisfies both.
     */
    var onStrokeUndone: ((StrokeTrace) -> Unit)? = null

    private fun launchHistoryOp(block: suspend () -> Unit) {
        val previous = historyJob
        historyJob = scope.launch(Dispatchers.Main) {
            previous?.join()
            block()
        }
    }

    /**
     * Pushes one undo entry. [specs] lists, per layer, which pixels the upcoming operation
     * will overwrite or remove (see [SnapshotSpec]); metadata-only operations pass an empty
     * map. Must be called before the operation mutates the layer bitmaps.
     */
    fun save(specs: Map<Long, SnapshotSpec>, cursorAnchor: CursorAnchor? = null) {
        val state = session.value
        if (state.projectId == -1L) return
        manager.saveState(
            session.layerBitmaps, state.drawingLayers, state.activeLayerId, state.historyLimit, specs, cursorAnchor
        )
        session.update { it.copy(canUndo = true, canRedo = false) }
    }

    /**
     * Silently drops the last history entry and restores its pixels, with no redo entry - the
     * operation is being taken back, not undone. Used by the FAB's drag-to-move and by
     * cancelling a floating selection.
     */
    fun abortLastEntry() {
        launchHistoryOp {
            val lastState = manager.popUndo() ?: return@launchHistoryOp
            // Taking an operation back is not the user undoing anything, so the cursor stays
            // where their finger left it.
            applyHistoryState(lastState, moveCursor = false)
            session.update { it.copy(canUndo = manager.hasUndo) }
        }
    }

    fun undo() {
        launchHistoryOp {
            val prevState = manager.popUndo() ?: return@launchHistoryOp
            val state = session.value
            manager.pushToRedo(
                manager.captureInverse(
                    session.layerBitmaps, state.drawingLayers, state.activeLayerId, prevState,
                    cursorAnchor = inverseAnchor(prevState, state)
                )
            )
            applyHistoryState(prevState)
            // Only after the pixels are back: the trace is a consequence of the undo, and must
            // not reach the canvas before the undo it belongs to has been applied.
            if (session.value.keepUndoneStrokes) {
                prevState.strokeTrace?.let { onStrokeUndone?.invoke(it) }
            }
        }
    }

    /** Hangs a stroke's own pixels on the entry that would undo it. */
    fun attachStrokeTrace(trace: StrokeTrace) = manager.attachTraceToLastEntry(trace)

    fun redo() {
        launchHistoryOp {
            val nextState = manager.popRedo() ?: return@launchHistoryOp
            val state = session.value
            manager.pushToUndo(
                manager.captureInverse(
                    session.layerBitmaps, state.drawingLayers, state.activeLayerId, nextState,
                    cursorAnchor = inverseAnchor(nextState, state)
                )
            )
            applyHistoryState(nextState)
        }
    }

    /**
     * Where going back the other way should land: wherever the pointer is now, but only if the
     * entry being applied moves it in the first place. Without that condition, undoing a stroke
     * and then a layer rename would leave the rename's redo carrying an anchor of its own, and
     * redoing it would shift the cursor for an operation that has no place on the canvas.
     */
    private fun inverseAnchor(target: HistoryState, state: DrawingState): CursorAnchor? =
        if (target.cursorAnchor == null) null
        else CursorAnchor(state.cursorPosition, state.brushPosition)

    /**
     * Abandons an in-flight glide. Called the moment the user drives the cursor themselves: the
     * animation would otherwise keep writing positions underneath them and drag the cursor back
     * off the line they are drawing.
     */
    fun cancelCursorGlide() {
        // A recoil glide holds its ground; see glideYieldsToMovement. Without this it was
        // cancelled by the very finger it was meant to run alongside - which is why the cursor
        // came back when the hand had stopped, and stayed put when it had not.
        if (!glideYieldsToMovement && cursorJob?.isActive == true) return
        cursorJob?.cancel()
        cursorJob = null
    }

    /**
     * Walks the cursor to [anchor] over [durationMs].
     *
     * Public because undo is no longer the only caller: the recoil setting sends the cursor
     * back to where a stroke began every time the pen comes up, which is the same motion for a
     * different reason. It takes its own duration because it happens far more often - once per
     * stroke rather than once in a while, and its own [yieldsToMovement] because the hand it
     * runs under is a moving one.
     *
     * A later call replaces an earlier glide whatever either asked for: undo pressed during a
     * recoil is a new instruction, not a finger carrying on.
     */
    fun glideCursorTo(anchor: CursorAnchor, durationMs: Long = 150L, yieldsToMovement: Boolean = true) {
        cursorJob?.cancel()
        glideYieldsToMovement = yieldsToMovement
        cursorJob = scope.launch(Dispatchers.Main) {
            val fromCursor = session.value.cursorPosition
            val fromBrush = session.value.brushPosition
            // Time-driven rather than Animatable, for the same reason CanvasTransformController
            // spells out: the Compose animation APIs want a MonotonicFrameClock, and the scope
            // this runs on has none. The easing is pure maths and works anywhere.
            val startTime = android.os.SystemClock.uptimeMillis()
            while (true) {
                val fraction = ((android.os.SystemClock.uptimeMillis() - startTime).toFloat() / durationMs)
                    .coerceIn(0f, 1f)
                val t = FastOutSlowInEasing.transform(fraction)
                session.update {
                    it.copy(
                        cursorPosition = lerp(fromCursor, anchor.cursor, t),
                        brushPosition = lerp(fromBrush, anchor.brush, t)
                    )
                }
                if (fraction >= 1f) break
                delay(16)
            }
            // Land exactly on it: a glide that stops a fraction of a pixel short would leave
            // the next stroke starting somewhere other than the one it is replacing.
            session.update { it.copy(cursorPosition = anchor.cursor, brushPosition = anchor.brush) }
        }
    }

    fun clearAll() = manager.clearAll()

    private suspend fun applyHistoryState(history: HistoryState, moveCursor: Boolean = true) {
        if (moveCursor && session.value.undoRestoresCursor) {
            history.cursorAnchor?.let(::glideCursorTo)
        }
        // If any snapshot is unrecoverable, leave the canvas untouched rather than corrupting it
        val restored = manager.restoreBitmaps(history) ?: return

        manager.syncLayersWithDatabase(history)

        // The trace layer is deliberately outside history: undo neither made it nor filled
        // it, and taking it back would throw away traces from strokes still further back that
        // the same undo has nothing to say about. Carried across every restore untouched.
        val traceLayers = session.value.layers.filter { it.isTrace }

        // Drop layers this state doesn't have (reverts an add/duplicate/import)
        val targetIds = history.layersMetadata.map { it.id }.toSet() + traceLayers.map { it.id }
        session.layerBitmaps.keys.retainAll(targetIds)

        val restorePaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        history.snapshots.forEach { (layerId, snap) ->
            val pixels = restored[layerId] ?: return@forEach
            val existing = session.layerBitmaps[layerId]
            if (existing == null) {
                // The reverted operation removed this layer; bring its full bitmap back
                if (snap.isFullLayer && layerId in targetIds) session.layerBitmaps[layerId] = pixels
            } else {
                // Replace exactly the frozen region, transparent pixels included
                Canvas(existing).drawBitmap(pixels, snap.left.toFloat(), snap.top.toFloat(), restorePaint)
            }
        }

        // Safety net: a layer present in metadata but without pixels gets a blank bitmap
        val st = session.value
        if (st.canvasWidth > 0 && st.canvasHeight > 0) {
            history.layersMetadata.forEach { meta ->
                if (meta.id !in session.layerBitmaps) {
                    session.layerBitmaps[meta.id] = Bitmap.createBitmap(st.canvasWidth, st.canvasHeight, Bitmap.Config.ARGB_8888)
                }
            }
        }

        session.update { it.copy(
            // Reference layers first: they sit at the bottom of the stack, and this list is
            // ordered the way the canvas composites it.
            layers = traceLayers + history.layersMetadata,
            activeLayerId = history.activeLayerId,
            layerBitmaps = session.layerBitmaps.toMap(),
            renderVersion = it.renderVersion + 1,
            canUndo = manager.hasUndo,
            canRedo = manager.hasRedo
        ) }

        // Re-save only the layers this restore actually touched, batched so undo spam
        // doesn't rewrite a full PNG per step
        history.snapshots.keys.forEach { id ->
            if (id in session.layerBitmaps) persistence.scheduleLayerSave(id)
        }
        persistence.touchProject()
    }
}
