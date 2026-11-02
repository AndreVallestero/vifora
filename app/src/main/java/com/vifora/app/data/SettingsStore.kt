package com.vifora.app.data

import android.content.Context
import com.vifora.app.engine.Preset

/** Settings worth remembering between launches (per-video state is excluded). */
data class PersistedSettings(
    val preset: Preset = Preset.DISCORD,
    val targetMb: String = "10",
    val fileTemplate: String = "{source}_vifora.{ext}",
    val stillLowMotion: Boolean = false,
    val denoise: Boolean = false,
    val scalePercent: Int = 100,
    val grayscale: Boolean = false,
    val monoAudio: Boolean = false,
    val removeAudio: Boolean = false,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("vifora_settings", Context.MODE_PRIVATE)

    fun load(): PersistedSettings = PersistedSettings(
        preset = runCatching {
            Preset.valueOf(prefs.getString(KEY_PRESET, null) ?: "")
        }.getOrDefault(Preset.DISCORD),
        targetMb = prefs.getString(KEY_TARGET_MB, "10") ?: "10",
        fileTemplate = prefs.getString(KEY_TEMPLATE, "{source}_vifora.{ext}")
            ?: "{source}_vifora.{ext}",
        stillLowMotion = prefs.getBoolean(KEY_STILL, false),
        denoise = prefs.getBoolean(KEY_DENOISE, false),
        scalePercent = prefs.getInt(KEY_SCALE, 100).coerceIn(1, 100),
        grayscale = prefs.getBoolean(KEY_GRAY, false),
        monoAudio = prefs.getBoolean(KEY_MONO, false),
        removeAudio = prefs.getBoolean(KEY_NO_AUDIO, false),
    )

    fun save(s: PersistedSettings) {
        prefs.edit()
            .putString(KEY_PRESET, s.preset.name)
            .putString(KEY_TARGET_MB, s.targetMb)
            .putString(KEY_TEMPLATE, s.fileTemplate)
            .putBoolean(KEY_STILL, s.stillLowMotion)
            .putBoolean(KEY_DENOISE, s.denoise)
            .putInt(KEY_SCALE, s.scalePercent)
            .putBoolean(KEY_GRAY, s.grayscale)
            .putBoolean(KEY_MONO, s.monoAudio)
            .putBoolean(KEY_NO_AUDIO, s.removeAudio)
            .apply()
    }

    companion object {
        private const val KEY_PRESET = "preset"
        private const val KEY_TARGET_MB = "targetMb"
        private const val KEY_TEMPLATE = "template"
        private const val KEY_STILL = "still"
        private const val KEY_DENOISE = "denoise"
        private const val KEY_SCALE = "scale"
        private const val KEY_GRAY = "gray"
        private const val KEY_MONO = "mono"
        private const val KEY_NO_AUDIO = "noAudio"
    }
}
