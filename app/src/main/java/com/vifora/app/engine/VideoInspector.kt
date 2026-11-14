package com.vifora.app.engine

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit

object VideoInspector {
    fun inspect(context: Context, uri: Uri, sourceBytes: Long): VideoInfo {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            val durationMs = mmr.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION,
            )?.toLongOrNull() ?: 0L
            val width = mmr.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
            )?.toIntOrNull() ?: 0
            val height = mmr.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
            )?.toIntOrNull() ?: 0
            val hasAudio = mmr.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO,
            ) == "yes"
            VideoInfo(
                sourceBytes = sourceBytes,
                durationSec = (durationMs / 1000.0).coerceAtLeast(0.1),
                hasAudio = hasAudio && !false,
                width = width,
                height = height,
            )
        } finally {
            runCatching { mmr.release() }
        }
    }

    /**
     * Parses the source framerate from `ffmpeg -i` output (MediaMetadataRetriever
     * has no reliable fps key). Returns null when it cannot be determined.
     */
    fun probeSourceFps(inputPath: String): Double? = runCatching {
        val session = FFmpegKit.execute("-i $inputPath")
        val out = session.output ?: return null
        val videoSection = out.substringAfter("Video:", missingDelimiterValue = "")
        if (videoSection.isEmpty()) return null
        Regex("""(\d+(?:\.\d+)?)\s+fps""").find(videoSection)?.groupValues
            ?.getOrNull(1)?.toDoubleOrNull()?.takeIf { it > 0 }
    }.getOrNull()
}
