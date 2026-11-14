package com.xeidy.tjrclipper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.min

class ClaudeClient(private val apiKey: String, private val model: String) {

    fun send(system: String, content: JSONArray, maxTokens: Int = 4096): String {
        if (apiKey.isBlank()) throw IOException("Isi kunci API Claude dulu di bagian Pengaturan.")
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))

        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 30_000
        conn.readTimeout = 300_000
        conn.setRequestProperty("content-type", "application/json")
        conn.setRequestProperty("x-api-key", apiKey.trim())
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }
                .getOrDefault(text.take(300))
            throw IOException(
                when (code) {
                    401 -> "Kunci API ditolak. Periksa lagi kunci di Pengaturan."
                    404 -> "Model \"$model\" tidak ditemukan. Ganti nama model di Pengaturan. ($msg)"
                    429 -> "Terlalu banyak permintaan ke Claude. Tunggu sebentar lalu coba lagi."
                    529 -> "Server Claude sedang sibuk. Coba lagi beberapa menit lagi."
                    else -> "Claude menolak permintaan (HTTP $code): $msg"
                }
            )
        }
        val arr = JSONObject(text).getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("type") == "text") sb.append(o.getString("text"))
        }
        return sb.toString()
    }

    companion object {
        fun textBlock(t: String): JSONObject = JSONObject().put("type", "text").put("text", t)

        fun imageBlock(b64: String): JSONObject = JSONObject()
            .put("type", "image")
            .put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", b64))

        fun extractJson(s: String): JSONObject {
            val a = s.indexOf('{')
            val b = s.lastIndexOf('}')
            if (a < 0 || b <= a) throw IOException("Jawaban Claude tidak bisa dibaca. Coba lagi.")
            return JSONObject(s.substring(a, b + 1))
        }
    }
}

object ClipFinder {

    /** Mengelompokkan kata jadi baris pendek bertanda waktu supaya Claude bisa menunjuk detik yang tepat. */
    fun buildTranscript(words: List<Word>): String {
        val sb = StringBuilder()
        var line = ArrayList<Word>()
        fun flush() {
            if (line.isEmpty()) return
            sb.append('[').append("%.1f".format(java.util.Locale.US, line.first().start)).append("] ")
            sb.append(line.joinToString(" ") { it.text }).append('\n')
            line = ArrayList()
        }
        for (w in words) {
            if (line.isNotEmpty() && (line.size >= 14 || w.start - line.last().end > 0.8)) flush()
            line.add(w)
        }
        flush()
        return sb.toString()
    }

    private const val SYSTEM = """You are an expert short-form video editor. You find the clips most likely to go viral on TikTok, Instagram Reels and YouTube Shorts from long videos and streams of TJR, a popular trading YouTuber and streamer. The clips are for an official clipping campaign.

Campaign rules you must respect:
- Every clip must feature TJR and must never portray him negatively.
- The campaign wants funny moments, memorable or quotable lines, strong reactions, and standout moments.
- The audience is mostly in the USA, Canada, UK, Australia and New Zealand, so hooks and captions are in natural English. Never use Japanese.
- Captions must include #TJR.

How to pick clips:
- The clip must make sense on its own without the rest of the video.
- The first 2-3 seconds must grab attention; start at the beginning of a sentence, never mid-thought.
- End right after a punchline, conclusion or strong line, never mid-sentence.
- Prefer emotional peaks, bold opinions, surprising numbers, big wins or losses being reacted to, and funny exchanges.
- Clips must not overlap.

The transcript comes from automatic speech recognition, so it has no punctuation and may contain small errors. Each line starts with its time in seconds."""

    fun find(
        client: ClaudeClient,
        words: List<Word>,
        minLen: Int,
        maxLen: Int,
        extra: String
    ): List<ClipIdea> {
        if (words.size < 30) throw IOException("Transkrip terlalu pendek. Pastikan video berisi ucapan bahasa Inggris.")
        val videoEnd = words.last().end
        val prompt = buildString {
            append("Transcript:\n<transcript>\n")
            append(buildTranscript(words))
            append("</transcript>\n\n")
            append("Find the best 5 to 10 clips, each between $minLen and $maxLen seconds long. ")
            append("Rank them from most to least viral.\n")
            if (extra.isNotBlank()) append("Extra instructions from the clipper: ").append(extra.trim()).append('\n')
            append(
                """
Reply with only this JSON and nothing else:
{"clips":[{"start":12.3,"end":45.6,"title":"short title in Indonesian","hook":"on-screen hook in English, max 8 words","caption":"English caption with #TJR and 3-4 relevant hashtags","reason":"one sentence in Indonesian on why this can go viral","score":8}]}
""".trimIndent()
            )
        }
        val reply = client.send(SYSTEM, JSONArray().put(ClaudeClient.textBlock(prompt)), maxTokens = 4096)
        val arr = ClaudeClient.extractJson(reply).optJSONArray("clips")
            ?: throw IOException("Claude tidak mengembalikan daftar clip. Coba lagi.")

        val result = ArrayList<ClipIdea>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            var s = o.optDouble("start", -1.0)
            var e = o.optDouble("end", -1.0)
            if (s < 0 || e <= s) continue
            s = s.coerceIn(0.0, videoEnd)
            e = e.coerceIn(0.0, videoEnd)
            // Rapikan ke batas kata supaya tidak terpotong di tengah kata.
            words.firstOrNull { it.start >= s - 0.3 }?.let { s = max(0.0, it.start - 0.15) }
            words.lastOrNull { it.end <= e + 0.5 }?.let { e = min(videoEnd + 0.5, it.end + 0.35) }
            if (e - s < 5) continue
            var caption = o.optString("caption")
            if (!caption.contains("#TJR", ignoreCase = true)) caption = "$caption #TJR".trim()
            result.add(
                ClipIdea(
                    id = i,
                    start = s,
                    end = e,
                    title = o.optString("title", "Clip ${i + 1}"),
                    hook = o.optString("hook"),
                    caption = caption,
                    reason = o.optString("reason"),
                    score = o.optInt("score", 0)
                )
            )
        }
        if (result.isEmpty()) throw IOException("Claude tidak menemukan clip yang cocok. Coba ubah instruksi atau durasi.")
        return result
    }
}

object StyleAnalyzer {

    private fun frames(ctx: Context, uri: Uri, count: Int): List<String> {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (durMs <= 0) throw IOException("Video contoh tidak bisa dibaca.")
            val list = ArrayList<String>()
            for (k in 1..count) {
                val tUs = durMs * 1000 * k / (count + 1)
                val bmp = r.getScaledFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST, 480, 854) ?: continue
                val bos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 75, bos)
                bmp.recycle()
                list.add(Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP))
            }
            if (list.isEmpty()) throw IOException("Tidak bisa mengambil gambar dari video contoh.")
            return list
        } finally {
            r.release()
        }
    }

    fun analyze(ctx: Context, client: ClaudeClient, uri: Uri): CaptionStyle {
        val content = JSONArray()
        frames(ctx, uri, 6).forEach { content.put(ClaudeClient.imageBlock(it)) }
        content.put(
            ClaudeClient.textBlock(
                """
These are frames from a short vertical video whose editing style I want to copy.
Describe its subtitle style and framing as settings. Reply with only this JSON:
{"uppercase":true,"wordsPerChunk":3,"fontScale":1.0,"heavy":true,"textColor":"#FFFFFF","highlightColor":"#FFE500 or null","strokeColor":"#000000 or null","boxColor":"#B3000000 or null","positionY":0.7,"framing":"face|center|fit","zoom":1.0,"showHook":true,"note":"one sentence in Indonesian describing the style"}
Meaning: wordsPerChunk = words shown at once (1-8). fontScale 0.6-1.6 where 1.0 means text about 8% of the video width tall. heavy = very bold font. highlightColor = color of the currently spoken word if words are highlighted, else null. strokeColor = outline color or null. boxColor = background box color with alpha (#AARRGGBB) or null. positionY = vertical center of the subtitles, 0 top to 1 bottom. framing: face = cropped tight on the speaker, center = center crop, fit = the full original wide video with bars. zoom 1.0-1.6. showHook = whether there is a title or hook text near the top.
If there are no subtitles, set wordsPerChunk to 0.
""".trimIndent()
            )
        )
        val reply = client.send("You analyze video editing styles precisely.", content, maxTokens = 800)
        val o = ClaudeClient.extractJson(reply)

        fun color(key: String): Int? {
            if (o.isNull(key)) return null
            val s = o.optString(key).trim()
            if (!s.startsWith("#")) return null
            return runCatching { Color.parseColor(s) }.getOrNull()
        }
        val wpc = o.optInt("wordsPerChunk", 3)
        return CaptionStyle(
            name = "Dari video contoh",
            showCaptions = wpc > 0,
            uppercase = o.optBoolean("uppercase", true),
            wordsPerChunk = wpc.coerceIn(1, 8),
            fontScale = o.optDouble("fontScale", 1.0).toFloat().coerceIn(0.6f, 1.6f),
            heavy = o.optBoolean("heavy", true),
            textColor = color("textColor") ?: Color.WHITE,
            highlightColor = color("highlightColor"),
            strokeColor = color("strokeColor"),
            boxColor = color("boxColor"),
            positionY = o.optDouble("positionY", 0.7).toFloat().coerceIn(0.1f, 0.92f),
            framing = when (o.optString("framing").lowercase()) {
                "fit" -> Framing.FIT
                "center" -> Framing.CENTER
                else -> Framing.FACE
            },
            zoom = o.optDouble("zoom", 1.0).toFloat().coerceIn(1f, 1.6f),
            showHook = o.optBoolean("showHook", true),
            note = o.optString("note", "")
        )
    }
}
