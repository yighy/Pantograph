package com.yighy.pantograph.drawing

/**
 * When each recorded frame shows in the exported video. Pure arithmetic, so it is tested without
 * a device.
 */
object TimelapseTiming {

    /** How long the finished drawing stays on screen at the end, before the video stops. */
    const val HOLD_US = 2_000_000L

    /** One entry of the video: [frame] is an index into the recorded frames. */
    data class Shot(val frame: Int, val timeUs: Long)

    /**
     * The video for [frameCount] recorded frames: [targetSeconds] long, or at one frame each at
     * [fps] when null - the original pace.
     *
     * More frames than the target has room for at [fps], and they are sampled evenly, first and
     * last always kept. Fewer, and each is shown longer instead, so a short drawing still fills
     * the length asked for rather than coming out as a shorter video. Either way the last frame
     * is shown again [HOLD_US] later: the finished drawing stays on screen before the end.
     */
    fun plan(frameCount: Int, targetSeconds: Int?, fps: Int = TimelapseRecorder.FPS): List<Shot> {
        if (frameCount <= 0) return emptyList()
        val frameUs = 1_000_000L / fps
        val shots = ArrayList<Shot>()
        val slots = targetSeconds?.let { it * fps }
        if (slots == null || frameCount >= slots) {
            val count = slots ?: frameCount
            for (i in 0 until count) {
                val frame = if (count == 1) 0 else (i.toLong() * (frameCount - 1) / (count - 1)).toInt()
                shots += Shot(frame, i * frameUs)
            }
        } else {
            val totalUs = targetSeconds * 1_000_000L
            for (i in 0 until frameCount) shots += Shot(i, i * totalUs / frameCount)
        }
        shots += Shot(frameCount - 1, shots.last().timeUs + HOLD_US)
        return shots
    }

    /** The video's length, to the last shot. */
    fun durationUs(plan: List<Shot>): Long = plan.lastOrNull()?.timeUs ?: 0L
}
