package com.yighy.paintcursor.drawing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.yighy.paintcursor.data.LayerEntity
import com.yighy.paintcursor.data.ProjectRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.LinkedList
import java.util.UUID

data class HistoryState(
    val layersMetadata: List<LayerEntity>,
    val activeLayerId: Long,
    val bitmapKeys: Map<Long, String>,
    var inMemoryBitmaps: Map<Long, Bitmap>? = null // Temporary cache to prevent disk race conditions
)

/**
 * Owns the undo/redo stacks for [DrawingViewModel]: snapshotting layer bitmaps to an in-memory
 * cache plus disk (cache dir), restoring them back, and keeping the layer table in the database
 * in sync with whatever point in history is currently active.
 *
 * This class doesn't touch [DrawingViewModel]'s UI state directly - callers pass in the current
 * layer bitmaps/metadata and get back what to apply, so the ViewModel stays the single source of
 * truth for `DrawingState`.
 */
class DrawingHistoryManager(
    private val cacheDir: File,
    private val repository: ProjectRepository,
    private val projectId: Long,
    private val ioScope: CoroutineScope
) {
    private val undoStack = LinkedList<HistoryState>()
    private val redoStack = LinkedList<HistoryState>()

    val hasUndo: Boolean get() = undoStack.isNotEmpty()
    val hasRedo: Boolean get() = redoStack.isNotEmpty()

    /**
     * @param mutatedLayerIds Layers whose bitmap object is about to be drawn into in place
     * (e.g. the active layer during a stroke). Only these need an immediate deep copy on the
     * calling thread to freeze their pre-mutation pixels; every other layer's bitmap object
     * won't change before the async disk write below runs, so it's shared by reference instead
     * of copied.
     */
    fun saveState(
        layerBitmaps: Map<Long, Bitmap>,
        layers: List<LayerEntity>,
        activeLayerId: Long,
        historyLimit: Int,
        mutatedLayerIds: Set<Long>
    ) {
        // 1. Immediate deep copy of only the bitmaps about to be mutated in place (CPU intensive
        // but in-memory, relatively fast). Untouched layers are passed through by reference.
        val snapshots = layerBitmaps.mapValues { (id, bitmap) ->
            if (id in mutatedLayerIds) bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true) else bitmap
        }

        // 2. Synchronously update the stack with planned file names
        val historyId = UUID.randomUUID().toString()
        val keys = snapshots.mapValues { (id, _) -> "hist_${historyId}_layer_$id.png" }

        val historyState = HistoryState(
            layersMetadata = layers.map { it.copy() },
            activeLayerId = activeLayerId,
            bitmapKeys = keys,
            inMemoryBitmaps = snapshots // Keep in RAM until disk write is done
        )
        undoStack.addFirst(historyState)

        if (undoStack.size > historyLimit) {
            cleanupHistoryFiles(undoStack.removeLast())
        }

        // Clear redo stack on new action
        redoStack.forEach { cleanupHistoryFiles(it) }
        redoStack.clear()

        // 3. Asynchronously write to disk
        ioScope.launch(Dispatchers.IO) {
            snapshots.forEach { (id, bitmap) ->
                val file = File(cacheDir, keys[id]!!)
                try {
                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("DrawingHistoryManager", "Failed to write history snapshot", e)
                }
            }
            // Once written to disk, we can clear the memory cache for this state
            // but we keep it for a short time to allow immediate aborts to be fast
            delay(1000)
            historyState.inMemoryBitmaps = null
        }
    }

    fun popUndo(): HistoryState? = if (undoStack.isEmpty()) null else undoStack.removeFirst()
    fun popRedo(): HistoryState? = if (redoStack.isEmpty()) null else redoStack.removeFirst()

    /** Snapshots the current state and pushes it onto the redo stack (called from undo()). */
    fun pushToRedo(layerBitmaps: Map<Long, Bitmap>, layers: List<LayerEntity>, activeLayerId: Long) =
        pushCurrentStateTo(redoStack, "redo", layerBitmaps, layers, activeLayerId)

    /** Snapshots the current state and pushes it onto the undo stack (called from redo()). */
    fun pushToUndo(layerBitmaps: Map<Long, Bitmap>, layers: List<LayerEntity>, activeLayerId: Long) =
        pushCurrentStateTo(undoStack, "hist", layerBitmaps, layers, activeLayerId)

    private fun pushCurrentStateTo(
        targetStack: LinkedList<HistoryState>,
        filePrefix: String,
        layerBitmaps: Map<Long, Bitmap>,
        layers: List<LayerEntity>,
        activeLayerId: Long
    ) {
        val snapshots = layerBitmaps.mapValues { (_, bitmap) ->
            bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
        }

        val id = UUID.randomUUID().toString()
        val keys = snapshots.mapValues { (layerId, _) -> "${filePrefix}_${id}_layer_$layerId.png" }

        targetStack.addFirst(HistoryState(
            layersMetadata = layers.map { it.copy() },
            activeLayerId = activeLayerId,
            bitmapKeys = keys
        ))

        ioScope.launch(Dispatchers.IO) {
            snapshots.forEach { (layerId, bitmap) ->
                val file = File(cacheDir, keys[layerId]!!)
                FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
                bitmap.recycle()
            }
        }
    }

    /**
     * Restores the bitmaps for [history], preferring the in-memory cache and falling back to
     * disk. Returns null if no bitmap could be recovered at all, so the caller can leave the
     * canvas untouched rather than wiping it.
     */
    suspend fun restoreBitmaps(history: HistoryState): Map<Long, Bitmap>? {
        val newLayerBitmaps = mutableMapOf<Long, Bitmap>()

        val cached = history.inMemoryBitmaps
        if (cached != null) {
            cached.forEach { (id, bitmap) ->
                newLayerBitmaps[id] = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
            }
        } else {
            withContext(Dispatchers.IO) {
                history.bitmapKeys.forEach { (id, fileName) ->
                    val file = File(cacheDir, fileName)
                    if (file.exists()) {
                        val options = BitmapFactory.Options().apply { inMutable = true }
                        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
                        if (bitmap != null) {
                            newLayerBitmaps[id] = bitmap
                        }
                    }
                }
            }
        }

        if (newLayerBitmaps.isEmpty() && history.bitmapKeys.isNotEmpty()) return null
        return newLayerBitmaps
    }

    /** Makes the layer table match [history]'s metadata: deletes layers absent from it, re-inserts the rest. */
    suspend fun syncLayersWithDatabase(history: HistoryState) {
        withContext(Dispatchers.IO) {
            val currentLayers = repository.getLayersForProject(projectId).first()
            currentLayers.forEach { currentLayer ->
                if (history.layersMetadata.none { it.id == currentLayer.id }) {
                    repository.deleteLayer(currentLayer)
                }
            }
            history.layersMetadata.forEach { oldLayer ->
                repository.insertLayer(oldLayer)
            }
        }
    }

    private fun cleanupHistoryFiles(state: HistoryState) {
        ioScope.launch(Dispatchers.IO) {
            state.bitmapKeys.values.forEach { fileName ->
                File(cacheDir, fileName).delete()
            }
        }
    }

    /** Call from onCleared(): deletes every history file this manager ever wrote, including strays. */
    fun clearAll() {
        undoStack.forEach { cleanupHistoryFiles(it) }
        redoStack.forEach { cleanupHistoryFiles(it) }
        ioScope.launch(Dispatchers.IO) {
            cacheDir.listFiles { _, name -> name.startsWith("hist_") }?.forEach { it.delete() }
        }
    }
}
