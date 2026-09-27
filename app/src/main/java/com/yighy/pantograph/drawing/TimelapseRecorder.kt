package com.yighy.pantograph.drawing

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Where an export of the timelapse has got to. */
sealed interface TimelapseExport {
    data object Idle : TimelapseExport
    data class Running(val progress: Float) : TimelapseExport
    data object Saved : TimelapseExport
    data object Failed : TimelapseExport
}

/** What the timelapse dialog shows of a project's recording. */
data class TimelapseInfo(
    val recording: Boolean = false,
    /** Drawing actions made while recording: strokes, paths, fills. */
    val strokes: Int = 0,
    val frames: Int = 0,
    /** What the frames take up on the device, not what the video will weigh. */
    val bytes: Long = 0
) {
    /** How long the frames play at [TimelapseRecorder.FPS], one frame each. */
    val seconds: Float get() = frames / TimelapseRecorder.FPS.toFloat()
}

/**
 * Keeps a picture of the drawing each time it changes, for a timelapse to be made from later.
 *
 * Off until turned on, per project. The frames are scaled-down JPEGs in a folder of the
 * project's own, next to its layers, so nothing in the database changes: a project that never
 * records never has the folder, and deleting the project deletes it.
 *
 * Capturing reads the layers off the main thread, the way the thumbnail always has: a frame
 * that catches a layer mid-change is only ever a frame, and the next one replaces it.
 */
class TimelapseRecorder(
    private val session: DrawingSession,
    projectId: Long,
    internalFilesDir: File,
    private val scope: CoroutineScope
) {
    private val dir = File(internalFilesDir, dirName(projectId))
    private val metaFile = File(dir, META)

    private val _info = MutableStateFlow(TimelapseInfo())
    val info: StateFlow<TimelapseInfo> = _info

    private val _export = MutableStateFlow<TimelapseExport>(TimelapseExport.Idle)
    val export: StateFlow<TimelapseExport> = _export

    /** Every file operation takes this, so frames land in order and a delete is never half-done. */
    private val lock = Mutex()
    private val loaded = CompletableDeferred<Unit>()

    // Read on the main thread, where the drawing changes; the rest lives under the lock.
    @Volatile private var recording = false
    private val strokes = AtomicInteger(0)
    /** Changes since recording began. A frame is taken every [stride]-th one. */
    private val changes = AtomicLong(0)
    private var capturedAt = 0L
    private var stride = 1
    private var nextIndex = 0
    private var frames = 0
    private var bytes = 0L

    /**
     * One pending capture at most. A burst of changes - a layer's opacity slid across - becomes
     * the one frame of where it ended, not a frame per step of the slide.
     */
    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(Dispatchers.IO) {
            lock.withLock { load() }
            loaded.complete(Unit)
            publish()
        }
        scope.launch(Dispatchers.Default) {
            loaded.await()
            for (request in requests) captureIfDue()
        }
    }

    /** The drawing changed. Called on the main thread, after every change to what it shows. */
    fun onDrawingChanged() {
        if (!recording) return
        changes.incrementAndGet()
        requests.trySend(Unit)
    }

    /** A drawing action landed - a stroke, a path, a fill. Counted, not captured: see above. */
    fun onStroke() {
        if (recording) strokes.incrementAndGet()
    }

    /**
     * Starts or stops recording. Starting takes a frame of the drawing as it is, so a recording
     * begun halfway through opens on what was already there rather than on a blank page.
     */
    fun setRecording(on: Boolean) {
        scope.launch(Dispatchers.IO) {
            loaded.await()
            lock.withLock {
                recording = on
                if (on) {
                    dir.mkdirs()
                    writeFrame()
                }
                saveMeta()
            }
            publish()
        }
    }

    /** Throws the frames away and stops recording. */
    fun delete() {
        scope.launch(Dispatchers.IO) {
            loaded.await()
            lock.withLock {
                dir.deleteRecursively()
                recording = false
                strokes.set(0)
                changes.set(0)
                capturedAt = 0
                stride = 1
                nextIndex = 0
                frames = 0
                bytes = 0
            }
            publish()
        }
    }

    /**
     * Makes a video of the frames, [targetSeconds] long or at their own pace when null, and saves
     * it to the gallery under Movies/Pantograph.
     *
     * Holds the lock throughout, so no frame is thinned out from under the encoder; a change
     * made meanwhile is captured once it is done.
     */
    fun exportVideo(context: Context, projectName: String, targetSeconds: Int?) {
        if (_export.value is TimelapseExport.Running) return
        _export.value = TimelapseExport.Running(0f)
        scope.launch(Dispatchers.Default) {
            loaded.await()
            val temp = File(context.cacheDir, "timelapse_export.mp4")
            val saved = try {
                lock.withLock {
                    val frames = frameFiles()
                    val plan = TimelapseTiming.plan(frames.size, targetSeconds)
                    if (plan.isEmpty()) return@withLock false
                    TimelapseExporter.encode(frames, plan, temp) { _export.value = TimelapseExport.Running(it) }
                    true
                } && saveToGallery(context, temp, projectName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("TimelapseRecorder", "Export failed", e)
                false
            } finally {
                temp.delete()
            }
            _export.value = if (saved) TimelapseExport.Saved else TimelapseExport.Failed
        }
    }

    /** Back to idle once its outcome has been seen, so the next opening starts clean. */
    fun clearExportResult() {
        if (_export.value !is TimelapseExport.Running) _export.value = TimelapseExport.Idle
    }

    private fun saveToGallery(context: Context, video: File, projectName: String): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Timelapse_${projectName}_${System.currentTimeMillis()}.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Pantograph")
            // Hidden from the gallery until it is whole.
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out -> video.inputStream().use { it.copyTo(out) } } ?: error("No stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            true
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private suspend fun captureIfDue() {
        lock.withLock {
            if (!recording) return
            val now = changes.get()
            if (now - capturedAt < stride) return
            capturedAt = now
            writeFrame()
            if (frames > MAX_FRAMES) thin()
            saveMeta()
        }
        publish()
    }

    /** Under [lock]. */
    private fun writeFrame() {
        val state = session.value
        val frame = CanvasComposite.render(state, state.layerBitmaps, FRAME_MAX_SIDE, evenSize = true) ?: return
        val file = File(dir, "f%06d.jpg".format(nextIndex))
        FileOutputStream(file).use { frame.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        frame.recycle()
        nextIndex++
        frames++
        bytes += file.length()
    }

    /**
     * Past the ceiling, every other frame goes and from then on only every other change is
     * captured. The recording keeps the whole drawing at an even pace, only more coarsely, and
     * never takes more room than [MAX_FRAMES] frames. Under [lock].
     */
    private fun thin() {
        val all = frameFiles()
        all.filterIndexed { i, _ -> i % 2 == 1 }.forEach { it.delete() }
        stride *= 2
        val left = frameFiles()
        frames = left.size
        bytes = left.sumOf { it.length() }
    }

    private fun frameFiles(): List<File> =
        dir.listFiles { f -> f.name.startsWith("f") && f.name.endsWith(".jpg") }?.sortedBy { it.name } ?: emptyList()

    /** Under [lock]. */
    private fun load() {
        if (!metaFile.exists()) return
        val props = Properties().apply { metaFile.inputStream().use { load(it) } }
        recording = props.getProperty("recording") == "true"
        strokes.set(props.getProperty("strokes")?.toIntOrNull() ?: 0)
        stride = props.getProperty("stride")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val all = frameFiles()
        frames = all.size
        bytes = all.sumOf { it.length() }
        nextIndex = (all.lastOrNull()?.name?.removeSurrounding("f", ".jpg")?.toIntOrNull() ?: -1) + 1
    }

    /** Under [lock]. */
    private fun saveMeta() {
        if (!dir.exists()) return
        val props = Properties().apply {
            setProperty("recording", recording.toString())
            setProperty("strokes", strokes.get().toString())
            setProperty("stride", stride.toString())
        }
        metaFile.outputStream().use { props.store(it, null) }
    }

    private fun publish() {
        _info.value = TimelapseInfo(recording, strokes.get(), frames, bytes)
    }

    companion object {
        /** Frames per second of the timelapse at its own pace. */
        const val FPS = 30
        private const val MAX_FRAMES = 1500
        /** 720p's long side: sharp enough to share, small enough to keep a lot of. */
        private const val FRAME_MAX_SIDE = 1280
        private const val JPEG_QUALITY = 85
        private const val META = "timelapse.properties"

        /** The project's timelapse folder, under the app's files. */
        fun dirName(projectId: Long) = "timelapse_$projectId"
    }
}
