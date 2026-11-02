package com.vifora.app.engine

import android.os.SystemClock
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

sealed interface CompressResult {
    data class Success(
        val outputBytes: Long,
        val videoAttempts: Int,
        val audioAttempts: Int,
        val mode: String = "2-pass",
    ) : CompressResult
    data class Failure(val reason: String) : CompressResult
}

/**
 * Executes 2-pass encode with retry:
 * video: reduce 10% per retry, audio: reduce 5% per retry.
 * Max 4 retries (5 total tries) per stage.
 */
class CompressionEngine {
    data class Progress(
        val stage: String,
        val attempt: Int,
        val maxAttempts: Int,
        /** 0..1 encode position, null when unknown (probing). */
        val frac: Float? = null,
        val detail: String = "",
    )

    private data class StatsCtx(
        val stage: String,
        val attempt: Int,
        val maxAttempts: Int,
        val durationMs: Long,
        var lastEmitMs: Long = 0L,
    )

    /** Resolved at runtime from the bundled FFmpeg build. */
    data class EncoderCaps(
        val av1Encoder: String,
        val x264PixFmt: String,
        val x265PixFmt: String,
        val av1PixFmt: String,
    )

    @Volatile var cancelled = false

    fun cancel() {
        cancelled = true
        runCatching { FFmpegKit.cancel() }
    }

    @Volatile private var cachedCaps: EncoderCaps? = null

    /** Probe which encoders / pixel formats the bundled FFmpeg actually supports. */
    fun probeCaps(): EncoderCaps {
        cachedCaps?.let { return it }
        fun helpOutput(encoder: String): String {
            val s = runCatching { FFmpegKit.execute("-h encoder=$encoder") }.getOrNull()
                ?: return ""
            return s.output ?: ""
        }
        fun hasEncoder(encoder: String): Boolean =
            helpOutput(encoder).contains("Encoder '$encoder'")
        fun declaredPixFmts(encoder: String): Set<String> {
            val line = helpOutput(encoder).lineSequence()
                .firstOrNull { it.contains("Supported pixel formats", ignoreCase = true) }
                ?: return emptySet()
            return line.substringAfter(":").trim().split(Regex("\\s+")).toSet()
        }
        // Declared lists lie for multilib-limited builds (e.g. 8-bit-only x265
        // still lists yuv420p10le), so trial-open the encoder to be sure.
        fun canOpen(encoder: String, pixFmt: String, extra: List<String>): Boolean {
            val cmd = mutableListOf(
                "-y", "-f", "lavfi", "-i", "testsrc=size=64x64:rate=1:duration=0.2",
                "-c:v", encoder, "-pix_fmt", pixFmt,
            )
            cmd += extra
            cmd += listOf("-f", "null", "-")
            val s = runCatching { FFmpegKit.execute(cmd.joinToString(" ")) }.getOrNull()
                ?: return false
            return ReturnCode.isSuccess(s.returnCode)
        }
        fun pick(encoder: String, extra: List<String>): String {
            if ("yuv420p10le" !in declaredPixFmts(encoder)) return "yuv420p"
            return if (canOpen(encoder, "yuv420p10le", extra)) "yuv420p10le" else "yuv420p"
        }
        val hasSvt = hasEncoder("libsvtav1")
        val av1 = if (hasSvt) "libsvtav1" else "libaom-av1"
        val caps = EncoderCaps(
            av1Encoder = av1,
            x264PixFmt = pick("libx264", listOf("-preset", "veryfast")),
            x265PixFmt = pick("libx265", listOf("-preset", "veryfast")),
            av1PixFmt = pick(av1, if (av1 == "libaom-av1") listOf("-cpu-used", "4") else listOf("-preset", "8")),
        )
        cachedCaps = caps
        return caps
    }

    fun execute(
        inputPath: String,
        outputPath: String,
        info: VideoInfo,
        preset: Preset,
        options: AdvancedOptions,
        targetMb: Double,
        passLogPrefix: String = FFmpegCommandBuilder.PASS_LOG_PREFIX,
        onProgress: (Progress) -> Unit = {},
    ): CompressResult {
        // Trimmed output is shorter: budget off the effective duration so the
        // target bitrate matches what is actually encoded.
        val effDurationSec = options.trim?.durationSec?.takeIf { it > 0 } ?: info.durationSec
        val effInfo = info.copy(durationSec = effDurationSec)
        val stats = StatsState()
        FFmpegKitConfig.enableStatisticsCallback { s ->
            val c = stats.current ?: return@enableStatisticsCallback
            val now = SystemClock.elapsedRealtime()
            if (now - c.lastEmitMs < 400 && s.time < c.durationMs) return@enableStatisticsCallback
            c.lastEmitMs = now
            val frac = (s.time.toFloat() / c.durationMs.toFloat().coerceAtLeast(1f))
                .coerceIn(0f, 1f)
            onProgress(
                Progress(c.stage, c.attempt, c.maxAttempts, frac, "%.0f%%".format(frac * 100)),
            )
        }
        try {
            return executeInner(
                inputPath, outputPath, effInfo, preset, options,
                targetMb, passLogPrefix, stats, onProgress,
            )
        } finally {
            FFmpegKitConfig.enableStatisticsCallback(null)
        }
    }

    private data class StatsState(@Volatile var current: StatsCtx? = null) {
        fun track(stage: String, attempt: Int, maxAttempts: Int, durationSec: Double) {
            current = StatsCtx(
                stage, attempt, maxAttempts,
                (durationSec * 1000).toLong().coerceAtLeast(1L),
            )
        }
        fun clear() {
            current = null
        }
    }

    private fun executeInner(
        inputPath: String,
        outputPath: String,
        info: VideoInfo,
        preset: Preset,
        options: AdvancedOptions,
        targetMb: Double,
        passLogPrefix: String,
        stats: StatsState,
        onProgress: (Progress) -> Unit,
    ): CompressResult {
        val targetBytes = (targetMb * 1_048_576.0).toLong()
        if (BitrateCalculator.shouldBlockUpfront(info.sourceBytes, targetBytes)) {
            return CompressResult.Failure(
                "Target (${targetMb}MB) >= source (${info.sourceBytes / 1_048_576}MB). " +
                    "Refusing to inflate file.",
            )
        }
        val effectiveAudio = info.hasAudio && !options.removeAudio
        var budget = BitrateCalculator.budget(targetMb, info.durationSec, effectiveAudio)
        onProgress(Progress("probing encoders", 1, BitrateCalculator.MAX_ATTEMPTS))
        val caps = probeCaps()
        val probedPixFmt = when (preset) {
            Preset.COMPATIBILITY -> caps.x264PixFmt
            Preset.DISCORD -> caps.x265PixFmt
            Preset.MAX_QUALITY -> caps.av1PixFmt
        }
        Log.i("ViforaFF", "caps=$caps probedPixFmt=$probedPixFmt preset=$preset")

        // --- Variant selection: probe short (5s) commands, fastest diagnostics
        // first. Tries full 2-pass, then degraded 2-pass, then single-pass ABR
        // (some bundled encoders, e.g. this build's libx265, reject -pass 1).
        // First variant whose probe opens wins and is used for the encode.
        data class Variant(val pixFmt: String, val useFilter: Boolean, val twoPass: Boolean)
        val variants = listOf(
            Variant(probedPixFmt, true, true),
            Variant(probedPixFmt, false, true),
            Variant("yuv420p", false, true),
            Variant(probedPixFmt, true, false),
            Variant(probedPixFmt, false, false),
            Variant("yuv420p", false, false),
        ).distinct()
        var chosen: Variant? = null
        var lastErr = "rc=?"
        for (v in variants) {
            if (cancelled) return CompressResult.Failure("Cancelled")
            val probeCmd = if (v.twoPass) {
                FFmpegCommandBuilder.pass1(inputPath, preset, budget.videoBps, options, passLogPrefix, v.pixFmt, caps.av1Encoder, v.useFilter, 5.0)
            } else {
                FFmpegCommandBuilder.singlePass(inputPath, outputPath, preset, budget.videoBps, budget.audioBps, options, passLogPrefix, v.pixFmt, caps.av1Encoder, v.useFilter, 5.0, true)
            }
            onProgress(Progress("probing ${if (v.twoPass) "2pass" else "1pass"}-${v.pixFmt}${if (v.useFilter) "" else "-nofilter"}", 1, BitrateCalculator.MAX_ATTEMPTS))
            stats.track("probe", 1, BitrateCalculator.MAX_ATTEMPTS, 5.0)
            val (ok, err) = runFFmpeg(
                probeCmd,
                "probe-${if (v.twoPass) "2p" else "1p"}-${v.pixFmt}-${if (v.useFilter) "vf" else "novf"}",
            )
            stats.clear()
            lastErr = err
            if (ok) {
                chosen = v
                Log.i("ViforaFF", "selected variant=$v")
                break
            }
        }
        val variant = chosen
            ?: return CompressResult.Failure("No encoder variant opened: $lastErr")
        val pixFmt = variant.pixFmt
        val useFilter = variant.useFilter

        if (!variant.twoPass) return singlePassLoop(
            inputPath, outputPath, info, preset, options, targetMb, targetBytes,
            budget, passLogPrefix, pixFmt, caps.av1Encoder, useFilter, stats, onProgress,
        )

        // --- Video stage: pass1 + pass2 video, retry pass2 on overshoot ---
        var videoAttempts = 0
        var videoBytes = 0L
        while (true) {
            if (cancelled) return CompressResult.Failure("Cancelled")
            videoAttempts++
            onProgress(Progress("video-pass", videoAttempts, BitrateCalculator.MAX_ATTEMPTS))
            stats.track("video", videoAttempts, BitrateCalculator.MAX_ATTEMPTS, info.durationSec)
            val (ok1, err1) = runFFmpeg(
                FFmpegCommandBuilder.pass1(inputPath, preset, budget.videoBps, options, passLogPrefix, pixFmt, caps.av1Encoder, useFilter),
                "pass1",
            )
            if (!ok1) {
                stats.clear()
                return CompressResult.Failure("Pass 1 failed (attempt $videoAttempts): $err1")
            }
            val (ok2, err2) = runFFmpeg(
                FFmpegCommandBuilder.pass2(inputPath, outputPath, preset, budget.videoBps, budget.audioBps, options, passLogPrefix, pixFmt, caps.av1Encoder, useFilter),
                "pass2",
            )
            stats.clear()
            if (!ok2) return CompressResult.Failure("Pass 2 failed (attempt $videoAttempts): $err2")
            videoBytes = File(outputPath).length()
            val videoBudgetBytes = (budget.videoRatio * targetBytes).toLong()
            if (videoBytes <= videoBudgetBytes) break
            if (videoAttempts >= BitrateCalculator.MAX_ATTEMPTS) {
                cleanup(outputPath)
                return CompressResult.Failure("Video budget overshoot after $videoAttempts tries")
            }
            budget = budget.copy(videoBps = BitrateCalculator.reduceVideoBitrate(budget.videoBps))
        }

        // --- Audio stage: file already muxed in pass2; shrink audio on total overshoot ---
        var audioAttempts = 1
        while (true) {
            if (cancelled) {
                cleanup(outputPath)
                return CompressResult.Failure("Cancelled")
            }
            val total = File(outputPath).length()
            if (total <= targetBytes && !BitrateCalculator.shouldDiscardResult(total, info.sourceBytes, targetBytes)) {
                return CompressResult.Success(total, videoAttempts, audioAttempts)
            }
            if (audioAttempts >= BitrateCalculator.MAX_ATTEMPTS) {
                cleanup(outputPath)
                return CompressResult.Failure("Audio/total overshoot after $audioAttempts tries")
            }
            audioAttempts++
            onProgress(Progress("audio-retry", audioAttempts, BitrateCalculator.MAX_ATTEMPTS))
            budget = budget.copy(audioBps = BitrateCalculator.reduceAudioBitrate(budget.audioBps))
            // Re-run pass2 only (logs reused) with smaller audio bitrate.
            stats.track("audio", audioAttempts, BitrateCalculator.MAX_ATTEMPTS, info.durationSec)
            val (ok, err) = runFFmpeg(
                FFmpegCommandBuilder.pass2(inputPath, outputPath, preset, budget.videoBps, budget.audioBps, options, passLogPrefix, pixFmt, caps.av1Encoder, useFilter),
                "audio-retry",
            )
            stats.clear()
            if (!ok) return CompressResult.Failure("Audio retry failed (attempt $audioAttempts): $err")
        }
    }

    /**
     * Single-pass ABR loop: encode audio+video together, measure the real
     * output, reduce video bitrate 10% per retry. Same 5-try budget as 2-pass.
     */
    private fun singlePassLoop(
        inputPath: String,
        outputPath: String,
        info: VideoInfo,
        preset: Preset,
        options: AdvancedOptions,
        targetMb: Double,
        targetBytes: Long,
        budget: BitrateCalculator.Budget,
        passLogPrefix: String,
        pixFmt: String,
        av1Encoder: String,
        useFilter: Boolean,
        stats: StatsState,
        onProgress: (Progress) -> Unit,
    ): CompressResult {
        var bps = budget
        var attempts = 0
        while (true) {
            if (cancelled) {
                cleanup(outputPath)
                return CompressResult.Failure("Cancelled")
            }
            attempts++
            onProgress(Progress("single-pass", attempts, BitrateCalculator.MAX_ATTEMPTS))
            stats.track("encode", attempts, BitrateCalculator.MAX_ATTEMPTS, info.durationSec)
            val (ok, err) = runFFmpeg(
                FFmpegCommandBuilder.singlePass(inputPath, outputPath, preset, bps.videoBps, bps.audioBps, options, passLogPrefix, pixFmt, av1Encoder, useFilter),
                "single-pass",
            )
            stats.clear()
            if (!ok) return CompressResult.Failure("Encode failed (attempt $attempts): $err")
            val total = File(outputPath).length()
            if (total <= targetBytes && !BitrateCalculator.shouldDiscardResult(total, info.sourceBytes, targetBytes)) {
                return CompressResult.Success(total, attempts, 0, "single-pass")
            }
            if (attempts >= BitrateCalculator.MAX_ATTEMPTS) {
                cleanup(outputPath)
                return CompressResult.Failure("Size overshoot after $attempts tries")
            }
            bps = bps.copy(videoBps = BitrateCalculator.reduceVideoBitrate(bps.videoBps))
        }
    }

    private fun runFFmpeg(args: List<String>, tag: String): Pair<Boolean, String> {
        if (cancelled) return false to "cancelled"
        val session = FFmpegKit.execute(args.joinToString(" "))
        val ok = ReturnCode.isSuccess(session.returnCode)
        val tail = ((session.output ?: "") + "\n" + (session.failStackTrace ?: ""))
            .replace(Regex("\\s+"), " ").trim().takeLast(280)
        Log.e("ViforaFF", "$tag rc=${session.returnCode} cmd=${args.joinToString(" ")} out=$tail")
        return ok to tail.ifEmpty { "rc=${session.returnCode}" }
    }

    private fun cleanup(path: String) = runCatching { File(path).delete() }
}
