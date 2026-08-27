package com.yighy.pantograph.drawing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import androidx.compose.ui.geometry.Offset
import com.yighy.pantograph.data.LayerEntity
import com.yighy.pantograph.data.ProjectRepository
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

/** How a layer's pixels should be captured into a history entry. */
sealed interface SnapshotSpec {
    /** Deep-copy only this region: the pixels the operation is about to overwrite in place. */
    data class Region(val rect: Rect) : SnapshotSpec

    /** Deep-copy the whole layer (scattered in-place mutation, e.g. flood fill or clear). */
    object FullMutated : SnapshotSpec

    /**
     * Keep the whole layer by reference: the operation removes the bitmap from the live map
     * without ever mutating its pixels (delete layer, merge source), so no copy is needed.
     */
    object FullByRef : SnapshotSpec
}

/**
 * Pre-operation pixels of one layer, cropped to the mutated region. Held in RAM until the
 * async disk spill completes (plus a short grace period so an immediate undo/abort is fast).
 */
class RegionSnapshot(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    /** True when the snapshot covers the whole layer - required to revert a layer removal. */
    val isFullLayer: Boolean,
    val fileKey: String,
    @Volatile var inMemory: Bitmap?
)

/**
 * Where the pointer stood when an operation began, so undoing it can put it back.
 *
 * Both points are kept because lazy mode holds the brush at a distance behind the cursor:
 * restoring the cursor alone would leave the rope stretched across the canvas, and the next
 * stroke would open with a long stretch of slack being taken up.
 */
data class CursorAnchor(val cursor: Offset, val brush: Offset)

/**
 * A stroke's own pixels, cropped to its bounds, kept so undoing it can leave the trace behind.
 *
 * Taken from the engine's stroke bitmap rather than reconstructed by comparing the layer before
 * and after: the stroke exists there in isolation, already masked and clipped exactly as it was
 * composited, right up until the next pen-down clears it.
 */
data class StrokeGhost(val pixels: Bitmap, val left: Int, val top: Int)

data class HistoryState(
    val layersMetadata: List<LayerEntity>,
    val activeLayerId: Long,
    /** Only the layers the operation mutated or removed; metadata-only ops have no snapshots. */
    val snapshots: Map<Long, RegionSnapshot>,
    /**
     * Null for everything that has no meaningful place on the canvas - adding a layer, renaming
     * one, reordering the stack. Only strokes set it, and only strokes move the cursor back.
     */
    val cursorAnchor: CursorAnchor? = null,
    /** Set only on stroke entries, and only while the setting asks for traces. */
    val strokeGhost: StrokeGhost? = null
)

/**
 * Owns the undo/redo stacks for [DrawingViewModel]. History is delta-based: each entry stores,
 * per affected layer, only the region of pixels the operation overwrote (or the full bitmap of
 * a removed layer), never the untouched layers. Snapshots spill to disk (cache dir) so RAM use
 * stays bounded, and the layer table in the database is kept in sync with whatever point in
 * history is currently active.
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
     * Freezes the pixels described by [specs] and pushes the entry onto the undo stack.
     * Call before the operation mutates the layer bitmaps. An empty [specs] still pushes an
     * entry (metadata-only undo step).
     */
    fun saveState(
        layerBitmaps: Map<Long, Bitmap>,
        layers: List<LayerEntity>,
        activeLayerId: Long,
        historyLimit: Int,
        specs: Map<Long, SnapshotSpec>,
        cursorAnchor: CursorAnchor? = null
    ) {
        val historyState = HistoryState(
            layersMetadata = layers.map { it.copy() },
            activeLayerId = activeLayerId,
            snapshots = buildSnapshots(layerBitmaps, specs, "hist"),
            cursorAnchor = cursorAnchor
        )
        undoStack.addFirst(historyState)

        if (undoStack.size > historyLimit) {
            cleanupHistoryFiles(undoStack.removeLast())
        }

        // Clear redo stack on new action
        redoStack.forEach { cleanupHistoryFiles(it) }
        redoStack.clear()

        spillToDisk(historyState.snapshots.values)
    }

    /**
     * Builds the inverse of [target] from the current state: captures, for each region [target]
     * will restore, the pixels currently there, plus the full bitmap of any layer that applying
     * [target] will remove. Push the result onto the opposite stack before applying [target].
     */
    fun captureInverse(
        layerBitmaps: Map<Long, Bitmap>,
        layers: List<LayerEntity>,
        activeLayerId: Long,
        target: HistoryState,
        cursorAnchor: CursorAnchor? = null
    ): HistoryState {
        val specs = mutableMapOf<Long, SnapshotSpec>()
        target.snapshots.forEach { (layerId, snap) ->
            if (layerId in layerBitmaps) {
                specs[layerId] = if (snap.isFullLayer) SnapshotSpec.FullMutated
                else SnapshotSpec.Region(Rect(snap.left, snap.top, snap.left + snap.width, snap.top + snap.height))
            }
        }
        val targetIds = target.layersMetadata.map { it.id }.toSet()
        layers.forEach { layer ->
            if (layer.id !in targetIds && layer.id in layerBitmaps) {
                // Applying target removes this layer; keep its full bitmap so the inverse can re-add it
                specs[layer.id] = SnapshotSpec.FullByRef
            }
        }

        val entry = HistoryState(
            layersMetadata = layers.map { it.copy() },
            activeLayerId = activeLayerId,
            snapshots = buildSnapshots(layerBitmaps, specs, "redo"),
            cursorAnchor = cursorAnchor
        )
        spillToDisk(entry.snapshots.values)
        return entry
    }

    /**
     * Hangs [ghost] on the entry just pushed. Separate from [saveState] because the stroke is
     * only final after it has been composited, and history has to be pushed before that - it
     * freezes the pixels the composite is about to overwrite.
     */
    fun attachGhostToLastEntry(ghost: StrokeGhost) {
        val head = undoStack.firstOrNull() ?: return
        undoStack[0] = head.copy(strokeGhost = ghost)
    }

    fun pushToRedo(entry: HistoryState) = redoStack.addFirst(entry)
    fun pushToUndo(entry: HistoryState) = undoStack.addFirst(entry)

    fun popUndo(): HistoryState? = if (undoStack.isEmpty()) null else undoStack.removeFirst()
    fun popRedo(): HistoryState? = if (redoStack.isEmpty()) null else redoStack.removeFirst()

    private fun buildSnapshots(
        layerBitmaps: Map<Long, Bitmap>,
        specs: Map<Long, SnapshotSpec>,
        filePrefix: String
    ): Map<Long, RegionSnapshot> {
        if (specs.isEmpty()) return emptyMap()
        val historyId = UUID.randomUUID().toString()
        val out = mutableMapOf<Long, RegionSnapshot>()
        specs.forEach { (layerId, spec) ->
            val src = layerBitmaps[layerId] ?: return@forEach
            val fileKey = "${filePrefix}_${historyId}_layer_$layerId.png"
            val snapshot = when (spec) {
                is SnapshotSpec.Region -> {
                    val r = Rect(spec.rect)
                    if (!r.intersect(0, 0, src.width, src.height) || r.isEmpty) return@forEach
                    RegionSnapshot(
                        r.left, r.top, r.width(), r.height(), isFullLayer = false, fileKey = fileKey,
                        inMemory = Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height())
                    )
                }
                SnapshotSpec.FullMutated -> RegionSnapshot(
                    0, 0, src.width, src.height, isFullLayer = true, fileKey = fileKey,
                    inMemory = src.copy(src.config ?: Bitmap.Config.ARGB_8888, true)
                )
                SnapshotSpec.FullByRef -> RegionSnapshot(
                    0, 0, src.width, src.height, isFullLayer = true, fileKey = fileKey,
                    inMemory = src
                )
            }
            out[layerId] = snapshot
        }
        return out
    }

    /**
     * Writes the snapshots to the cache dir, then drops the in-memory bitmaps after a short
     * grace period so an immediate undo/abort doesn't have to hit the disk. Snapshots whose
     * write failed stay in RAM.
     */
    private fun spillToDisk(snapshots: Collection<RegionSnapshot>) {
        if (snapshots.isEmpty()) return
        ioScope.launch(Dispatchers.IO) {
            val written = mutableListOf<RegionSnapshot>()
            snapshots.forEach { snap ->
                val bitmap = snap.inMemory ?: return@forEach
                try {
                    FileOutputStream(File(cacheDir, snap.fileKey)).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    written.add(snap)
                } catch (e: Exception) {
                    android.util.Log.e("DrawingHistoryManager", "Failed to write history snapshot", e)
                }
            }
            delay(1000)
            written.forEach { it.inMemory = null }
        }
    }

    /**
     * Loads the snapshot bitmaps for [history], preferring the in-memory cache and falling back
     * to disk. Returns null if any snapshot is unrecoverable, so the caller can leave the canvas
     * untouched rather than applying a partial restore.
     */
    suspend fun restoreBitmaps(history: HistoryState): Map<Long, Bitmap>? {
        if (history.snapshots.isEmpty()) return emptyMap()
        val out = mutableMapOf<Long, Bitmap>()
        withContext(Dispatchers.IO) {
            history.snapshots.forEach { (layerId, snap) ->
                val cached = snap.inMemory
                val bitmap = if (cached != null) {
                    cached.copy(cached.config ?: Bitmap.Config.ARGB_8888, true)
                } else {
                    val file = File(cacheDir, snap.fileKey)
                    if (file.exists()) {
                        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inMutable = true })
                    } else null
                }
                if (bitmap != null) out[layerId] = bitmap
            }
        }
        if (out.size != history.snapshots.size) return null
        return out
    }

    /** Makes the layer table match [history]'s metadata: deletes layers absent from it, re-inserts the rest. */
    suspend fun syncLayersWithDatabase(history: HistoryState) {
        withContext(Dispatchers.IO) {
            val currentLayers = repository.getLayersForProject(projectId).first()
            currentLayers.forEach { currentLayer ->
                // A reference layer is never in an entry's metadata - it is not part of the
                // drawing and history does not carry it - so without this it reads as a layer
                // added since, and every undo would delete the traces it just collected.
                if (currentLayer.isReference) return@forEach
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
        if (state.snapshots.isEmpty()) return
        ioScope.launch(Dispatchers.IO) {
            state.snapshots.values.forEach { snap ->
                File(cacheDir, snap.fileKey).delete()
            }
        }
    }

    /** Call from onCleared(): deletes every history file this manager ever wrote, including strays. */
    fun clearAll() {
        undoStack.forEach { cleanupHistoryFiles(it) }
        redoStack.forEach { cleanupHistoryFiles(it) }
        undoStack.clear()
        redoStack.clear()
        ioScope.launch(Dispatchers.IO) {
            cacheDir.listFiles { _, name -> name.startsWith("hist_") || name.startsWith("redo_") }
                ?.forEach { it.delete() }
        }
    }
}
