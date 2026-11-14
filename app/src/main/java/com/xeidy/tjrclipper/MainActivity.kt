package com.xeidy.tjrclipper

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppScreen(viewModel())
                }
            }
        }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(0xFFFFE500),
            onPrimary = Color(0xFF14213D),
            background = Color(0xFF0F1626),
            surface = Color(0xFF172036),
            surfaceVariant = Color(0xFF1E2945)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF1F4FD8),
            onPrimary = Color.White,
            background = Color(0xFFF3F1EA),
            surface = Color(0xFFFFFDF7),
            surfaceVariant = Color(0xFFECE8DC)
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun AppScreen(vm: AppViewModel) {
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.setVideo(it) }
    }
    val pickSubtitle = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.setSubtitle(it) }
    }
    val pickSample = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.analyzeSample(it) }
    }

    val view = LocalView.current
    DisposableEffect(vm.busy) {
        view.keepScreenOn = vm.busy
        onDispose { }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text("TJR Clipper", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                Text(
                    "Pilih video TJR, Claude cari momen viral, aplikasi memotong dan memberi subtitle.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item { SettingsCard(vm) }
        item {
            SourceCard(
                vm,
                onPickVideo = { pickVideo.launch(arrayOf("video/*")) },
                onPickSubtitle = { pickSubtitle.launch(arrayOf("*/*")) }
            )
        }
        item { StyleCard(vm, onPickSample = { pickSample.launch(arrayOf("video/*")) }) }
        item { OptionsCard(vm) }
        item { ActionCard(vm) }
        if (vm.clips.isNotEmpty()) {
            item {
                Text(
                    "Momen pilihan Claude (${vm.clips.size})",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            items(vm.clips, key = { it.id }) { clip -> ClipCard(vm, clip) }
            item {
                val n = vm.clips.count { it.selected }
                Button(
                    onClick = { vm.renderSelected() },
                    enabled = !vm.busy && n > 0,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Render $n clip dengan gaya \"${vm.style.name}\"") }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun SettingsCard(vm: AppViewModel) {
    var open by remember { mutableStateOf(vm.apiKey.isBlank()) }
    var key by remember { mutableStateOf(vm.apiKey) }
    var model by remember { mutableStateOf(vm.model) }
    Section("Pengaturan Claude") {
        if (!open) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Kunci API tersimpan · model ${vm.model}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = { open = true }) { Text("Ubah") }
            }
        } else {
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("Kunci API Claude (sk-ant-…)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("Nama model") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Kunci hanya disimpan di HP ini dan dikirim langsung ke Anthropic.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = { vm.saveSettings(key, model); open = key.isBlank() },
                enabled = key.isNotBlank()
            ) { Text("Simpan") }
        }
    }
}

@Composable
private fun SourceCard(vm: AppViewModel, onPickVideo: () -> Unit, onPickSubtitle: () -> Unit) {
    Section("1. Video sumber") {
        if (vm.videoUri != null) {
            Text(vm.videoName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        } else {
            Text(
                "Video TJR dari YouTube, Kick, atau Instagram yang sudah ada di HP (20–60 menit paling pas).",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Button(onClick = onPickVideo, enabled = !vm.busy) {
            Text(if (vm.videoUri == null) "Pilih video" else "Ganti video")
        }
        Text(
            "Opsional: punya file subtitle .srt atau .vtt videonya? Pakai itu supaya tidak perlu transkrip (jauh lebih cepat).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPickSubtitle, enabled = !vm.busy) {
                Text(if (vm.subtitleUri == null) "Pilih subtitle" else "Ganti subtitle")
            }
            if (vm.subtitleUri != null) {
                Text(vm.subtitleName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { vm.setSubtitle(null) }, enabled = !vm.busy) { Text("Hapus") }
            }
        }
    }
}

@Composable
private fun StyleCard(vm: AppViewModel, onPickSample: () -> Unit) {
    Section("2. Gaya editing") {
        val style = vm.style
        val preview = remember(style) { stylePreview(style).asImageBitmap() }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(
                bitmap = preview,
                contentDescription = "Contoh tampilan gaya ${style.name}",
                modifier = Modifier
                    .width(120.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
            Column(modifier = Modifier.weight(1f)) {
                vm.styles.forEachIndexed { i, s ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !vm.busy) { vm.selectStyle(i) }
                    ) {
                        RadioButton(selected = i == vm.styleIndex, onClick = { vm.selectStyle(i) }, enabled = !vm.busy)
                        Text(s.name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (style.note.isNotBlank()) {
            Text(
                "${style.note} Bingkai: ${style.framing.label.lowercase()}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedButton(onClick = onPickSample, enabled = !vm.busy && vm.apiKey.isNotBlank()) {
            Text("Tiru gaya dari video contoh")
        }
        Text(
            "Pilih satu clip pendek yang editannya Anda suka. Claude melihat beberapa gambarnya lalu menyetel subtitle dan bingkai yang mirip.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun OptionsCard(vm: AppViewModel) {
    Section("3. Pilihan clip") {
        Text("Panjang tiap clip", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppViewModel.LENGTHS.forEachIndexed { i, (label, _) ->
                FilterChip(
                    selected = vm.lengthIndex == i,
                    onClick = { vm.lengthIndex = i },
                    label = { Text(label) },
                    enabled = !vm.busy
                )
            }
        }
        OutlinedTextField(
            value = vm.extra,
            onValueChange = { vm.extra = it },
            label = { Text("Instruksi tambahan untuk Claude (opsional)") },
            placeholder = { Text("mis. fokus momen lucu dan reaksi saat profit") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !vm.busy
        )
    }
}

@Composable
private fun ActionCard(vm: AppViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = { vm.findClips() },
            enabled = !vm.busy && vm.videoUri != null && vm.apiKey.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Cari momen viral") }
        if (vm.videoUri == null || vm.apiKey.isBlank()) {
            Text(
                "Isi kunci API dan pilih video dulu.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (vm.busy) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(vm.status, style = MaterialTheme.typography.bodyMedium)
                    if (vm.progress >= 0f) {
                        LinearProgressIndicator(progress = { vm.progress }, modifier = Modifier.fillMaxWidth())
                        Text("${(vm.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    TextButton(onClick = { vm.cancel() }) { Text("Batalkan") }
                }
            }
        } else if (vm.status.isNotBlank()) {
            Text(vm.status, style = MaterialTheme.typography.bodySmall)
        }
        vm.error?.let { msg ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(msg, color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(onClick = { vm.dismissError() }) { Text("Tutup") }
                }
            }
        }
    }
}

@Composable
private fun ClipCard(vm: AppViewModel, clip: ClipIdea) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = clip.selected,
                    onCheckedChange = { c -> vm.updateClip(clip.id) { it.copy(selected = c) } },
                    enabled = !vm.busy
                )
                Text(
                    clip.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${clip.score}/10",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Black
                )
            }
            Text(
                "${formatTime(clip.start)} – ${formatTime(clip.end)} · ${(clip.end - clip.start).toInt()} detik",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (clip.reason.isNotBlank()) Text(clip.reason, style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = clip.hook,
                onValueChange = { v -> vm.updateClip(clip.id) { it.copy(hook = v) } },
                label = { Text("Hook di atas video (3 detik pertama)") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !vm.busy
            )
            OutlinedTextField(
                value = clip.caption,
                onValueChange = { v -> vm.updateClip(clip.id) { it.copy(caption = v) } },
                label = { Text("Caption postingan") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !vm.busy
            )
            clip.renderStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(clip.caption)) }) { Text("Salin caption") }
                clip.outputUri?.let { out ->
                    val uri = Uri.parse(out)
                    TextButton(onClick = {
                        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        runCatching { ctx.startActivity(i) }
                    }) { Text("Putar") }
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(clip.caption))
                        val send = Intent(Intent.ACTION_SEND).setType("video/mp4")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .putExtra(Intent.EXTRA_TEXT, clip.caption)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        runCatching { ctx.startActivity(Intent.createChooser(send, "Bagikan clip")) }
                    }) { Text("Bagikan") }
                }
            }
        }
    }
    Spacer(modifier = Modifier.padding(0.dp))
}
