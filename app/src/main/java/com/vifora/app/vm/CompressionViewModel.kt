package com.vifora.app.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import com.vifora.app.data.PersistedSettings
import com.vifora.app.data.SettingsStore
import com.vifora.app.engine.AdvancedOptions
import com.vifora.app.engine.CropRect
import com.vifora.app.engine.Preset
import com.vifora.app.engine.TrimRange
import com.vifora.app.engine.VideoInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ViforaUiState(
    val videoUri: Uri? = null,
    val videoName: String = "",
    val videoInfo: VideoInfo? = null,
    val preset: Preset = Preset.DISCORD,
    val options: AdvancedOptions = AdvancedOptions(),
    val targetMb: String = "10",
    val outputDirLabel: String = "{source_dir}",
    val fileTemplate: String = "{source}_vifora.{ext}",
    val status: String = "Idle",
    val progress: String = "",
    val progressFrac: Float? = null,
    val isWorking: Boolean = false,
)

class CompressionViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)

    private val _state: MutableStateFlow<ViforaUiState>
    val state: StateFlow<ViforaUiState>

    init {
        val s = store.load()
        _state = MutableStateFlow(
            ViforaUiState(
                preset = s.preset,
                targetMb = s.targetMb,
                fileTemplate = s.fileTemplate,
                options = AdvancedOptions(
                    stillLowMotion = s.stillLowMotion,
                    denoise = s.denoise,
                    scalePercent = s.scalePercent,
                    grayscale = s.grayscale,
                    monoAudio = s.monoAudio,
                    removeAudio = s.removeAudio,
                ),
                status = "Restored last settings",
            ),
        )
        state = _state
    }

    private fun persist() {
        val v = _state.value
        store.save(
            PersistedSettings(
                preset = v.preset,
                targetMb = v.targetMb,
                fileTemplate = v.fileTemplate,
                stillLowMotion = v.options.stillLowMotion,
                denoise = v.options.denoise,
                scalePercent = v.options.scalePercent,
                grayscale = v.options.grayscale,
                monoAudio = v.options.monoAudio,
                removeAudio = v.options.removeAudio,
            ),
        )
    }

    fun setVideo(uri: Uri, name: String, info: VideoInfo?) {
        _state.value = _state.value.copy(videoUri = uri, videoName = name, videoInfo = info)
    }

    fun clearVideo() {
        _state.value = _state.value.copy(
            videoUri = null,
            videoName = "",
            videoInfo = null,
            // Per-video edits don't carry over.
            options = _state.value.options.copy(crop = null, trim = null),
        )
    }

    fun setPreset(p: Preset) {
        _state.value = _state.value.copy(preset = p)
        persist()
    }

    fun setOptions(o: AdvancedOptions) {
        _state.value = _state.value.copy(options = o)
        persist()
    }

    fun setTargetMb(v: String) {
        _state.value = _state.value.copy(targetMb = v.filter { it.isDigit() || it == '.' }.take(8))
        persist()
    }

    fun setTemplate(v: String) {
        _state.value = _state.value.copy(fileTemplate = v.take(120))
        persist()
    }

    fun setOutputDirLabel(v: String) {
        _state.value = _state.value.copy(outputDirLabel = v)
    }

    fun setCrop(c: CropRect?) {
        _state.value = _state.value.copy(options = _state.value.options.copy(crop = c))
    }

    fun setTrim(t: TrimRange?) {
        _state.value = _state.value.copy(options = _state.value.options.copy(trim = t))
    }

    fun setStatus(status: String, progress: String = "", working: Boolean = _state.value.isWorking) {
        _state.value = _state.value.copy(
            status = status,
            progress = progress,
            // A fresh status resets the bar; live updates come via setProgress.
            progressFrac = null,
            isWorking = working,
        )
    }

    fun setProgress(frac: Float?, text: String) {
        _state.value = _state.value.copy(progress = text, progressFrac = frac)
    }
}
