package com.alphamessenger.ui.sticker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.io.File

/**
 * The built-in pack: Alpha's ghost in sixteen moods, drawn here (not shipped
 * as pictures), so every phone has it from the start.
 */
object AlphaGhostPack {
    const val ID = "alpha_ghost"
    private const val VERSION = 1

    private enum class Mouth { SMILE, GRIN, OPEN, SAD, FLAT, SMIRK, KISS, WAVY, TONGUE }
    private enum class Eyes { DOTS, HAPPY, WIDE, CLOSED, WINK, HEARTS, STARS, ANGRY, SAD }
    private enum class Extra { NONE, TEARS, BLUSH, ZZZ, SWEAT, SPARKS, HEART, QUESTION, WAVE, FIRE, THUMB, PARTY }

    private data class Mood(val emoji: String, val body: Int, val eyes: Eyes, val mouth: Mouth, val extra: Extra = Extra.NONE)

    private val moods = listOf(
        Mood("😊", 0xFF8C95FF.toInt(), Eyes.HAPPY, Mouth.SMILE, Extra.BLUSH),
        Mood("😂", 0xFFF5C451.toInt(), Eyes.CLOSED, Mouth.GRIN, Extra.TEARS),
        Mood("😍", 0xFFF28CB1.toInt(), Eyes.HEARTS, Mouth.SMILE),
        Mood("😮", 0xFFF2F3F5.toInt(), Eyes.WIDE, Mouth.OPEN),
        Mood("😢", 0xFF7FB8E8.toInt(), Eyes.SAD, Mouth.SAD, Extra.TEARS),
        Mood("😠", 0xFFE86F6F.toInt(), Eyes.ANGRY, Mouth.FLAT, Extra.FIRE),
        Mood("😴", 0xFFB39DDB.toInt(), Eyes.CLOSED, Mouth.FLAT, Extra.ZZZ),
        Mood("😏", 0xFF5FD3A5.toInt(), Eyes.DOTS, Mouth.SMIRK),
        Mood("😘", 0xFFF28CB1.toInt(), Eyes.WINK, Mouth.KISS, Extra.HEART),
        Mood("🤩", 0xFFF5C451.toInt(), Eyes.STARS, Mouth.GRIN, Extra.SPARKS),
        Mood("😅", 0xFF8C95FF.toInt(), Eyes.HAPPY, Mouth.GRIN, Extra.SWEAT),
        Mood("🤔", 0xFFE8A766.toInt(), Eyes.DOTS, Mouth.WAVY, Extra.QUESTION),
        Mood("👋", 0xFF5FD3A5.toInt(), Eyes.HAPPY, Mouth.SMILE, Extra.WAVE),
        Mood("👍", 0xFF8C95FF.toInt(), Eyes.WINK, Mouth.SMILE, Extra.THUMB),
        Mood("🎉", 0xFFB39DDB.toInt(), Eyes.HAPPY, Mouth.OPEN, Extra.PARTY),
        Mood("😜", 0xFFE8A766.toInt(), Eyes.WINK, Mouth.TONGUE),
    )

    @Synchronized
    fun ensure(ctx: Context) {
        val prefs = ctx.getSharedPreferences("stickers", Context.MODE_PRIVATE)
        val existing = StickerStore.dir(ctx, ID)
        if (prefs.getInt("ghost_v", 0) == VERSION && File(existing, "pack.json").exists()) return
        existing.listFiles()?.forEach { it.delete() }
        val stickers = moods.mapIndexedNotNull { i, m ->
            val f = File(existing, "${STICKER_PREFIX}${ID}_$i.webp")
            val bmp = draw(m)
            val ok = runCatching {
                f.outputStream().use {
                    @Suppress("DEPRECATION")
                    val fmt = if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP
                    bmp.compress(fmt, 100, it)
                }
            }.isSuccess
            bmp.recycle()
            if (ok) Sticker(f.absolutePath, m.emoji) else null
        }
        File(existing, "pack.json").writeText(
            org.json.JSONObject().put("id", ID).put("n", "Alpha Ghost").put("own", false).put("b", true)
                .put("s", org.json.JSONArray().apply {
                    stickers.forEach { put(org.json.JSONObject().put("f", File(it.file).name).put("e", it.emoji)) }
                }).toString()
        )
        prefs.edit().putInt("ghost_v", VERSION).apply()
    }

    private const val S = 512f

    private fun draw(m: Mood): Bitmap {
        val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        val ink = 0xFF1E1F22.toInt()

        // The ghost: a dome over a wavy hem, with a white sticker outline.
        val body = Path().apply {
            moveTo(110f, 440f)
            lineTo(110f, 230f)
            arcTo(RectF(110f, 84f, 402f, 376f), 180f, 180f, false)
            lineTo(402f, 440f)
            val w = (402f - 110f) / 5f
            for (k in 0 until 5) {
                val x0 = 402f - k * w
                quadTo(x0 - w / 2f, 400f, x0 - w, 440f)
            }
            close()
        }
        stroke.color = Color.WHITE; stroke.strokeWidth = 26f
        c.drawPath(body, stroke)
        fill.color = m.body
        c.drawPath(body, fill)
        // A soft shade on one side.
        fill.color = 0x22000000
        c.save(); c.clipPath(body); c.drawCircle(470f, 300f, 170f, fill); c.restore()

        val ex = floatArrayOf(205f, 307f)
        val ey = 225f
        stroke.color = ink; stroke.strokeWidth = 14f
        fill.color = ink
        when (m.eyes) {
            Eyes.DOTS -> ex.forEach { c.drawCircle(it, ey, 17f, fill) }
            Eyes.WIDE -> ex.forEach {
                fill.color = Color.WHITE; c.drawCircle(it, ey, 30f, fill)
                fill.color = ink; c.drawCircle(it, ey + 4f, 15f, fill)
            }
            Eyes.HAPPY -> ex.forEach { c.drawArc(RectF(it - 24f, ey - 14f, it + 24f, ey + 26f), 200f, 140f, false, stroke) }
            Eyes.CLOSED -> ex.forEach { c.drawArc(RectF(it - 24f, ey - 24f, it + 24f, ey + 14f), 20f, 140f, false, stroke) }
            Eyes.WINK -> {
                c.drawCircle(ex[0], ey, 17f, fill)
                c.drawArc(RectF(ex[1] - 24f, ey - 14f, ex[1] + 24f, ey + 26f), 200f, 140f, false, stroke)
            }
            Eyes.HEARTS -> ex.forEach { heart(c, it, ey, 26f, 0xFFE0245E.toInt()) }
            Eyes.STARS -> ex.forEach { star(c, it, ey, 28f, 0xFFFFFFFF.toInt(), ink) }
            Eyes.ANGRY -> ex.forEachIndexed { i, x ->
                c.drawCircle(x, ey + 8f, 15f, fill)
                val dir = if (i == 0) 1 else -1
                c.drawLine(x - 26f * dir, ey - 30f, x + 22f * dir, ey - 14f, stroke)
            }
            Eyes.SAD -> ex.forEachIndexed { i, x ->
                c.drawCircle(x, ey + 6f, 15f, fill)
                val dir = if (i == 0) 1 else -1
                c.drawLine(x - 24f * dir, ey - 16f, x + 20f * dir, ey - 30f, stroke)
            }
        }

        val mx = 256f; val my = 300f
        stroke.color = ink; stroke.strokeWidth = 14f
        fill.color = ink
        when (m.mouth) {
            Mouth.SMILE -> c.drawArc(RectF(mx - 46f, my - 40f, mx + 46f, my + 30f), 20f, 140f, false, stroke)
            Mouth.GRIN -> {
                val p = Path().apply { moveTo(mx - 56f, my - 10f); quadTo(mx, my + 80f, mx + 56f, my - 10f); close() }
                c.drawPath(p, fill)
                fill.color = 0xFFE86F6F.toInt()
                c.save(); c.clipPath(p); c.drawCircle(mx, my + 52f, 30f, fill); c.restore()
            }
            Mouth.OPEN -> c.drawOval(RectF(mx - 22f, my - 18f, mx + 22f, my + 34f), fill)
            Mouth.SAD -> c.drawArc(RectF(mx - 40f, my + 6f, mx + 40f, my + 60f), 200f, 140f, false, stroke)
            Mouth.FLAT -> c.drawLine(mx - 34f, my + 14f, mx + 34f, my + 14f, stroke)
            Mouth.SMIRK -> {
                val p = Path().apply { moveTo(mx - 34f, my + 14f); quadTo(mx + 10f, my + 26f, mx + 44f, my - 6f) }
                c.drawPath(p, stroke)
            }
            Mouth.KISS -> {
                c.drawArc(RectF(mx - 14f, my - 10f, mx + 18f, my + 14f), 270f, 180f, false, stroke)
                c.drawArc(RectF(mx - 14f, my + 12f, mx + 18f, my + 36f), 270f, 180f, false, stroke)
            }
            Mouth.WAVY -> {
                val p = Path().apply { moveTo(mx - 40f, my + 14f); quadTo(mx - 20f, my, mx, my + 14f); quadTo(mx + 20f, my + 28f, mx + 40f, my + 14f) }
                c.drawPath(p, stroke)
            }
            Mouth.TONGUE -> {
                c.drawArc(RectF(mx - 46f, my - 40f, mx + 46f, my + 30f), 20f, 140f, false, stroke)
                fill.color = 0xFFE86F6F.toInt()
                c.drawRoundRect(RectF(mx - 4f, my + 20f, mx + 34f, my + 66f), 19f, 19f, fill)
            }
        }

        when (m.extra) {
            Extra.NONE -> {}
            Extra.BLUSH -> { fill.color = 0x55F28CB1; c.drawOval(RectF(150f, 262f, 196f, 288f), fill); c.drawOval(RectF(316f, 262f, 362f, 288f), fill) }
            Extra.TEARS -> {
                fill.color = 0xFF7FD3FF.toInt()
                drop(c, 168f, 270f, 18f, fill); drop(c, 344f, 270f, 18f, fill)
            }
            Extra.ZZZ -> {
                val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 70f; isFakeBoldText = true }
                val o = Paint(t).apply { style = Paint.Style.STROKE; strokeWidth = 10f; color = ink }
                listOf(Triple("Z", 380f, 120f), Triple("z", 430f, 72f)).forEach { (s, x, y) -> c.drawText(s, x, y, o); c.drawText(s, x, y, t) }
            }
            Extra.SWEAT -> { fill.color = 0xFF7FD3FF.toInt(); drop(c, 372f, 150f, 22f, fill) }
            Extra.SPARKS -> { star(c, 90f, 110f, 26f, 0xFFF5C451.toInt(), ink); star(c, 430f, 90f, 20f, 0xFFF5C451.toInt(), ink) }
            Extra.HEART -> heart(c, 420f, 120f, 40f, 0xFFE0245E.toInt())
            Extra.QUESTION -> {
                val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 150f; isFakeBoldText = true }
                val o = Paint(t).apply { style = Paint.Style.STROKE; strokeWidth = 12f; color = ink }
                c.drawText("?", 392f, 170f, o); c.drawText("?", 392f, 170f, t)
            }
            Extra.WAVE -> {
                // A little hand up beside the ghost.
                fill.color = m.body
                stroke.color = Color.WHITE; stroke.strokeWidth = 20f
                val hand = RectF(408f, 160f, 476f, 236f)
                c.drawOval(hand, stroke); c.drawOval(hand, fill)
                stroke.color = ink; stroke.strokeWidth = 8f
                c.drawArc(RectF(430f, 110f, 500f, 170f), 300f, 80f, false, stroke)
                c.drawArc(RectF(440f, 90f, 520f, 160f), 300f, 80f, false, stroke)
            }
            Extra.THUMB -> {
                fill.color = 0xFFF5C451.toInt()
                stroke.color = Color.WHITE; stroke.strokeWidth = 18f
                val fist = RectF(390f, 300f, 470f, 380f)
                c.drawRoundRect(fist, 22f, 22f, stroke); c.drawRoundRect(fist, 22f, 22f, fill)
                val thumb = RectF(404f, 236f, 438f, 320f)
                c.drawRoundRect(thumb, 17f, 17f, stroke); c.drawRoundRect(thumb, 17f, 17f, fill)
            }
            Extra.FIRE -> {
                fill.color = 0xFFFF7A3D.toInt()
                val p = Path().apply { moveTo(400f, 170f); quadTo(380f, 110f, 420f, 60f); quadTo(430f, 110f, 460f, 120f); quadTo(470f, 160f, 440f, 180f); close() }
                c.drawPath(p, fill)
            }
            Extra.PARTY -> {
                val colors = intArrayOf(0xFFF5C451.toInt(), 0xFF5FD3A5.toInt(), 0xFFE86F6F.toInt(), 0xFF7FB8E8.toInt())
                listOf(80f to 120f, 440f to 90f, 60f to 300f, 460f to 260f, 120f to 60f, 400f to 40f).forEachIndexed { i, (x, y) ->
                    fill.color = colors[i % colors.size]
                    c.save(); c.rotate(i * 37f, x, y); c.drawRect(x - 12f, y - 6f, x + 12f, y + 6f, fill); c.restore()
                }
            }
        }
        return bmp
    }

    private fun heart(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        val p = Path().apply {
            moveTo(x, y + r)
            cubicTo(x - r * 1.6f, y - r * 0.2f, x - r * 0.6f, y - r * 1.3f, x, y - r * 0.4f)
            cubicTo(x + r * 0.6f, y - r * 1.3f, x + r * 1.6f, y - r * 0.2f, x, y + r)
            close()
        }
        c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    }

    private fun star(c: Canvas, x: Float, y: Float, r: Float, color: Int, outline: Int) {
        val p = Path()
        for (k in 0 until 10) {
            val a = Math.toRadians((k * 36 - 90).toDouble())
            val rr = if (k % 2 == 0) r else r * 0.45f
            val px = x + (rr * Math.cos(a)).toFloat(); val py = y + (rr * Math.sin(a)).toFloat()
            if (k == 0) p.moveTo(px, py) else p.lineTo(px, py)
        }
        p.close()
        c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 8f; this.color = outline; strokeJoin = Paint.Join.ROUND })
        c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    }

    private fun drop(c: Canvas, x: Float, y: Float, r: Float, paint: Paint) {
        val p = Path().apply {
            moveTo(x, y - r * 1.6f)
            quadTo(x + r, y - r * 0.2f, x + r, y + r * 0.2f)
            arcTo(RectF(x - r, y - r * 0.8f, x + r, y + r * 1.2f), 0f, 180f, false)
            quadTo(x - r, y - r * 0.2f, x, y - r * 1.6f)
            close()
        }
        c.drawPath(p, paint)
    }
}
