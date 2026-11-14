package com.vifora.app.engine

data class VideoInfo(
    val sourceBytes: Long,
    val durationSec: Double,
    val hasAudio: Boolean,
    val width: Int = 0,
    val height: Int = 0,
    /** Probed source framerate, null when unknown (fps cap then stays off). */
    val sourceFps: Double? = null,
)

object OutputManager {
    /**
     * Tokens: {source} basename without extension, {ext} preset container ext,
     * {source_dir} handled by caller (SAF tree uri). Default: {source}_vifora.{ext}
     */
    fun resolveFileName(template: String, sourceName: String, ext: String): String {
        val base = sourceName.substringAfterLast('/').substringBeforeLast('.')
        return template
            .replace("{source}", base.ifEmpty { "vifora" })
            .replace("{ext}", ext)
            .ifBlank { "${base.ifEmpty { "vifora" }}_vifora.$ext" }
    }
}
