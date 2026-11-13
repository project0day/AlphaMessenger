package com.alphamessenger.ui.media.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import org.json.JSONArray
import org.json.JSONObject

/**
 * What is drawn over a photo: brush strokes, then emoji and stickers on top.
 *
 * Every position and size is a FRACTION of the edited frame (after crop and
 * rotation), like the crop itself: the editor, the viewer and the file the
 * recipient gets are different sizes, and one drawing must land the same on all.
 */
data class PhotoMarkup(
    val strokes: List<Stroke> = emptyList(),
    val stickers: List<Sticker> = emptyList(),
) {
    /** One line of the brush. [width] is a fraction of the frame's shorter side. */
    data class Stroke(val color: Int, val width: Float, val points: List<Float>)

    /**
     * An emoji ([emoji]) or a sticker picture ([path]) at a centre ([x], [y]),
     * [size] wide (a fraction of the frame's width), turned [rotation] degrees.
     */
    data class Sticker(
        val emoji: String? = null,
        val path: String? = null,
        val x: Float = 0.5f,
        val y: Float = 0.5f,
        val size: Float = 0.3f,
        val rotation: Float = 0f,
    )

    val isEmpty: Boolean get() = strokes.isEmpty() && stickers.isEmpty()

    fun toJson(): String = JSONObject().apply {
        put("s", JSONArray().apply {
            strokes.forEach { s ->
                put(JSONObject().put("c", s.color).put("w", s.width.toDouble()).put("p", JSONArray().apply { s.points.forEach { put(it.toDouble()) } }))
            }
        })
        put("k", JSONArray().apply {
            stickers.forEach { k ->
                put(JSONObject().apply {
                    k.emoji?.let { put("e", it) }
                    k.path?.let { put("f", it) }
                    put("x", k.x.toDouble()).put("y", k.y.toDouble()).put("z", k.size.toDouble()).put("r", k.rotation.toDouble())
                })
            }
        })
    }.toString()

    companion object {
        fun fromJson(json: String?): PhotoMarkup {
            if (json.isNullOrBlank()) return PhotoMarkup()
            return runCatching {
                val o = JSONObject(json)
                val s = o.optJSONArray("s") ?: JSONArray()
                val k = o.optJSONArray("k") ?: JSONArray()
                PhotoMarkup(
                    strokes = (0 until s.length()).map { i ->
                        val so = s.getJSONObject(i)
                        val p = so.getJSONArray("p")
                        Stroke(so.getInt("c"), so.getDouble("w").toFloat(), (0 until p.length()).map { p.getDouble(it).toFloat() })
                    },
                    stickers = (0 until k.length()).map { i ->
                        val ko = k.getJSONObject(i)
                        Sticker(
                            emoji = ko.optString("e").takeIf { it.isNotBlank() },
                            path = ko.optString("f").takeIf { it.isNotBlank() },
                            x = ko.optDouble("x", 0.5).toFloat(), y = ko.optDouble("y", 0.5).toFloat(),
                            size = ko.optDouble("z", 0.3).toFloat(), rotation = ko.optDouble("r", 0.0).toFloat(),
                        )
                    },
                )
            }.getOrDefault(PhotoMarkup())
        }
    }
}

/** The single drawing of a [PhotoMarkup] onto a [w]×[h] frame: on screen and into the file. */
object MarkupRenderer {

    fun draw(canvas: Canvas, w: Float, h: Float, m: PhotoMarkup, picture: (String) -> Bitmap?) {
        if (m.isEmpty || w <= 0f || h <= 0f) return
        val short = minOf(w, h)
        val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        for (s in m.strokes) {
            pen.color = s.color
            pen.strokeWidth = (s.width * short).coerceAtLeast(1f)
            canvas.drawPath(pathOf(s.points, w, h), pen)
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        for (k in m.stickers) {
            val side = k.size * w
            canvas.save()
            canvas.translate(k.x * w, k.y * h)
            canvas.rotate(k.rotation)
            val bmp = k.path?.let(picture)
            if (bmp != null) {
                val scale = side / maxOf(bmp.width, bmp.height).coerceAtLeast(1)
                val bw = bmp.width * scale
                val bh = bmp.height * scale
                canvas.drawBitmap(bmp, null, android.graphics.RectF(-bw / 2f, -bh / 2f, bw / 2f, bh / 2f), bmpPaint)
            } else if (k.emoji != null) {
                text.textSize = side * 0.86f
                val fm = text.fontMetrics
                canvas.drawText(k.emoji, 0f, -(fm.ascent + fm.descent) / 2f, text)
            }
            canvas.restore()
        }
    }

    /**
     * [bitmap] (the final frame) with [markupJson] drawn over it: the one place that
     * puts a drawing into pixels, for the file and for its poster alike.
     */
    fun bake(bitmap: Bitmap, markupJson: String?): Bitmap {
        val m = PhotoMarkup.fromJson(markupJson)
        if (m.isEmpty) return bitmap
        val out = if (bitmap.isMutable) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, true).also { bitmap.recycle() }
        val side = (maxOf(out.width, out.height) / 2).coerceIn(128, 1024)
        val pictures = m.stickers.mapNotNull { it.path }.distinct().associateWith { p ->
            kotlinx.coroutines.runBlocking { picture(p, side) }
        }
        draw(Canvas(out), out.width.toFloat(), out.height.toFloat(), m) { pictures[it] }
        return out
    }

    /** A smooth line through the points (midpoint quadratic curves). */
    fun pathOf(points: List<Float>, w: Float, h: Float): Path {
        val path = Path()
        if (points.size < 2) return path
        var px = points[0] * w
        var py = points[1] * h
        path.moveTo(px, py)
        if (points.size == 2) { path.lineTo(px + 0.1f, py); return path }
        var i = 2
        while (i + 1 < points.size) {
            val x = points[i] * w
            val y = points[i + 1] * h
            path.quadTo(px, py, (px + x) / 2f, (py + y) / 2f)
            px = x; py = y
            i += 2
        }
        path.lineTo(px, py)
        return path
    }

    /** A sticker file as a picture at [side] px: its first frame for moving ones. */
    suspend fun picture(path: String, side: Int): Bitmap? = runCatching {
        val key = com.alphamessenger.ui.sticker.StickerThumbs.keyOf(path) + "#markup$side"
        val kind = com.alphamessenger.ui.sticker.StickerThumbs.kindOf(path, key)
        val img = if (kind == com.alphamessenger.ui.sticker.StickerThumbs.Kind.VECTOR)
            com.alphamessenger.ui.sticker.StickerThumbs.vectorStill(path, key, side)
        else com.alphamessenger.ui.sticker.StickerThumbs.load(path, key, side)
        img?.asAndroidBitmap()
    }.getOrNull()
}
