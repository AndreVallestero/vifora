package com.vifora.app.ui

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.vifora.app.MainActivity
import com.vifora.app.engine.BitrateCalculator
import com.vifora.app.engine.CompressResult
import com.vifora.app.engine.CompressionEngine
import com.vifora.app.engine.CropRect
import com.vifora.app.engine.OutputManager
import com.vifora.app.engine.Preset
import com.vifora.app.engine.TrimRange
import com.vifora.app.engine.VideoInfo
import com.vifora.app.engine.VideoInspector
import com.vifora.app.vm.CompressionViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViforaScreen(vm: CompressionViewModel, activity: MainActivity) {
    val s by vm.state.collectAsState()
    var showCrop by remember { mutableStateOf(false) }
    var showTrim by remember { mutableStateOf(false) }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment ?: "video"
            var size = 0L
            runCatching {
                activity.contentResolver.openFileDescriptor(uri, "r")?.use { size = it.statSize }
            }
            val info = runCatching { VideoInspector.inspect(activity, uri, size) }.getOrNull()
            vm.setVideo(uri, name, info)
            val dur = info?.durationSec ?: 0.0
            val mb = if (size > 0) size / 1_048_576.0 else 0.0
            vm.setStatus(
                if (info == null) "Picked (probe failed)" else "Picked: ${"%.1f".format(mb)}MB, ${"%.0f".format(dur)}s",
            )
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Hero: preview of the selected video
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val selectedUri = s.videoUri
                if (selectedUri != null) {
                    VideoPreview(
                        uri = selectedUri,
                        aspect = s.videoInfo?.let { info ->
                            if (info.width > 0 && info.height > 0) {
                                info.width.toFloat() / info.height.toFloat()
                            } else {
                                null
                            }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (s.videoUri == null) {
                    Button(onClick = { pickVideo.launch("video/*") }) { Text("Tap To Select a Video") }
                } else {
                    Text(s.videoName.take(60))
                    Text(
                        s.videoInfo?.let {
                            "${it.width}x${it.height}, ${"%.0f".format(it.durationSec)}s, " +
                                (if (it.hasAudio) "audio" else "no-audio")
                        } ?: "metadata unavailable",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pickVideo.launch("video/*") }) { Text("Replace") }
                        OutlinedButton(onClick = { vm.clearVideo() }) { Text("Clear") }
                    }
                }
            }
        }

        // Presets
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Presets")
                Preset.entries.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = s.preset == p, onClick = { vm.setPreset(p) })
                        Text(
                            when (p) {
                                Preset.COMPATIBILITY -> "Compatibility (H264 + MP3, mp4)"
                                Preset.DISCORD -> "Discord (H265 + AAC, mp4)"
                                Preset.MAX_QUALITY -> "Max Quality (AV1 + Opus, webm)"
                            },
                        )
                    }
                }
            }
        }

        // Advanced
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Advanced")
                CheckRow("Optimize for very low motion / still", s.options.stillLowMotion) {
                    vm.setOptions(s.options.copy(stillLowMotion = it))
                }
                CheckRow("Crop", s.options.crop != null) {
                    if (it) showCrop = true else vm.setCrop(null)
                }
                CheckRow("Denoise (${if (s.options.stillLowMotion) "atadenoise" else "hqdn3d"})", s.options.denoise) {
                    vm.setOptions(s.options.copy(denoise = it))
                }
                CheckRow("Trim", s.options.trim != null) {
                    if (it) showTrim = true else vm.setTrim(null)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Resolution scale (1-100): ")
                    var v by remember(s.options.scalePercent) {
                        mutableStateOf(s.options.scalePercent.toString())
                    }
                    OutlinedTextField(
                        value = v,
                        onValueChange = {
                            v = it.filter(Char::isDigit).take(3)
                            v.toIntOrNull()?.let { n ->
                                vm.setOptions(s.options.copy(scalePercent = n.coerceIn(1, 100)))
                            }
                        },
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                CheckRow("Grayscale", s.options.grayscale) {
                    vm.setOptions(s.options.copy(grayscale = it))
                }
                CheckRow("Single channel audio", s.options.monoAudio) {
                    vm.setOptions(s.options.copy(monoAudio = it))
                }
                CheckRow("Remove audio", s.options.removeAudio) {
                    vm.setOptions(s.options.copy(removeAudio = it))
                }
                OutlinedTextField(
                    value = s.fileTemplate,
                    onValueChange = { vm.setTemplate(it) },
                    label = { Text("Output file name {source}_vifora.{ext}") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Output directory: ${s.outputDirLabel} (app cache in v1)")
            }
        }

        // Bottom dock
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(12.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = s.targetMb,
                    onValueChange = { vm.setTargetMb(it) },
                    label = { Text("size (MB)") },
                    modifier = Modifier.weight(1f),
                )
                Button(
                    enabled = s.videoUri != null && !s.isWorking,
                    onClick = { startCompress(vm, activity) },
                ) { Text("Compress") }
            }
        }

        Text("Status: ${s.status}")
        if (s.progress.isNotBlank()) Text(s.progress)
        if (s.isWorking) {
            Spacer(Modifier.height(4.dp))
            val frac = s.progressFrac
            if (frac != null) {
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showCrop) {
        ModalBottomSheet(onDismissRequest = { showCrop = false }) {
            CropSheet(
                onSave = {
                    vm.setCrop(it)
                    showCrop = false
                },
                onClear = {
                    vm.setCrop(null)
                    showCrop = false
                },
            )
        }
    }
    if (showTrim) {
        ModalBottomSheet(onDismissRequest = { showTrim = false }) {
            TrimSheet(
                durationSec = s.videoInfo?.durationSec,
                onSave = {
                    vm.setTrim(it)
                    showTrim = false
                },
                onClear = {
                    vm.setTrim(null)
                    showTrim = false
                },
            )
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

private fun startCompress(vm: CompressionViewModel, activity: MainActivity) {
    val st = vm.state.value
    val uri = st.videoUri ?: return
    val targetMb = st.targetMb.toDoubleOrNull() ?: return
    val info = st.videoInfo ?: return
    val targetBytes = (targetMb * 1_048_576.0).toLong()
    if (BitrateCalculator.shouldBlockUpfront(info.sourceBytes, targetBytes)) {
        vm.setStatus("Refused: target >= source (guard)")
        return
    }
    // Copy content uri -> cache input (FFmpegKit needs file paths; done off UI thread).
    val inputFile = java.io.File(activity.cacheDir, "vifora_input")
    val outName = OutputManager.resolveFileName(st.fileTemplate, st.videoName, st.preset.containerExt)
    val outputFile = java.io.File(activity.cacheDir, outName.ifBlank { "vifora_out.${st.preset.containerExt}" })
    val snapshot = st
    vm.setStatus("Copying input…", working = true)
    // No ForegroundService: plain background thread keeps targetSdk-36
    // FGS-type enforcement out of the picture. App must stay foreground.
    Thread {
        runCatching {
            activity.contentResolver.openInputStream(uri)?.use { ins ->
                inputFile.outputStream().use { ins.copyTo(it) }
            }
        }
        val passLog = java.io.File(activity.cacheDir, "vifora_pass").absolutePath
        val engine = CompressionEngine()
        val res = engine.execute(
            inputFile.absolutePath,
            outputFile.absolutePath,
            VideoInfo(info.sourceBytes, info.durationSec, info.hasAudio),
            snapshot.preset,
            snapshot.options,
            targetMb,
            passLog,
        ) { p ->
            val label = "${p.stage} ${p.attempt}/${p.maxAttempts}" +
                (if (p.detail.isNotBlank()) " ${p.detail}" else "")
            vm.setProgress(p.frac, label)
        }
        when (res) {
            is CompressResult.Success -> {
                val publicUri = publishToGallery(activity, outputFile, st.preset.containerExt)
                vm.setStatus(
                    "Done (${res.mode}): ${res.outputBytes / 1_048_576}MB " +
                        "(video tries ${res.videoAttempts}, audio tries ${res.audioAttempts}) -> " +
                        (publicUri ?: outputFile.absolutePath),
                    working = false,
                )
            }
            is CompressResult.Failure -> vm.setStatus("Failed: ${res.reason}", working = false)
        }
    }.start()
}

@Composable
fun CropSheet(onSave: (CropRect) -> Unit, onClear: () -> Unit) {
    var w by remember { mutableStateOf("1280") }
    var h by remember { mutableStateOf("720") }
    var x by remember { mutableStateOf("0") }
    var y by remember { mutableStateOf("0") }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Crop (rectangle over preview constrains it)")
        OutlinedTextField(w, { w = it.filter(Char::isDigit) }, label = { Text("w") })
        OutlinedTextField(h, { h = it.filter(Char::isDigit) }, label = { Text("h") })
        OutlinedTextField(x, { x = it.filter(Char::isDigit) }, label = { Text("x") })
        OutlinedTextField(y, { y = it.filter(Char::isDigit) }, label = { Text("y") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                onSave(CropRect(w.toIntOrNull() ?: 0, h.toIntOrNull() ?: 0, x.toIntOrNull() ?: 0, y.toIntOrNull() ?: 0))
            }) { Text("Save") }
            OutlinedButton(onClick = onClear) { Text("Clear") }
        }
    }
}

@Composable
fun TrimSheet(durationSec: Double?, onSave: (TrimRange) -> Unit, onClear: () -> Unit) {
    var start by remember { mutableStateOf("0") }
    var end by remember { mutableStateOf(durationSec?.toString() ?: "") }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Trim (start/end seconds)")
        OutlinedTextField(start, { start = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("start") })
        OutlinedTextField(end, { end = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("end") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                onSave(TrimRange(start.toDoubleOrNull() ?: 0.0, end.toDoubleOrNull() ?: 0.0))
            }) { Text("Save") }
            OutlinedButton(onClick = onClear) { Text("Clear") }
        }
    }
}

/**
 * Copies a finished encode into shared storage (Movies/Vifora) via MediaStore
 * so it shows up in Files/gallery recents. Returns the public uri string.
 */
private fun publishToGallery(
    activity: MainActivity,
    file: java.io.File,
    containerExt: String,
): String? {
    return runCatching {
        val mime = if (containerExt == "webm") "video/webm" else "video/mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Vifora")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val resolver = activity.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        uri.toString()
    }.getOrNull()
}

/**
 * Inline preview of the selected video with playback controls.
 * Player is released when the selection changes or the UI goes away.
 */
@Composable
private fun VideoPreview(uri: android.net.Uri, aspect: Float?) {
    val appContext = LocalContext.current.applicationContext
    val player = remember(uri) {
        ExoPlayer.Builder(appContext).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = false
        }
    }
    DisposableEffect(uri) {
        onDispose { player.release() }
    }
    val previewModifier = if (aspect != null && aspect > 0) {
        Modifier.fillMaxWidth().aspectRatio(aspect)
    } else {
        Modifier.fillMaxWidth().heightIn(min = 200.dp)
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
            }
        },
        modifier = previewModifier,
    )
}
