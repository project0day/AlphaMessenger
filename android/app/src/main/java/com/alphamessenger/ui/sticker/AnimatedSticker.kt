package com.alphamessenger.ui.sticker

/** The largest animated sticker taken in, in bytes, and its largest side in pixels. */
const val ANIMATED_MAX_BYTES = 512 * 1024
const val ANIMATED_MAX_SIDE = 512

/**
 * Animated stickers (animated WebP, GIF) travel as the file itself: nothing on the
 * way re-encodes them, so they keep moving, and an app that cannot animate shows
 * the first frame. Only what could say something about the sender is cut out:
 * WebP's EXIF/XMP chunks, GIF's comment, text and foreign application blocks.
 */
object AnimatedSticker {
    private fun at(b: ByteArray, i: Int, s: String) =
        b.size >= i + s.length && s.indices.all { b[i + it] == s[it].code.toByte() }

    private fun le32(b: ByteArray, i: Int) =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)

    private fun putLe32(b: ByteArray, i: Int, v: Int) {
        for (k in 0..3) b[i + k] = (v ushr (8 * k)).toByte()
    }

    fun isWebp(b: ByteArray) = at(b, 0, "RIFF") && at(b, 8, "WEBP")
    fun isGif(b: ByteArray) = at(b, 0, "GIF8")

    /** An animated WebP says so in its VP8X header (flag A). */
    fun isAnimatedWebp(b: ByteArray) = isWebp(b) && at(b, 12, "VP8X") && b.size > 20 && (b[20].toInt() and 0x02) != 0

    /** From the first bytes of a file: a picture that may move. */
    fun mayMove(head: ByteArray) = isAnimatedWebp(head) || isGif(head)

    fun isGzip(b: ByteArray) = b.size > 2 && b[0] == 0x1F.toByte() && b[1] == 0x8B.toByte()
    private fun isZip(b: ByteArray) = at(b, 0, "PK")

    /**
     * A vector animation (Lottie) from any of its usual files — plain JSON, gzipped
     * (.tgs) or a dotLottie container — kept as gzipped JSON, the lightest to store
     * and send. Null when it is none, or larger than a sticker may be.
     */
    fun vector(b: ByteArray): ByteArray? {
        val json = when {
            isGzip(b) -> gunzip(b)
            isZip(b) -> dotLottieJson(b)
            else -> b
        } ?: return null
        if (json.size > VECTOR_MAX_JSON || !isLottieJson(json)) return null
        val fixed = fixRadials(String(json)).toByteArray()
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(out).use { it.write(fixed) }
        return out.toByteArray().takeIf { it.size <= ANIMATED_MAX_BYTES }
    }

    /** The JSON of a stored vector sticker (gzipped or plain), bounded and safe to draw. */
    fun vectorJson(b: ByteArray): String? = (if (isGzip(b)) gunzip(b) else b)?.let { fixRadials(String(it)) }

    /**
     * A radial gradient whose end sits on its start has no radius, and the renderer
     * throws drawing it. Every radial gradient's end is moved a hundredth of a pixel:
     * nothing to see, never a zero radius. Files without one are returned untouched.
     */
    fun fixRadials(json: String): String {
        if (!json.contains("\"gs\"") && !json.contains("\"gf\"")) return json
        val root = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return json
        var changed = false
        fun nudge(point: Any?): Boolean {
            val arr = point as? org.json.JSONArray ?: return false
            if (arr.length() < 1) return false
            arr.put(0, arr.optDouble(0) + RADIAL_NUDGE)
            return true
        }
        fun walk(o: Any?) {
            when (o) {
                is org.json.JSONObject -> {
                    val ty = o.optString("ty")
                    if ((ty == "gs" || ty == "gf") && o.optInt("t") == 2) {
                        val end = o.optJSONObject("e")
                        val k = end?.opt("k")
                        if (end?.optInt("a") == 1 && k is org.json.JSONArray) {
                            for (i in 0 until k.length()) {
                                val kf = k.optJSONObject(i) ?: continue
                                if (nudge(kf.opt("s"))) changed = true
                                if (nudge(kf.opt("e"))) changed = true
                            }
                        } else if (nudge(k)) changed = true
                    }
                    o.keys().forEach { walk(o.opt(it)) }
                }
                is org.json.JSONArray -> for (i in 0 until o.length()) walk(o.opt(i))
            }
        }
        walk(root)
        return if (changed) root.toString() else json
    }

    private const val RADIAL_NUDGE = 0.01

    private fun isLottieJson(json: ByteArray): Boolean {
        val head = String(json, 0, minOf(json.size, 64)).trimStart()
        return head.startsWith("{") && String(json).let { "\"layers\"" in it && "\"op\"" in it }
    }

    /** Inflated at most to [VECTOR_MAX_JSON]: a small file cannot unfold into a huge one. */
    private fun gunzip(b: ByteArray): ByteArray? = runCatching {
        java.util.zip.GZIPInputStream(b.inputStream()).use { readBounded(it, VECTOR_MAX_JSON) }
    }.getOrNull()

    /** A dotLottie's first animation (animations/<id>.json). */
    private fun dotLottieJson(b: ByteArray): ByteArray? = runCatching {
        java.util.zip.ZipInputStream(b.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: return@use null
                if (!e.isDirectory && e.name.startsWith("animations/") && e.name.endsWith(".json")) return@use readBounded(zip, VECTOR_MAX_JSON)
            }
            @Suppress("UNREACHABLE_CODE") null
        }
    }.getOrNull()

    private fun readBounded(input: java.io.InputStream, max: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return out.toByteArray()
            out.write(buf, 0, n)
            if (out.size() > max) return null
        }
    }

    /** The largest unpacked vector animation taken in. */
    const val VECTOR_MAX_JSON = 2 * 1024 * 1024

    /** [b] cleaned, if it is an animated WebP or a GIF of more than one frame; else null. */
    fun clean(b: ByteArray): ByteArray? = when {
        isAnimatedWebp(b) -> cleanWebp(b)
        isGif(b) -> cleanGif(b)?.takeIf { it.second > 1 }?.first
        else -> null
    }

    private fun cleanWebp(b: ByteArray): ByteArray? {
        val out = java.io.ByteArrayOutputStream(b.size)
        out.write(b, 0, 12)
        var i = 12
        while (i + 8 <= b.size) {
            val size = le32(b, i + 4)
            if (size < 0) return null
            val padded = size + (size and 1)
            if (i + 8 + padded > b.size) return null
            if (!at(b, i, "EXIF") && !at(b, i, "XMP ")) out.write(b, i, 8 + padded)
            i += 8 + padded
        }
        if (i != b.size) return null
        val r = out.toByteArray()
        putLe32(r, 4, r.size - 8)
        // No EXIF (0x08) or XMP (0x04) left: their flags go too.
        r[20] = (r[20].toInt() and 0x08.inv() and 0x04.inv()).toByte()
        return r
    }

    /** The GIF without comment, text and foreign application blocks, and its frame count. */
    private fun cleanGif(b: ByteArray): Pair<ByteArray, Int>? {
        if (b.size < 13) return null
        val out = java.io.ByteArrayOutputStream(b.size)
        var i = 13
        val packed = b[10].toInt() and 0xFF
        if (packed and 0x80 != 0) i += 3 * (1 shl ((packed and 0x07) + 1))
        if (i > b.size) return null
        out.write(b, 0, i)
        var frames = 0
        fun subBlocksEnd(from: Int): Int {
            var j = from
            while (j < b.size) {
                val n = b[j].toInt() and 0xFF
                j += 1 + n
                if (n == 0) return j
            }
            return -1
        }
        while (i < b.size) {
            when (b[i].toInt() and 0xFF) {
                0x3B -> { out.write(0x3B); return out.toByteArray() to frames }
                0x21 -> {
                    if (i + 2 > b.size) return null
                    val label = b[i + 1].toInt() and 0xFF
                    val end = subBlocksEnd(i + 2).takeIf { it > 0 } ?: return null
                    val keep = when (label) {
                        0xF9 -> true // frame timing
                        0xFF -> at(b, i + 3, "NETSCAPE2.0") || at(b, i + 3, "ANIMEXTS1.0") // looping
                        else -> false
                    }
                    if (keep) out.write(b, i, end - i)
                    i = end
                }
                0x2C -> {
                    if (i + 10 > b.size) return null
                    val local = b[i + 9].toInt() and 0xFF
                    var j = i + 10
                    if (local and 0x80 != 0) j += 3 * (1 shl ((local and 0x07) + 1))
                    j += 1 // LZW code size
                    val end = subBlocksEnd(j).takeIf { it > 0 && j <= b.size } ?: return null
                    out.write(b, i, end - i)
                    frames++
                    i = end
                }
                else -> return null
            }
        }
        return null
    }
}

/** What a picked picture turned out to be. */
sealed interface AnimatedPick {
    class Ok(val bytes: ByteArray) : AnimatedPick
    /** Moves, but is over [ANIMATED_MAX_BYTES] or [ANIMATED_MAX_SIDE]. */
    object TooBig : AnimatedPick
}

/** The picture at [uri] as an animated sticker, or null when it does not move (the photo editor takes it). */
fun readAnimated(ctx: android.content.Context, uri: android.net.Uri): AnimatedPick? = runCatching {
    ctx.contentResolver.openInputStream(uri)?.use { input ->
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        while (buf.size() <= ANIMATED_MAX_BYTES) {
            val n = input.read(chunk)
            if (n < 0) break
            buf.write(chunk, 0, n)
            if (buf.size() >= 32 && !AnimatedSticker.mayMove(buf.toByteArray().copyOf(32))) return@use null
        }
        val all = buf.toByteArray()
        if (!AnimatedSticker.mayMove(all)) return@use null
        val bytes = AnimatedSticker.clean(all.copyOf(minOf(all.size, ANIMATED_MAX_BYTES))).takeIf { all.size <= ANIMATED_MAX_BYTES }
        if (all.size > ANIMATED_MAX_BYTES) {
            // Too big to keep: whether it moves at all decides between "too big" and the editor.
            return@use if (AnimatedSticker.isAnimatedWebp(all) || AnimatedSticker.isGif(all)) AnimatedPick.TooBig else null
        }
        bytes ?: return@use null
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (maxOf(o.outWidth, o.outHeight) > ANIMATED_MAX_SIDE) AnimatedPick.TooBig else AnimatedPick.Ok(bytes)
    }
}.getOrNull()
