package com.yighy.pantograph.drawing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import com.yighy.pantograph.data.LayerEntity
import com.yighy.pantograph.data.ProjectRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The layer stack: add, delete, duplicate, merge, reorder, rename, opacity, visibility.
 *
 * Each operation declares up front what it will do to the pixels, because that is what the
 * history entry has to freeze: metadata-only edits pass an empty spec, a merge copies its target
 * and keeps its source by reference, a delete keeps the removed bitmap by reference so undo can
 * re-add it. Getting that wrong doesn't fail loudly - it quietly makes one undo step wrong.
 */
class LayerController(
    private val session: DrawingSession,
    private val repository: ProjectRepository,
    private val projectId: Long,
    private val history: HistoryCoordinator,
    private val persistence: ProjectPersistence,
    private val scope: CoroutineScope
) {
    /** Reads a layer's pixels back from disk, or hands out a blank canvas-sized bitmap. */
    suspend fun loadBitmap(layer: LayerEntity, width: Int, height: Int): Bitmap {
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

    /**
     * Stamps an undone stroke onto the project's reference layer, making the layer the first
     * time one is needed.
     *
     * Deliberately pushes no history entry. This runs as part of an undo, and an undo that
     * quietly added an entry of its own would make the next one take back the trace instead of
     * the stroke before it.
     */
    fun leaveTrace(ghost: StrokeGhost) {
        scope.launch {
            val layerId = ensureReferenceLayer() ?: return@launch
            val bitmap = session.layerBitmaps[layerId] ?: return@launch
            Canvas(bitmap).drawBitmap(ghost.pixels, ghost.left.toFloat(), ghost.top.toFloat(), null)
            session.update { it.copy(renderVersion = it.renderVersion + 1) }
            persistence.scheduleLayerSave(layerId)
        }
    }

    /**
     * The bitmap is put in the map here rather than left to the layer flow's collector, which
     * would only get to it on the next emission - by which point the trace it was made for has
     * already been dropped for want of somewhere to go.
     */
    private suspend fun ensureReferenceLayer(): Long? {
        val state = session.value
        state.layers.firstOrNull { it.isReference }?.let { return it.id }
        if (state.canvasWidth <= 0 || state.canvasHeight <= 0) return null
        // Underneath everything, not on top. A trace exists to be drawn over, and a faint
        // copy of the old stroke lying across the new one tints every colour you are trying to
        // judge. Below the stack it shows through exactly where it is wanted - the area the
        // undone stroke used to occupy is, by definition, empty again on the layer above it.
        //
        // Negative is fine: the query orders by zIndex and the first reorder renumbers the
        // whole stack from zero anyway.
        val zIndex = (state.layers.minOfOrNull { it.zIndex } ?: 0) - 1
        val id = repository.insertLayer(
            LayerEntity(
                projectId = projectId,
                name = "Reference",
                zIndex = zIndex,
                // Faint from the outset, so it reads as something to draw over rather than as
                // an undo that did not take.
                opacity = 0.35f,
                isReference = true
            )
        )
        session.layerBitmaps[id] = Bitmap.createBitmap(
            state.canvasWidth, state.canvasHeight, Bitmap.Config.ARGB_8888
        )
        return id
    }

    fun add(name: String) {
        history.save(emptyMap()) // adding a layer doesn't mutate any existing bitmap
        scope.launch {
            val zIndex = (session.value.layers.maxOfOrNull { it.zIndex } ?: 0) + 1
            val newLayerId = repository.insertLayer(LayerEntity(projectId = projectId, name = name, zIndex = zIndex))
            // A freshly created layer is what the user wants to draw on next
            session.update { it.copy(activeLayerId = newLayerId) }
            persistence.touchProject()
        }
    }

    fun delete(layer: LayerEntity) {
        if (session.value.layers.size <= 1) return
        // The deleted layer's bitmap is kept by reference (never mutated) so undo can re-add it
        history.save(mapOf(layer.id to SnapshotSpec.FullByRef))
        scope.launch {
            repository.deleteLayer(layer)
            session.layerBitmaps.remove(layer.id)
            persistence.touchProject()
        }
    }

    fun toggleVisibility(layer: LayerEntity) {
        history.save(emptyMap()) // only flips a metadata flag, no bitmap pixels change
        scope.launch {
            repository.updateLayer(layer.copy(isVisible = !layer.isVisible))
            persistence.touchProject()
        }
    }

    fun rename(layer: LayerEntity, newName: String) {
        if (newName.isBlank()) return
        history.save(emptyMap()) // renaming is metadata-only
        scope.launch {
            repository.updateLayer(layer.copy(name = newName))
            persistence.touchProject()
        }
    }

    /** Live opacity update (state only); persisted on slider release via [persistOpacity]. */
    fun setOpacity(layer: LayerEntity, opacity: Float) {
        session.update { state ->
            val updatedLayers = state.layers.map {
                if (it.id == layer.id) it.copy(opacity = opacity) else it
            }
            state.copy(layers = updatedLayers)
        }
    }

    fun persistOpacity(layerId: Long) {
        val layer = session.value.layers.find { it.id == layerId } ?: return
        scope.launch {
            repository.updateLayer(layer)
            persistence.touchProject()
        }
    }

    fun duplicate(layer: LayerEntity) {
        history.save(emptyMap()) // creates a new bitmap, doesn't mutate an existing one
        scope.launch {
            val zIndex = (session.value.layers.maxOfOrNull { it.zIndex } ?: 0) + 1
            val newLayerId = repository.insertLayer(
                LayerEntity(projectId = projectId, name = "${layer.name} Copy", zIndex = zIndex, opacity = layer.opacity)
            )
            val originalBitmap = session.layerBitmaps[layer.id]
            if (originalBitmap != null) {
                val newBitmap = originalBitmap.copy(originalBitmap.config ?: Bitmap.Config.ARGB_8888, true)
                session.layerBitmaps[newLayerId] = newBitmap
                persistence.saveLayerNow(newLayerId)
            }
            persistence.touchProject()
        }
    }

    fun mergeDown(layer: LayerEntity) {
        val layers = session.value.layers
        val index = layers.indexOfFirst { it.id == layer.id }
        if (index <= 0) return
        val targetLayer = layers[index - 1]

        // Target is drawn into in place (full copy); source is removed untouched (by ref)
        history.save(mapOf(targetLayer.id to SnapshotSpec.FullMutated, layer.id to SnapshotSpec.FullByRef))
        scope.launch {
            val sourceBitmap = session.layerBitmaps[layer.id] ?: return@launch
            val targetBitmap = session.layerBitmaps[targetLayer.id] ?: return@launch
            val canvas = Canvas(targetBitmap)
            val paint = Paint().apply { alpha = (layer.opacity * 255).toInt() }
            canvas.drawBitmap(sourceBitmap, 0f, 0f, paint)
            repository.deleteLayer(layer)
            session.layerBitmaps.remove(layer.id)
            persistence.saveLayerNow(targetLayer.id)
            persistence.touchProject()
        }
    }

    fun reorder(fromIndex: Int, toIndex: Int) {
        val layers = session.value.layers.toMutableList()
        if (fromIndex !in layers.indices || toIndex !in layers.indices) return

        history.save(emptyMap()) // reordering only changes z-index metadata
        val item = layers.removeAt(fromIndex)
        layers.add(toIndex, item)
        scope.launch {
            layers.forEachIndexed { index, layer -> repository.updateLayerZIndex(layer.id, index) }
            persistence.touchProject()
        }
    }

    fun clear(id: Long) {
        history.save(mapOf(id to SnapshotSpec.FullMutated)) // erased in place right below
        session.layerBitmaps[id]?.eraseColor(android.graphics.Color.TRANSPARENT)
        session.bumpRender()
        persistence.saveLayerNow(id)
        persistence.touchProject()
    }
}
