package com.xeidy.tjrclipper

import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

/** Satu kata hasil transkrip, waktu dalam detik. */
data class Word(val text: String, val start: Double, val end: Double)

enum class Framing(val label: String) {
    FACE("Fokus wajah"),
    CENTER("Tengah"),
    FIT("Video utuh")
}

data class CaptionStyle(
    val name: String,
    val showCaptions: Boolean = true,
    val uppercase: Boolean = true,
    val wordsPerChunk: Int = 3,
    val fontScale: Float = 1f,
    val heavy: Boolean = true,
    val textColor: Int = Color.WHITE,
    val highlightColor: Int? = null,
    val strokeColor: Int? = Color.BLACK,
    val boxColor: Int? = null,
    /** Posisi tengah subtitle, 0 = atas, 1 = bawah. */
    val positionY: Float = 0.7f,
    val framing: Framing = Framing.FACE,
    val zoom: Float = 1f,
    val showHook: Boolean = true,
    val note: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("showCaptions", showCaptions)
        .put("uppercase", uppercase)
        .put("wordsPerChunk", wordsPerChunk)
        .put("fontScale", fontScale.toDouble())
        .put("heavy", heavy)
        .put("textColor", textColor)
        .put("highlightColor", highlightColor ?: JSONObject.NULL)
        .put("strokeColor", strokeColor ?: JSONObject.NULL)
        .put("boxColor", boxColor ?: JSONObject.NULL)
        .put("positionY", positionY.toDouble())
        .put("framing", framing.name)
        .put("zoom", zoom.toDouble())
        .put("showHook", showHook)
        .put("note", note)

    companion object {
        fun fromJson(o: JSONObject): CaptionStyle {
            fun optColor(key: String): Int? = if (o.isNull(key)) null else o.optInt(key)
            return CaptionStyle(
                name = o.optString("name", "Gaya saya"),
                showCaptions = o.optBoolean("showCaptions", true),
                uppercase = o.optBoolean("uppercase", true),
                wordsPerChunk = o.optInt("wordsPerChunk", 3),
                fontScale = o.optDouble("fontScale", 1.0).toFloat(),
                heavy = o.optBoolean("heavy", true),
                textColor = o.optInt("textColor", Color.WHITE),
                highlightColor = optColor("highlightColor"),
                strokeColor = optColor("strokeColor"),
                boxColor = optColor("boxColor"),
                positionY = o.optDouble("positionY", 0.7).toFloat(),
                framing = runCatching { Framing.valueOf(o.optString("framing")) }.getOrDefault(Framing.FACE),
                zoom = o.optDouble("zoom", 1.0).toFloat(),
                showHook = o.optBoolean("showHook", true),
                note = o.optString("note", "")
            )
        }
    }
}

object Presets {
    val all = listOf(
        CaptionStyle(
            name = "Viral kuning",
            uppercase = true, wordsPerChunk = 3, fontScale = 1f,
            highlightColor = Color.parseColor("#FFE500"), strokeColor = Color.BLACK,
            positionY = 0.68f, framing = Framing.FACE,
            note = "Huruf besar tebal, kata yang sedang diucapkan berwarna kuning."
        ),
        CaptionStyle(
            name = "Bersih",
            uppercase = false, wordsPerChunk = 6, fontScale = 0.78f, heavy = false,
            strokeColor = null, boxColor = Color.parseColor("#B3000000"),
            positionY = 0.8f, framing = Framing.FACE,
            note = "Kalimat biasa dengan kotak gelap, kesan profesional."
        ),
        CaptionStyle(
            name = "Pop hijau",
            uppercase = true, wordsPerChunk = 2, fontScale = 1.3f,
            highlightColor = Color.parseColor("#39FF14"), strokeColor = Color.BLACK,
            positionY = 0.6f, framing = Framing.FACE, zoom = 1.15f,
            note = "Dua kata sekali tampil, sangat besar, sedikit zoom."
        ),
        CaptionStyle(
            name = "Layar utuh",
            uppercase = false, wordsPerChunk = 5, fontScale = 0.85f,
            highlightColor = Color.parseColor("#FFE500"), strokeColor = Color.BLACK,
            positionY = 0.78f, framing = Framing.FIT,
            note = "Video asli tidak dipotong (ada bar hitam), cocok untuk chart."
        ),
        CaptionStyle(
            name = "Tanpa subtitle",
            showCaptions = false, framing = Framing.FACE,
            note = "Hanya dipotong ke 9:16 dan diberi hook di atas."
        )
    )
}

data class ClipIdea(
    val id: Int,
    val start: Double,
    val end: Double,
    val title: String,
    val hook: String,
    val caption: String,
    val reason: String,
    val score: Int,
    val selected: Boolean = true,
    val renderStatus: String? = null,
    val outputUri: String? = null
)

object WordStore {
    fun toJson(words: List<Word>): String {
        val arr = JSONArray()
        for (w in words) arr.put(JSONArray().put(w.text).put(w.start).put(w.end))
        return arr.toString()
    }

    fun fromJson(s: String): List<Word> {
        val arr = JSONArray(s)
        return List(arr.length()) { i ->
            val a = arr.getJSONArray(i)
            Word(a.getString(0), a.getDouble(1), a.getDouble(2))
        }
    }
}

fun formatTime(sec: Double): String {
    val s = sec.toInt().coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val r = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
}
