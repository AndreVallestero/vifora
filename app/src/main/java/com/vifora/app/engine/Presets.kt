package com.vifora.app.engine

/** Encoding presets. Names match mockup: Compatibility / Discord / Max Quality. */
enum class Preset(
    val videoCodec: String,
    val audioCodec: String,
    val containerExt: String,
) {
    COMPATIBILITY(
        videoCodec = "libx264",
        audioCodec = "libmp3lame",
        containerExt = "mp4",
    ),
    DISCORD(
        videoCodec = "libx265",
        audioCodec = "aac",
        containerExt = "mp4",
    ),
    MAX_QUALITY(
        videoCodec = "libsvtav1",
        audioCodec = "libopus",
        containerExt = "webm",
    );
}
