package com.xeidy.tjrclipper

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Context get() = getApplication()
    private val prefs = app.getSharedPreferences("tjr", Context.MODE_PRIVATE)

    var apiKey by mutableStateOf(prefs.getString("apiKey", "") ?: "")
        private set
    var model by mutableStateOf(prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL)
        private set

    var videoUri by mutableStateOf<Uri?>(null)
        private set
    var videoName by mutableStateOf("")
        private set
    var subtitleUri by mutableStateOf<Uri?>(null)
        private set
    var subtitleName by mutableStateOf("")
        private set

    val styles = mutableStateListOf<CaptionStyle>().apply {
        addAll(Presets.all)
        prefs.getString("customStyle", null)?.let { s ->
            runCatching { add(CaptionStyle.fromJson(JSONObject(s))) }
        }
    }
    var styleIndex by mutableIntStateOf(prefs.getInt("styleIndex", 0).coerceIn(0, styles.size - 1))
        private set
    val style: CaptionStyle get() = styles[styleIndex.coerceIn(0, styles.size - 1)]

    var lengthIndex by mutableIntStateOf(prefs.getInt("lengthIndex", 0))
    var extra by mutableStateOf(prefs.getString("extra", "") ?: "")

    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set
    var progress by mutableFloatStateOf(-1f)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var words: List<Word> = emptyList()
    val clips = mutableStateListOf<ClipIdea>()
    private var job: Job? = null

    companion object {
        const val DEFAULT_MODEL = "claude-sonnet-5-5"
        val LENGTHS = listOf(
            "15–30 dtk" to (15 to 30),
            "30–60 dtk" to (30 to 60),
            "45–90 dtk" to (45 to 90)
        )
    }

    fun saveSettings(key: String, modelName: String) {
        apiKey = key.trim()
        model = modelName.trim().ifBlank { DEFAULT_MODEL }
        prefs.edit().putString("apiKey", apiKey).putString("model", model).apply()
    }

    fun selectStyle(i: Int) {
        styleIndex = i
        prefs.edit().putInt("styleIndex", i).apply()
    }

    fun saveOptions() {
        prefs.edit().putInt("lengthIndex", lengthIndex).putString("extra", extra).apply()
    }

    private fun displayName(uri: Uri): String =
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "video"

    private fun keepPermission(uri: Uri) {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun setVideo(uri: Uri) {
        keepPermission(uri)
        videoUri = uri
        videoName = displayName(uri)
        words = emptyList()
        clips.clear()
        error = null
    }

    fun setSubtitle(uri: Uri?) {
        subtitleUri = uri
        subtitleName = uri?.let { keepPermission(it); displayName(it) } ?: ""
        words = emptyList()
    }

    fun dismissError() {
        error = null
    }

    fun cancel() {
        job?.cancel()
    }

    private fun client() = ClaudeClient(apiKey, model)

    private fun launchTask(block: suspend () -> Unit) {
        if (busy) return
        job = viewModelScope.launch {
            busy = true
            error = null
            progress = -1f
            try {
                block()
                status = ""
            } catch (e: CancellationException) {
                status = "Dibatalkan."
            } catch (e: Throwable) {
                status = ""
                error = e.message ?: e.toString()
            } finally {
                busy = false
                progress = -1f
            }
        }
    }

    fun analyzeSample(uri: Uri) = launchTask {
        status = "Claude sedang membaca gaya video contoh…"
        val custom = withContext(Dispatchers.IO) { StyleAnalyzer.analyze(ctx, client(), uri) }
        val existing = styles.indexOfFirst { it.name == custom.name }
        if (existing >= 0) styles[existing] = custom else styles.add(custom)
        prefs.edit().putString("customStyle", custom.toJson().toString()).apply()
        selectStyle(styles.indexOfFirst { it.name == custom.name })
    }

    private fun cacheFile(uri: Uri): File {
        val dir = File(ctx.filesDir, "transcripts").apply { mkdirs() }
        return File(dir, "${uri.toString().hashCode()}.json")
    }

    private suspend fun obtainWords(uri: Uri): List<Word> {
        if (words.isNotEmpty()) return words
        val sub = subtitleUri
        if (sub != null) {
            status = "Membaca file subtitle…"
            val text = withContext(Dispatchers.IO) {
                ctx.contentResolver.openInputStream(sub)?.bufferedReader()?.use { it.readText() }
            } ?: throw IOException("File subtitle tidak bisa dibuka.")
            val parsed = SubtitleParser.parse(text)
            if (parsed.isEmpty()) throw IOException("File subtitle kosong atau formatnya tidak dikenal (pakai .srt atau .vtt).")
            words = parsed
            return parsed
        }
        val cache = cacheFile(uri)
        if (cache.exists()) {
            runCatching { WordStore.fromJson(cache.readText()) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let {
                words = it
                return it
            }
        }
        val t = Transcriber(ctx)
        val ctxJob = coroutineContext
        withContext(Dispatchers.IO) {
            if (!t.modelReady()) {
                status = "Mengunduh model suara (sekali saja, ±40 MB)…"
                t.downloadModel({ progress = it }, { ctxJob.isActive })
            }
            status = "Mentranskrip suara di HP… Biarkan aplikasi tetap terbuka."
            progress = 0f
            val result = t.transcribe(uri, { progress = it }, { ctxJob.isActive })
            if (result.isEmpty()) throw IOException("Tidak ada ucapan yang terdengar di video ini.")
            cache.writeText(WordStore.toJson(result))
            words = result
        }
        return words
    }

    fun findClips() = launchTask {
        val uri = videoUri ?: throw IOException("Pilih video sumber dulu.")
        if (apiKey.isBlank()) throw IOException("Isi kunci API Claude dulu di bagian Pengaturan.")
        saveOptions()
        val w = obtainWords(uri)
        status = "Claude sedang memilih momen paling berpotensi viral…"
        progress = -1f
        val (minLen, maxLen) = LENGTHS[lengthIndex.coerceIn(0, LENGTHS.size - 1)].second
        val ideas = withContext(Dispatchers.IO) { ClipFinder.find(client(), w, minLen, maxLen, extra) }
        clips.clear()
        clips.addAll(ideas)
    }

    fun updateClip(id: Int, change: (ClipIdea) -> ClipIdea) {
        val i = clips.indexOfFirst { it.id == id }
        if (i >= 0) clips[i] = change(clips[i])
    }

    fun renderSelected() = launchTask {
        val uri = videoUri ?: throw IOException("Pilih video sumber dulu.")
        val w = obtainWords(uri)
        val chosen = clips.filter { it.selected }
        if (chosen.isEmpty()) throw IOException("Centang minimal satu clip untuk dirender.")
        val renderer = ClipRenderer(ctx)
        val st = style
        chosen.forEachIndexed { n, clip ->
            status = "Merender clip ${n + 1} dari ${chosen.size}: ${clip.title}"
            progress = 0f
            updateClip(clip.id) { it.copy(renderStatus = "Merender…", outputUri = null) }
            try {
                val out = renderer.render(uri, clip, w, st) { progress = it }
                updateClip(clip.id) { it.copy(renderStatus = "Tersimpan di galeri (Movies/TJR Clipper)", outputUri = out.toString()) }
            } catch (e: CancellationException) {
                updateClip(clip.id) { it.copy(renderStatus = "Dibatalkan") }
                throw e
            } catch (e: Exception) {
                updateClip(clip.id) { it.copy(renderStatus = "Gagal: ${e.message}") }
            }
        }
    }
}
