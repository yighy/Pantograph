package com.yighy.pantograph.drawing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import java.io.File

/**
 * Encodes recorded frames into an MP4, with the encoder every Android device already has - no
 * library in the APK for it.
 *
 * Frames go in as YUV written through [Image] planes rather than as a drawing on an input
 * surface: a surface stamps each frame with the time it was drawn, where the timelapse needs the
 * times of its own plan, and the planes' strides are what lets one conversion work whatever
 * layout a device's encoder asks for.
 */
object TimelapseExporter {

    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    /** Plenty for drawings, which are mostly flat colour; about 300 KB a second. */
    const val BITRATE = 2_500_000
    private const val TIMEOUT_US = 10_000L

    /**
     * Writes [frames] to [output] as [plan] lays them out, reporting 0..1 through [onProgress].
     * Throws if the device cannot encode; [output] is then left for the caller to delete.
     */
    suspend fun encode(
        frames: List<File>,
        plan: List<TimelapseTiming.Shot>,
        output: File,
        onProgress: (Float) -> Unit
    ) {
        require(plan.isNotEmpty())
        val first = BitmapFactory.decodeFile(frames[plan.first().frame].path)
            ?: error("Unreadable frame")
        val (width, height) = encodableSize(first.width, first.height)
        first.recycle()

        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, TimelapseRecorder.FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MIME)
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var track = -1

            // Pulls whatever the encoder has finished into the file. True once it has passed on
            // the end of the stream.
            fun drain(): Boolean {
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, 0)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        index >= 0 -> {
                            val buffer = codec.getOutputBuffer(index)!!
                            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (!isConfig && info.size > 0 && muxerStarted) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                muxer.writeSampleData(track, buffer, info)
                            }
                            codec.releaseOutputBuffer(index, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                        }
                    }
                }
            }

            fun nextInput(): Int {
                while (true) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) return index
                    drain()
                }
            }

            // The same frame repeats - held at the end, or shown several times over at the
            // original pace - so the last one decoded is kept rather than read again.
            var pixelsFor = -1
            val argb = IntArray(width * height)
            plan.forEachIndexed { n, shot ->
                currentCoroutineContext().ensureActive()
                if (shot.frame != pixelsFor) {
                    readFrame(frames[shot.frame], width, height, argb)
                    pixelsFor = shot.frame
                }
                val index = nextInput()
                val capacity = codec.getInputBuffer(index)!!.capacity()
                val image = codec.getInputImage(index) ?: error("Encoder takes no image input")
                writeYuv(argb, width, height, image)
                codec.queueInputBuffer(index, 0, minOf(capacity, width * height * 3 / 2), shot.timeUs, 0)
                drain()
                onProgress((n + 1f) / plan.size)
            }
            val end = nextInput()
            codec.queueInputBuffer(end, 0, 0, plan.last().timeUs + 1, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            while (!drain()) currentCoroutineContext().ensureActive()
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
        }
    }

    /**
     * The frame size, if the device's encoder takes it; otherwise the nearest smaller one in
     * multiples of 16, which every encoder does. Frames are scaled to fit.
     */
    private fun encodableSize(width: Int, height: Int): Pair<Int, Int> {
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        fun takes(w: Int, h: Int) =
            codecs.findEncoderForFormat(MediaFormat.createVideoFormat(MIME, w, h)) != null
        if (takes(width, height)) return width to height
        val w16 = (width / 16 * 16).coerceAtLeast(16)
        val h16 = (height / 16 * 16).coerceAtLeast(16)
        return w16 to h16
    }

    private fun readFrame(file: File, width: Int, height: Int, into: IntArray) {
        val decoded = BitmapFactory.decodeFile(file.path) ?: error("Unreadable frame")
        val bitmap = if (decoded.width == width && decoded.height == height) decoded
            else Bitmap.createScaledBitmap(decoded, width, height, true).also { decoded.recycle() }
        bitmap.getPixels(into, 0, width, 0, 0, width, height)
        bitmap.recycle()
    }

    /**
     * ARGB into the encoder's YUV 4:2:0 planes, BT.601 limited range - what players assume of
     * AVC. Written by each plane's own row and pixel strides, which is what makes it work for
     * planar and semi-planar layouts alike.
     */
    private fun writeYuv(argb: IntArray, width: Int, height: Int, image: Image) {
        val planes = image.planes
        val y = planes[0].buffer
        val yRow = planes[0].rowStride
        val yPixel = planes[0].pixelStride
        for (row in 0 until height) {
            val base = row * width
            val out = row * yRow
            for (col in 0 until width) {
                val c = argb[base + col]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                y.put(out + col * yPixel, (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte())
            }
        }
        val u = planes[1].buffer
        val v = planes[2].buffer
        val uRow = planes[1].rowStride
        val uPixel = planes[1].pixelStride
        val vRow = planes[2].rowStride
        val vPixel = planes[2].pixelStride
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                // One sample for each 2x2 block, from its top-left pixel.
                val c = argb[(row * 2) * width + col * 2]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                u.put(row * uRow + col * uPixel, (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte())
                v.put(row * vRow + col * vPixel, (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte())
            }
        }
    }
}
