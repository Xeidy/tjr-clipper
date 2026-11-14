package com.xeidy.tjrclipper

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import java.util.zip.ZipInputStream

/**
 * Mengubah suara video menjadi teks bahasa Inggris dengan waktu per kata,
 * sepenuhnya di HP memakai Vosk.
 */
class Transcriber(private val ctx: Context) {

    companion object {
        const val MODEL_NAME = "vosk-model-small-en-us-0.15"
        const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
        private const val TARGET_RATE = 16000
    }

    private val modelDir get() = File(ctx.filesDir, MODEL_NAME)

    fun modelReady(): Boolean = File(modelDir, "am/final.mdl").exists()

    fun downloadModel(onProgress: (Float) -> Unit, isActive: () -> Boolean) {
        val tmp = File(ctx.cacheDir, "vosk-model.zip")
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        val code = conn.responseCode
        if (code !in 200..299) throw IOException("Gagal mengunduh model suara (HTTP $code). Cek koneksi internet.")
        val total = conn.contentLengthLong
        conn.inputStream.use { inp ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    if (!isActive()) throw CancellationException()
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) onProgress(done.toFloat() / total)
                }
            }
        }
        val root = ctx.filesDir.canonicalPath
        ZipInputStream(tmp.inputStream().buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val f = File(ctx.filesDir, e.name)
                if (!f.canonicalPath.startsWith(root)) continue
                if (e.isDirectory) {
                    f.mkdirs()
                } else {
                    f.parentFile?.mkdirs()
                    f.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        tmp.delete()
        if (!modelReady()) throw IOException("Model suara rusak setelah diunduh. Coba lagi.")
    }

    fun transcribe(uri: Uri, onProgress: (Float) -> Unit, isActive: () -> Boolean): List<Word> {
        val model = Model(modelDir.absolutePath)
        val rec = Recognizer(model, TARGET_RATE.toFloat())
        rec.setWords(true)
        val words = ArrayList<Word>()

        fun collect(json: String) {
            val arr = JSONObject(json).optJSONArray("result") ?: return
            for (i in 0 until arr.length()) {
                val w = arr.getJSONObject(i)
                words.add(Word(w.getString("word"), w.getDouble("start"), w.getDouble("end")))
            }
        }

        try {
            decodeAudio(uri, onProgress, isActive) { data, n ->
                if (rec.acceptWaveForm(data, n)) collect(rec.result)
            }
            collect(rec.finalResult)
        } finally {
            rec.close()
            model.close()
        }
        return words
    }

    /** Membaca audio video, mengubahnya jadi mono 16 kHz, lalu meneruskannya ke [sink]. */
    private fun decodeAudio(
        uri: Uri,
        onProgress: (Float) -> Unit,
        isActive: () -> Boolean,
        sink: (ShortArray, Int) -> Unit
    ) {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        var track = -1
        for (i in 0 until ex.trackCount) {
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                track = i
                break
            }
        }
        if (track < 0) {
            ex.release()
            throw IOException("Video ini tidak punya suara.")
        }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        var srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floatPcm = false

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(fmt, null, null, 0)
        codec.start()

        val info = MediaCodec.BufferInfo()
        val out = ShortArray(8192)
        var outN = 0
        var phase = 0.0
        var acc = 0.0
        var accN = 0
        var lastReport = 0L
        var inputDone = false
        var outputDone = false

        fun push(sample: Double) {
            val step = srcRate.toDouble() / TARGET_RATE
            acc += sample
            accN++
            phase += 1.0
            if (phase >= step) {
                val v = (acc / accN).toInt().coerceIn(-32768, 32767).toShort()
                while (phase >= step) {
                    out[outN++] = v
                    if (outN == out.size) {
                        sink(out, outN)
                        outN = 0
                    }
                    phase -= step
                }
                acc = 0.0
                accN = 0
            }
        }

        try {
            while (!outputDone) {
                if (!isActive()) throw CancellationException()
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    srcRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (oi >= 0) {
                    val buf = codec.getOutputBuffer(oi)!!
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    buf.order(ByteOrder.nativeOrder())
                    val ch = channels.coerceAtLeast(1)
                    if (floatPcm) {
                        val fb = buf.asFloatBuffer()
                        val frames = fb.remaining() / ch
                        for (f in 0 until frames) {
                            var sum = 0.0
                            for (c in 0 until ch) sum += fb.get()
                            push(sum / ch * 32767.0)
                        }
                    } else {
                        val sb = buf.asShortBuffer()
                        val frames = sb.remaining() / ch
                        for (f in 0 until frames) {
                            var sum = 0.0
                            for (c in 0 until ch) sum += sb.get()
                            push(sum / ch)
                        }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    if (durUs > 0 && info.presentationTimeUs - lastReport > 1_000_000) {
                        lastReport = info.presentationTimeUs
                        onProgress((info.presentationTimeUs.toFloat() / durUs).coerceIn(0f, 1f))
                    }
                }
            }
            if (outN > 0) sink(out, outN)
        } finally {
            runCatching { codec.stop() }
            codec.release()
            ex.release()
        }
    }
}

/** Membaca file subtitle .srt atau .vtt (misalnya dari YouTube) sebagai pengganti transkrip. */
object SubtitleParser {
    private val timeRe = Regex("""((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})""")

    private fun toSec(t: String): Double {
        val parts = t.replace(',', '.').split(':')
        var s = 0.0
        for (p in parts) s = s * 60 + p.toDouble()
        return s
    }

    fun parse(text: String): List<Word> {
        val words = ArrayList<Word>()
        val lines = text.replace("\r", "").split("\n")
        var i = 0
        var prevLines = emptyList<String>()
        while (i < lines.size) {
            val line = lines[i]
            if (line.contains("-->")) {
                val times = timeRe.findAll(line).map { it.value }.toList()
                i++
                val cue = ArrayList<String>()
                while (i < lines.size && lines[i].isNotBlank()) {
                    val clean = lines[i]
                        .replace(Regex("<[^>]*>"), "")
                        .replace(Regex("\\{[^}]*\\}"), "")
                        .trim()
                    if (clean.isNotEmpty()) cue.add(clean)
                    i++
                }
                if (times.size < 2) continue
                // Subtitle otomatis YouTube mengulang baris sebelumnya; buang yang sudah pernah muncul.
                val fresh = cue.filter { it !in prevLines }
                prevLines = cue
                val tokens = fresh.joinToString(" ").split(Regex("\\s+")).filter { it.isNotBlank() }
                if (tokens.isEmpty()) continue
                val start = toSec(times[0])
                val end = toSec(times[1]).coerceAtLeast(start + 0.2)
                val per = (end - start) / tokens.size
                tokens.forEachIndexed { k, tok ->
                    words.add(Word(tok, start + k * per, start + (k + 1) * per))
                }
            } else {
                i++
            }
        }
        return words
    }
}
