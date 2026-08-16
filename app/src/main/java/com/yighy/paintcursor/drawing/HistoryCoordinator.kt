package com.yighy.paintcursor.drawing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    fun save(specs: Map<Long, SnapshotSpec>) {
        val state = session.value
        if (state.projectId == -1L) return
        manager.saveState(session.layerBitmaps, state.layers, state.activeLayerId, state.historyLimit, specs)
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
            applyHistoryState(lastState)
            session.update { it.copy(canUndo = manager.hasUndo) }
        }
    }

    fun undo() {
        launchHistoryOp {
            val prevState = manager.popUndo() ?: return@launchHistoryOp
            val state = session.value
            manager.pushToRedo(
                manager.captureInverse(session.layerBitmaps, state.layers, state.activeLayerId, prevState)
            )
            applyHistoryState(prevState)
        }
    }

    fun redo() {
        launchHistoryOp {
            val nextState = manager.popRedo() ?: return@launchHistoryOp
            val state = session.value
            manager.pushToUndo(
                manager.captureInverse(session.layerBitmaps, state.layers, state.activeLayerId, nextState)
            )
            applyHistoryState(nextState)
        }
    }

    fun clearAll() = manager.clearAll()

    private suspend fun applyHistoryState(history: HistoryState) {
        // If any snapshot is unrecoverable, leave the canvas untouched rather than corrupting it
        val restored = manager.restoreBitmaps(history) ?: return

        manager.syncLayersWithDatabase(history)

        // Drop layers this state doesn't have (reverts an add/duplicate/import)
        val targetIds = history.layersMetadata.map { it.id }.toSet()
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
            layers = history.layersMetadata,
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
