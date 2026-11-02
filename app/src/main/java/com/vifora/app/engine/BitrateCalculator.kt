package com.vifora.app.engine

/**
 * Target-size bitrate allocator.
 *
 * Exact requirements behaviour (no overhead reserve, no bitrate floors):
 * - <=60s with audio: 90/10
 * - >=1800s (30m) with audio: 50/50
 * - in between: linear interpolation
 * - no audio: 100/0
 *
 * Retry policy: max 4 retries = 5 total tries per stage.
 */
object BitrateCalculator {
    const val MAX_RETRIES = 4
    const val MAX_ATTEMPTS = MAX_RETRIES + 1

    const val VIDEO_RETRY_FACTOR = 0.90
    const val AUDIO_RETRY_FACTOR = 0.95

    fun videoRatio(durationSec: Double, hasAudio: Boolean): Double {
        if (!hasAudio) return 1.0
        if (durationSec <= 60.0) return 0.90
        if (durationSec >= 1800.0) return 0.50
        return 0.90 - 0.40 * ((durationSec - 60.0) / (1800.0 - 60.0))
    }

    fun audioRatio(durationSec: Double, hasAudio: Boolean): Double {
        if (!hasAudio) return 0.0
        return 1.0 - videoRatio(durationSec, true)
    }

    data class Budget(
        val totalBps: Long,
        val videoBps: Long,
        val audioBps: Long,
        val videoRatio: Double,
    )

    fun budget(targetMb: Double, durationSec: Double, hasAudio: Boolean): Budget {
        require(targetMb > 0) { "targetMb must be > 0" }
        require(durationSec > 0) { "durationSec must be > 0" }
        val totalBps = ((targetMb * 8.0 * 1_048_576.0) / durationSec).toLong()
        val rv = videoRatio(durationSec, hasAudio)
        val videoBps = (totalBps * rv).toLong()
        val audioBps = (totalBps - videoBps).let { if (hasAudio) it else 0L }
        return Budget(totalBps, videoBps, audioBps, rv)
    }

    fun reduceVideoBitrate(videoBps: Long): Long = (videoBps * VIDEO_RETRY_FACTOR).toLong()

    fun reduceAudioBitrate(audioBps: Long): Long = (audioBps * AUDIO_RETRY_FACTOR).toLong()

    /** Guard A: refuse to start when target >= source (would inflate). */
    fun shouldBlockUpfront(sourceBytes: Long, targetBytes: Long): Boolean =
        targetBytes >= sourceBytes

    /** Guard B: discard result when output exceeds source or target. */
    fun shouldDiscardResult(outputBytes: Long, sourceBytes: Long, targetBytes: Long): Boolean =
        outputBytes > sourceBytes || outputBytes > targetBytes
}
