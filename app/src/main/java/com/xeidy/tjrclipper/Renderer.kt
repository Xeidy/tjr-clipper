package com.xeidy.tjrclipper

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Crop
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.android.gms.tasks.Tasks
import com.google.common.collect.ImmutableList
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

const val OUT_W = 720
const val OUT_H = 1280
private const val HOOK_SECONDS = 3.0

/** Menggambar subtitle dan hook untuk satu waktu tertentu di dalam clip. */
class CaptionRenderer(
    private val style: CaptionStyle,
    words: List<Word>,
    private val hook: String,
    private val width: Int,
    private val height: Int
) {
    private class Chunk(val words: List<Word>, val start: Double, var end: Double)

    private val chunks: List<Chunk>
    private val typeface: Typeface =
        if (style.heavy) Typeface.create("sans-serif-black", Typeface.NORMAL) else Typeface.create("sans-serif", Typeface.BOLD)

    init {
        val list = ArrayList<Chunk>()
        var cur = ArrayList<Word>()
        val per = style.wordsPerChunk.coerceAtLeast(1)
        for (w in words) {
            if (cur.isNotEmpty() && (cur.size >= per || w.start - cur.last().end > 0.6)) {
                list.add(Chunk(cur, cur.first().start, cur.last().end + 0.5))
                cur = ArrayList()
            }
            cur.add(w)
        }
        if (cur.isNotEmpty()) list.add(Chunk(cur, cur.first().start, cur.last().end + 0.5))
        for (i in 0 until list.size - 1) list[i].end = min(list[i].end, list[i + 1].start)
        chunks = list
    }

    private fun chunkAt(t: Double): Int = chunks.indexOfFirst { t >= it.start && t < it.end }

    /** Kunci yang berubah hanya ketika gambar perlu digambar ulang. */
    fun keyAt(t: Double): Long {
        val hookOn = style.showHook && hook.isNotBlank() && t < HOOK_SECONDS
        var key = if (hookOn) 1L else 0L
        if (style.showCaptions) {
            val ci = chunkAt(t)
            if (ci >= 0) {
                val wi = chunks[ci].words.indexOfLast { it.start <= t }.coerceAtLeast(0)
                key += (ci + 1L) * 1000L + wi * 10L
            }
        }
        return key
    }

    private fun display(w: String): String {
        val s = when {
            w == "i" -> "I"
            w.startsWith("i'") -> "I" + w.substring(1)
            else -> w
        }
        return if (style.uppercase) s.uppercase() else s
    }

    fun draw(c: Canvas, t: Double, clear: Boolean = true) {
        if (clear) c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        if (style.showHook && hook.isNotBlank() && t < HOOK_SECONDS) drawHook(c)
        if (!style.showCaptions) return
        val ci = chunkAt(t)
        if (ci < 0) return
        val chunk = chunks[ci]
        val wi = chunk.words.indexOfLast { it.start <= t }.coerceAtLeast(0)
        val texts = chunk.words.map { display(it.text) }
        val full = texts.joinToString(" ")

        val size = width * 0.085f * style.fontScale
        val maxW = (width * 0.86f).toInt()
        val st = style
        val fill = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = this@CaptionRenderer.typeface
            color = st.textColor
            if (st.strokeColor == null && st.boxColor == null) {
                setShadowLayer(size * 0.12f, 0f, size * 0.05f, Color.argb(200, 0, 0, 0))
            }
        }
        val spanned = SpannableString(full)
        style.highlightColor?.let { hl ->
            var off = 0
            for (k in 0 until wi) off += texts[k].length + 1
            spanned.setSpan(ForegroundColorSpan(hl), off, off + texts[wi].length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val fillLayout = layout(spanned, fill, maxW)
        val left = (width - maxW) / 2f
        val top = (height * style.positionY - fillLayout.height / 2f)
            .coerceIn(height * 0.03f, height * 0.97f - fillLayout.height)

        c.save()
        c.translate(left, top)
        style.boxColor?.let { box ->
            var l = Float.MAX_VALUE
            var r = 0f
            for (line in 0 until fillLayout.lineCount) {
                l = min(l, fillLayout.getLineLeft(line))
                r = maxOf(r, fillLayout.getLineRight(line))
            }
            val pad = size * 0.3f
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = box }
            c.drawRoundRect(RectF(l - pad, -pad * 0.6f, r + pad, fillLayout.height + pad * 0.6f), pad, pad, p)
        }
        style.strokeColor?.let { sc ->
            val stroke = TextPaint(fill).apply {
                this.style = Paint.Style.STROKE
                strokeWidth = size * 0.16f
                strokeJoin = Paint.Join.ROUND
                color = sc
                clearShadowLayer()
            }
            layout(full, stroke, maxW).draw(c)
        }
        fillLayout.draw(c)
        c.restore()
    }

    private fun drawHook(c: Canvas) {
        val size = width * 0.058f
        val maxW = (width * 0.8f).toInt()
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            color = Color.BLACK
        }
        val lay = layout(hook, paint, maxW)
        var l = Float.MAX_VALUE
        var r = 0f
        for (line in 0 until lay.lineCount) {
            l = min(l, lay.getLineLeft(line))
            r = maxOf(r, lay.getLineRight(line))
        }
        val left = (width - maxW) / 2f
        val top = height * 0.1f
        val pad = size * 0.45f
        c.save()
        c.translate(left, top)
        c.drawRoundRect(
            RectF(l - pad, -pad * 0.7f, r + pad, lay.height + pad * 0.7f),
            pad * 0.6f, pad * 0.6f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        )
        lay.draw(c)
        c.restore()
    }

    private fun layout(text: CharSequence, paint: TextPaint, w: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 0.95f)
            .setIncludePad(false)
            .build()
}

/** Overlay Media3 yang menggambar ulang subtitle hanya ketika isinya berubah. */
class CaptionOverlay(private val renderer: CaptionRenderer) : BitmapOverlay() {
    private val bitmaps = arrayOf(
        Bitmap.createBitmap(OUT_W, OUT_H, Bitmap.Config.ARGB_8888),
        Bitmap.createBitmap(OUT_W, OUT_H, Bitmap.Config.ARGB_8888)
    )
    private var current = 0
    private var lastKey: Long? = null
    private var firstTs = Long.MIN_VALUE

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        if (firstTs == Long.MIN_VALUE) firstTs = presentationTimeUs
        val t = (presentationTimeUs - firstTs) / 1_000_000.0
        val key = renderer.keyAt(t)
        if (key != lastKey) {
            current = 1 - current
            renderer.draw(Canvas(bitmaps[current]), t)
            lastKey = key
        }
        return bitmaps[current]
    }
}

class ClipRenderer(private val ctx: Context) {

    private data class SourceInfo(val width: Int, val height: Int)

    private fun sourceInfo(uri: Uri): SourceInfo {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (w <= 0 || h <= 0) throw IOException("Ukuran video tidak terbaca.")
            return if (rot == 90 || rot == 270) SourceInfo(h, w) else SourceInfo(w, h)
        } finally {
            r.release()
        }
    }

    /** Mencari posisi wajah terbesar (0..1) di beberapa frame clip. */
    private fun detectFace(uri: Uri, start: Double, end: Double): PointF? {
        val r = MediaMetadataRetriever()
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.05f)
                .build()
        )
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        try {
            r.setDataSource(ctx, uri)
            for (k in 1..5) {
                val t = start + (end - start) * k / 6.0
                val bmp = r.getScaledFrameAtTime(
                    (t * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 960, 960
                ) ?: continue
                val faces = runCatching { Tasks.await(detector.process(InputImage.fromBitmap(bmp, 0))) }.getOrNull()
                faces?.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }?.let {
                    xs.add(it.boundingBox.exactCenterX() / bmp.width)
                    ys.add(it.boundingBox.exactCenterY() / bmp.height)
                }
                bmp.recycle()
            }
        } catch (e: Exception) {
            return null
        } finally {
            detector.close()
            r.release()
        }
        if (xs.isEmpty()) return null
        xs.sort()
        ys.sort()
        return PointF(xs[xs.size / 2], ys[ys.size / 2])
    }

    private fun computeCrop(src: SourceInfo, face: PointF?, zoom: Float): Crop {
        val w = src.width.toFloat()
        val h = src.height.toFloat()
        val target = OUT_W.toFloat() / OUT_H
        var cropW: Float
        var cropH: Float
        if (w / h > target) {
            cropH = h / zoom
            cropW = cropH * target
        } else {
            cropW = w / zoom
            cropH = cropW / target
        }
        cropW = min(cropW, w)
        cropH = min(cropH, h)
        val fx = (face?.x ?: 0.5f) * w
        // Wajah ditaruh sedikit di atas tengah, ruang bawah untuk subtitle.
        val fy = if (face != null) face.y * h + cropH * 0.1f else h / 2f
        val cx = fx.coerceIn(cropW / 2f, w - cropW / 2f)
        val cy = fy.coerceIn(cropH / 2f, h - cropH / 2f)
        val left = (cx - cropW / 2f) / w * 2f - 1f
        val right = (cx + cropW / 2f) / w * 2f - 1f
        val top = 1f - (cy - cropH / 2f) / h * 2f
        val bottom = 1f - (cy + cropH / 2f) / h * 2f
        return Crop(left, right, bottom, top)
    }

    suspend fun render(
        uri: Uri,
        clip: ClipIdea,
        words: List<Word>,
        style: CaptionStyle,
        onProgress: (Float) -> Unit
    ): Uri {
        val effects = ArrayList<Effect>()
        withContext(Dispatchers.IO) {
            val info = sourceInfo(uri)
            when (style.framing) {
                Framing.FIT -> effects.add(
                    Presentation.createForWidthAndHeight(OUT_W, OUT_H, Presentation.LAYOUT_SCALE_TO_FIT)
                )
                Framing.CENTER, Framing.FACE -> {
                    val face = if (style.framing == Framing.FACE) detectFace(uri, clip.start, clip.end) else null
                    effects.add(computeCrop(info, face, style.zoom))
                    effects.add(
                        Presentation.createForWidthAndHeight(OUT_W, OUT_H, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
                    )
                }
            }
        }
        val relWords = words.filter { it.end > clip.start && it.start < clip.end }
            .map { Word(it.text, it.start - clip.start, it.end - clip.start) }
        if (style.showCaptions || (style.showHook && clip.hook.isNotBlank())) {
            val renderer = CaptionRenderer(style, relWords, clip.hook, OUT_W, OUT_H)
            effects.add(OverlayEffect(ImmutableList.of<TextureOverlay>(CaptionOverlay(renderer))))
        }

        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs((clip.start * 1000).toLong())
                    .setEndPositionMs((clip.end * 1000).toLong())
                    .build()
            )
            .build()
        val edited = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(emptyList(), effects))
            .build()
        val out = File(ctx.cacheDir, "render_${System.currentTimeMillis()}.mp4")

        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { cont ->
                val handler = Handler(Looper.getMainLooper())
                var finished = false
                val transformer = Transformer.Builder(ctx)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            finished = true
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            finished = true
                            if (cont.isActive) {
                                cont.resumeWithException(
                                    IOException("Gagal merender: ${exportException.message}", exportException)
                                )
                            }
                        }
                    })
                    .build()
                transformer.start(edited, out.absolutePath)
                val holder = ProgressHolder()
                val poll = object : Runnable {
                    override fun run() {
                        if (finished) return
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress(holder.progress / 100f)
                        }
                        handler.postDelayed(this, 500)
                    }
                }
                handler.post(poll)
                cont.invokeOnCancellation {
                    handler.post {
                        finished = true
                        transformer.cancel()
                    }
                }
            }
        }

        return withContext(Dispatchers.IO) {
            try {
                saveToGallery(out, "TJR_${clip.title.take(30).replace(Regex("[^A-Za-z0-9]+"), "_")}_${System.currentTimeMillis() / 1000}.mp4")
            } finally {
                out.delete()
            }
        }
    }

    private fun saveToGallery(file: File, name: String): Uri {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/TJR Clipper")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("Tidak bisa menyimpan video ke galeri.")
        resolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
            ?: throw IOException("Tidak bisa menulis video ke galeri.")
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }
}

/** Gambar contoh gaya untuk ditampilkan di layar pengaturan gaya. */
fun stylePreview(style: CaptionStyle): Bitmap {
    val w = 360
    val h = 640
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawColor(Color.parseColor("#2A3550"))
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3D4A6B") }
    if (style.framing == Framing.FIT) {
        c.drawColor(Color.BLACK)
        c.drawRect(0f, h * 0.36f, w.toFloat(), h * 0.64f, p)
        p.color = Color.parseColor("#56658C")
        c.drawCircle(w * 0.82f, h * 0.58f, w * 0.06f, p)
    } else {
        val r = w * 0.2f * style.zoom
        c.drawCircle(w / 2f, h * 0.4f, r, p)
        c.drawRoundRect(RectF(w / 2f - r * 1.6f, h * 0.4f + r * 1.1f, w / 2f + r * 1.6f, h * 1.1f), r, r, p)
    }
    val words = "the market does not care about your feelings".split(" ")
        .mapIndexed { i, t -> Word(t, i * 0.4, i * 0.4 + 0.35) }
    val renderer = CaptionRenderer(style, words, "TJR on trading discipline", w, h)
    renderer.draw(c, 0.45, clear = false)
    return bmp
}
