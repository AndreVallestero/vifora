package com.vifora.app.engine

data class CropRect(val w: Int, val h: Int, val x: Int, val y: Int)

data class TrimRange(val startSec: Double, val endSec: Double) {
    val durationSec: Double get() = (endSec - startSec).coerceAtLeast(0.0)
}

data class AdvancedOptions(
    val stillLowMotion: Boolean = false,
    val denoise: Boolean = false,
    val crop: CropRect? = null,
    val trim: TrimRange? = null,
    /** 1..100 percent. No upscale. */
    val scalePercent: Int = 100,
    val grayscale: Boolean = false,
    val monoAudio: Boolean = false,
    val removeAudio: Boolean = false,
    /** Framerate cap (fps). Never upscales: applied only when source fps exceeds it. */
    val frameRateFps: Int = 30,
)

/**
 * Centralized FFmpeg command builder.
 *
 * Always includes (per "follow designs as-is" decision):
 *   -sn -map_chapters -1 -map_metadata -1 -movflags +faststart
 * +faststart is only valid for MP4; it is omitted for WebM (Max Quality).
 */
object FFmpegCommandBuilder {
    const val PASS_LOG_PREFIX = "vifora_pass"

    fun videoFilter(options: AdvancedOptions, sourceFps: Double? = null): String? {
        val parts = mutableListOf<String>()
        options.crop?.let { parts += "crop=${it.w}:${it.h}:${it.x}:${it.y}" }
        val p = options.scalePercent.coerceIn(1, 100)
        if (p != 100) parts += "scale=iw*$p/100:-2"
        if (options.denoise) {
            parts += if (options.stillLowMotion) "atadenoise" else "hqdn3d"
        }
        if (options.grayscale) parts += "format=gray"
        // Framerate cap, applied last. Only when the source is actually faster;
        // never invents frames, and unknown source fps means conservative no-op.
        val cap = options.frameRateFps.coerceIn(1, 120)
        if (sourceFps != null && sourceFps > cap) parts += "fps=$cap"
        return parts.takeIf { it.isNotEmpty() }?.joinToString(",")
    }

    fun audioArgs(preset: Preset, audioBps: Long, options: AdvancedOptions): List<String> {
        if (options.removeAudio) return listOf("-an")
        val args = mutableListOf("-c:a", preset.audioCodec, "-b:a", audioBps.toString())
        if (options.monoAudio) {
            args += listOf("-ac", "1")
        }
        return args
    }

    private fun baseArgs(containerExt: String, includeFaststart: Boolean = true): List<String> {
        val args = mutableListOf("-sn", "-map_chapters", "-1", "-map_metadata", "-1")
        // +faststart is only valid for MP4, and meaningless on the pass-1 null muxer.
        if (includeFaststart && containerExt == "mp4") args += listOf("-movflags", "+faststart")
        return args
    }

    /**
     * @param pixFmt encoder-supported pixel format (probed at runtime; 10-bit
     *   requested, 8-bit fallback when the mobile build is 8-bit-only).
     * @param av1Encoder resolved AV1 encoder (libsvtav1 if present, else libaom-av1).
     */
    private fun videoCodecArgs(
        preset: Preset,
        videoBps: Long,
        still: Boolean,
        pixFmt: String,
        av1Encoder: String,
    ): List<String> {
        return when (preset) {
            Preset.COMPATIBILITY -> buildList {
                addAll(
                    listOf(
                        "-c:v", "libx264", "-preset", "veryfast",
                        "-b:v", videoBps.toString(), "-pix_fmt", pixFmt,
                    ),
                )
                // NOTE: stillimage tune exists for x264 only.
                if (still) addAll(listOf("-tune", "stillimage"))
            }
            // NOTE: x265 has no stillimage tune; still-toggle only switches denoise.
            Preset.DISCORD -> listOf(
                "-c:v", "libx265", "-preset", "veryfast",
                "-b:v", videoBps.toString(), "-pix_fmt", pixFmt,
            )
            Preset.MAX_QUALITY -> buildList {
                addAll(
                    listOf(
                        "-c:v", av1Encoder,
                        "-b:v", videoBps.toString(), "-pix_fmt", pixFmt,
                    ),
                )
                // NOTE: libaom's -still-picture is single-frame (AVIF) mode,
                // unsuitable for video; still-toggle only switches denoise.
                if (av1Encoder == "libaom-av1") addAll(listOf("-cpu-used", "4", "-row-mt", "1"))
                else addAll(listOf("-preset", "8"))
            }
        }
    }

    fun pass1(
        inputPath: String,
        preset: Preset,
        videoBps: Long,
        options: AdvancedOptions,
        passLogPrefix: String = PASS_LOG_PREFIX,
        pixFmt: String = "yuv420p",
        av1Encoder: String = "libaom-av1",
        useFilter: Boolean = true,
        probeSeconds: Double? = null,
        sourceFps: Double? = null,
    ): List<String> {
        val cmd = mutableListOf("-y")
        options.trim?.let {
            cmd += listOf("-ss", it.startSec.toString(), "-to", it.endSec.toString())
        }
        cmd += listOf("-i", inputPath)
        cmd += baseArgs(preset.containerExt, includeFaststart = false)
        if (useFilter) videoFilter(options, sourceFps)?.let { cmd += listOf("-vf", it) }
        cmd += videoCodecArgs(preset, videoBps, options.stillLowMotion, pixFmt, av1Encoder)
        cmd += listOf("-pass", "1", "-passlogfile", passLogPrefix)
        if (probeSeconds != null) cmd += listOf("-t", probeSeconds.toString())
        // Audio is skipped on pass 1; video-only null muxer keeps logs accurate.
        cmd += listOf("-an", "-f", "null", "-")
        return cmd
    }

    fun pass2(
        inputPath: String,
        outputPath: String,
        preset: Preset,
        videoBps: Long,
        audioBps: Long,
        options: AdvancedOptions,
        passLogPrefix: String = PASS_LOG_PREFIX,
        pixFmt: String = "yuv420p",
        av1Encoder: String = "libaom-av1",
        useFilter: Boolean = true,
        sourceFps: Double? = null,
    ): List<String> {
        val cmd = mutableListOf("-y")
        options.trim?.let {
            cmd += listOf("-ss", it.startSec.toString(), "-to", it.endSec.toString())
        }
        cmd += listOf("-i", inputPath)
        cmd += baseArgs(preset.containerExt)
        if (useFilter) videoFilter(options, sourceFps)?.let { cmd += listOf("-vf", it) }
        cmd += videoCodecArgs(preset, videoBps, options.stillLowMotion, pixFmt, av1Encoder)
        cmd += audioArgs(preset, audioBps, options)
        cmd += listOf("-pass", "2", "-passlogfile", passLogPrefix, outputPath)
        return cmd
    }

    /**
     * Single-pass ABR fallback for builds/encoders whose 2-pass is broken
     * (e.g. this build's libx265 rejects -pass 1). Same retry loop as 2-pass
     * (measure output, reduce video bitrate, retry) keeps size targeting.
     *
     * @param nullOutput probe mode: video-only null muxer instead of a file.
     */
    fun singlePass(
        inputPath: String,
        outputPath: String,
        preset: Preset,
        videoBps: Long,
        audioBps: Long,
        options: AdvancedOptions,
        passLogPrefix: String = PASS_LOG_PREFIX,
        pixFmt: String = "yuv420p",
        av1Encoder: String = "libaom-av1",
        useFilter: Boolean = true,
        probeSeconds: Double? = null,
        nullOutput: Boolean = false,
        sourceFps: Double? = null,
    ): List<String> {
        val cmd = mutableListOf("-y")
        options.trim?.let {
            cmd += listOf("-ss", it.startSec.toString(), "-to", it.endSec.toString())
        }
        cmd += listOf("-i", inputPath)
        cmd += baseArgs(preset.containerExt)
        if (useFilter) videoFilter(options, sourceFps)?.let { cmd += listOf("-vf", it) }
        cmd += videoCodecArgs(preset, videoBps, options.stillLowMotion, pixFmt, av1Encoder)
        if (probeSeconds != null) cmd += listOf("-t", probeSeconds.toString())
        if (nullOutput) {
            cmd += listOf("-an", "-f", "null", "-")
        } else {
            cmd += audioArgs(preset, audioBps, options)
            cmd += outputPath
        }
        return cmd
    }
}
