package com.yighy.pantograph.drawing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.toArgb
import com.yighy.pantograph.data.ProjectRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Gets pixels and project metadata onto disk without stalling the stroke.
 *
 * Both writes are write-behind: a full-canvas PNG encode plus a thumbnail per stroke starves the
 * CPU during rapid strokes, and the project row was being rewritten just as often. Stale layers
 * are batched and flushed at most every 1.5s, the project row at most once a second, and
 * [flushNow] forces both out on a scope that survives the ViewModel.
 *
 * @param scope the ViewModel scope: cancelled with the screen, fine for the throttled loops.
 * @param persistScope outlives onCleared(), so the final flush actually runs.
 */
class ProjectPersistence(
    private val session: DrawingSession,
    private val repository: ProjectRepository,
    private val projectId: Long,
    private val internalFilesDir: File,
    private val scope: CoroutineScope,
    private val persistScope: CoroutineScope
) {
    // Concurrent set: mutated on Main normally, drained from persistScope during shutdown.
    private val dirtyLayerIds: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private var layerSaveJob: Job? = null

    @Volatile private var projectMetaDirty = false
    private var projectMetaJob: Job? = null

    @Volatile private var brushSettingsDirty = false
    private var brushSettingsJob: Job? = null

    // ============================ Layer pixels ============================

    /** Marks [layerId]'s on-disk PNG as stale and arms a batched flush (at most one per 1.5s). */
    fun scheduleLayerSave(layerId: Long) {
        dirtyLayerIds.add(layerId)
        if (layerSaveJob?.isActive != true) {
            layerSaveJob = scope.launch {
                // Loop: a stroke landing while a flush is writing must not wait forever
                while (dirtyLayerIds.isNotEmpty()) {
                    delay(1500)
                    flushPendingLayerSaves()
                }
            }
        }
    }

    /**
     * Reclaims a layer's PNG once the layer itself is gone.
     *
     * Safe to call for a deletion that undo can take back: restoring one re-registers its
     * bitmap and schedules a save, so the file is written again on the way back. A batched
     * save still pending for it finds nothing in [DrawingSession.layerBitmaps] and skips,
     * so this cannot race a rewrite either.
     *
     * On [persistScope] rather than the ViewModel's, so leaving the screen in the same breath
     * as the delete does not cancel the cleanup.
     */
    fun deleteLayerFile(layerId: Long) {
        persistScope.launch(Dispatchers.IO) {
            File(internalFilesDir, "layer_${layerId}.png").delete()
        }
    }

    /** Writes one layer's PNG straight away, for edits that shouldn't wait for the batch. */
    fun saveLayerNow(layerId: Long) {
        scope.launch(Dispatchers.IO) {
            val bitmap = session.layerBitmaps[layerId] ?: return@launch
            writeLayer(layerId, bitmap)
            generateThumbnailNow(session.value)
        }
    }

    private suspend fun writeLayer(layerId: Long, bitmap: Bitmap) {
        withContext(Dispatchers.IO) {
            val file = File(internalFilesDir, "layer_${layerId}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val layer = session.value.layers.find { it.id == layerId }
            if (layer != null) {
                repository.updateLayer(layer.copy(imagePath = file.absolutePath))
            }
        }
    }

    /**
     * Writes every stale layer PNG once, then refreshes the project thumbnail once. Ids are
     * unmarked only after their write completes, so a cancellation mid-flush leaves the
     * unwritten ones dirty for the next flush instead of losing them.
     */
    private suspend fun flushPendingLayerSaves() {
        if (dirtyLayerIds.isEmpty()) return
        dirtyLayerIds.toList().forEach { id ->
            session.layerBitmaps[id]?.let { writeLayer(id, it) }
            dirtyLayerIds.remove(id)
        }
        generateThumbnailNow(session.value)
    }

    /** Immediate flush on the persist scope: safe to call from onCleared() or a lifecycle ON_STOP. */
    fun flushNow() {
        val throttledSaves = layerSaveJob
        val throttledMeta = projectMetaJob
        val throttledBrush = brushSettingsJob
        persistScope.launch {
            throttledSaves?.cancelAndJoin()
            throttledMeta?.cancelAndJoin()
            throttledBrush?.cancelAndJoin()
            flushPendingLayerSaves()
            flushProjectMeta()
            flushBrushSettings()
        }
    }

    // ============================ Thumbnail ============================

    fun generateThumbnail(state: DrawingState) {
        scope.launch { generateThumbnailNow(state) }
    }

    private suspend fun generateThumbnailNow(state: DrawingState) {
        if (state.canvasWidth <= 0 || state.canvasHeight <= 0) return
        withContext(Dispatchers.Default) {
            // Render directly at thumbnail size (max 512px): a full-resolution render + PNG
            // encode is wasted work for a home-grid preview
            val scale = min(1f, 512f / max(state.canvasWidth, state.canvasHeight))
            val tw = (state.canvasWidth * scale).toInt().coerceAtLeast(1)
            val th = (state.canvasHeight * scale).toInt().coerceAtLeast(1)
            val thumb = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(thumb)
            canvas.drawColor(android.graphics.Color.WHITE)
            canvas.scale(scale, scale)
            // Reference layers are working aids, not artwork: they are on the canvas to be
            // traced over, and have no business in the picture of the project.
            state.layers.filter { it.isVisible && !it.isReference }.forEach { layer ->
                session.layerBitmaps[layer.id]?.let {
                    val paint = Paint().apply {
                        alpha = (layer.opacity * 255).toInt()
                        isFilterBitmap = true
                    }
                    canvas.drawBitmap(it, 0f, 0f, paint)
                }
            }
            val thumbFile = File(internalFilesDir, "thumb_${projectId}.png")
            FileOutputStream(thumbFile).use { out -> thumb.compress(Bitmap.CompressFormat.PNG, 90, out) }
            repository.updateProjectThumbnail(projectId, thumbFile.absolutePath)
        }
    }

    // ============================ Project row ============================

    /** Marks the project row stale; the write is throttled to at most once a second. */
    fun touchProject() {
        projectMetaDirty = true
        if (projectMetaJob?.isActive != true) {
            projectMetaJob = scope.launch {
                while (projectMetaDirty) {
                    delay(1000)
                    flushProjectMeta()
                }
            }
        }
    }

    private suspend fun flushProjectMeta() {
        if (!projectMetaDirty) return
        projectMetaDirty = false
        repository.updateProjectTimestamp(projectId)
        saveBrushSettings()
    }

    /**
     * Marks the brush settings stale; the write is throttled to at most once a second.
     *
     * For the settings that stream - a satellite gate sweep emits one value per pointer event -
     * where writing straight through meant a project read plus a project write per frame.
     *
     * Deliberately not [touchProject], which would do the same job but also bump the project's
     * updatedAt: adjusting a brush would then reorder the home grid as though the drawing had
     * been worked on.
     */
    fun scheduleBrushSettingsSave() {
        brushSettingsDirty = true
        if (brushSettingsJob?.isActive != true) {
            brushSettingsJob = scope.launch {
                while (brushSettingsDirty) {
                    delay(1000)
                    flushBrushSettings()
                }
            }
        }
    }

    private suspend fun flushBrushSettings() {
        if (!brushSettingsDirty) return
        brushSettingsDirty = false
        saveBrushSettings()
    }

    /** Persists the brush in hand and the reference image as the project's last-used settings. */
    suspend fun saveBrushSettings() {
        val state = session.value
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
            lastBrushRotationJitter = state.brushRotationJitter,
            lastSizeJitter = state.sizeJitter,
            lastScatterJitter = state.scatterJitter,
            lastFlowJitter = state.flowJitter,
            lastRotationFollow = state.rotationFollow,
            lastBrushTipUri = state.brushTipUri,
            lastBrushTextureUri = state.brushTextureUri,
            lastSizeMultiplier = state.sizeMultiplier,

            referenceImageUri = ref?.uri,
            referenceImageOffsetX = ref?.offset?.x ?: 0f,
            referenceImageOffsetY = ref?.offset?.y ?: 0f,
            referenceImageScale = ref?.scale ?: 1f,
            referenceImageRotation = ref?.rotation ?: 0f
        ))
    }

    // ============================ Export ============================

    /** Flattens the visible layers onto white and drops the file into the pictures collection. */
    fun export(context: android.content.Context, format: String) {
        scope.launch(Dispatchers.Default) {
            val state = session.value
            val width = state.canvasWidth
            val height = state.canvasHeight
            if (width <= 0 || height <= 0) return@launch

            val exportBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(exportBitmap)
            canvas.drawColor(android.graphics.Color.WHITE)

            // As with the thumbnail: a reference layer never leaves the app.
            state.layers.filter { it.isVisible && !it.isReference }.forEach { layer ->
                session.layerBitmaps[layer.id]?.let {
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
                android.util.Log.e("ProjectPersistence", "Export failed", e)
            }
        }
    }
}
