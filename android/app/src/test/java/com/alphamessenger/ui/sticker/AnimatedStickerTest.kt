package com.alphamessenger.ui.sticker

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class AnimatedStickerTest {
    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
    private fun chunk(id: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray()); out.write(le32(data.size)); out.write(data)
        if (data.size % 2 == 1) out.write(0)
        return out.toByteArray()
    }
    private fun webp(flags: Int, vararg chunks: ByteArray): ByteArray {
        val vp8x = ByteArray(10).also { it[0] = flags.toByte() }
        val body = ByteArrayOutputStream().apply {
            write("WEBP".toByteArray()); write(chunk("VP8X", vp8x)); chunks.forEach { write(it) }
        }.toByteArray()
        return "RIFF".toByteArray() + le32(body.size) + body
    }

    @Test
    fun `an animated webp loses its exif and xmp and their flags, and nothing else`() {
        val anim = chunk("ANIM", ByteArray(6)); val frame = chunk("ANMF", ByteArray(17) { 7 })
        val dirty = webp(0x02 or 0x08 or 0x04, anim, chunk("EXIF", "GPS 48.85,2.35".toByteArray()), frame, chunk("XMP ", ByteArray(3)))
        val clean = AnimatedSticker.clean(dirty)
        assertNotNull(clean)
        assertArrayEquals(webp(0x02, anim, frame), clean)
        assertTrue(AnimatedSticker.isAnimatedWebp(clean!!))
    }

    @Test
    fun `a still webp is not an animated sticker`() {
        assertNull(AnimatedSticker.clean(webp(0x10, chunk("VP8L", ByteArray(5)))))
        assertFalse(AnimatedSticker.mayMove(webp(0x10)))
    }

    @Test
    fun `a broken webp is refused`() {
        val good = webp(0x02, chunk("ANMF", ByteArray(8)))
        assertNull(AnimatedSticker.clean(good.copyOf(good.size - 3)))
    }

    private fun gif(vararg blocks: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("GIF89a".toByteArray())
        out.write(byteArrayOf(1, 0, 1, 0, 0x80.toByte(), 0, 0)) // 1x1, global table of 2 colours
        out.write(ByteArray(6))
        blocks.forEach { out.write(it) }
        out.write(0x3B)
        return out.toByteArray()
    }
    private val loop = byteArrayOf(0x21, 0xFF.toByte(), 11) + "NETSCAPE2.0".toByteArray() + byteArrayOf(3, 1, 0, 0, 0)
    private val timing = byteArrayOf(0x21, 0xF9.toByte(), 4, 0, 10, 0, 0, 0)
    private val comment = byteArrayOf(0x21, 0xFE.toByte(), 5) + "hello".toByteArray() + byteArrayOf(0)
    private val frame = byteArrayOf(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 0x01, 0)

    @Test
    fun `a moving gif keeps frames, timing and looping and loses its comment`() {
        val clean = AnimatedSticker.clean(gif(loop, comment, timing, frame, timing, frame))
        assertArrayEquals(gif(loop, timing, frame, timing, frame), clean)
    }

    @Test
    fun `a gif of one frame is left to the photo editor`() {
        assertNull(AnimatedSticker.clean(gif(timing, frame)))
        assertEquals(true, AnimatedSticker.mayMove(gif(timing, frame)))
    }
}

class VectorStickerTest {
    private val lottie = """{"v":"5.5.2","fr":30,"ip":0,"op":42,"w":512,"h":512,"layers":[{"ty":3}]}""".toByteArray()
    private fun gz(b: ByteArray) = java.io.ByteArrayOutputStream().also { o -> java.util.zip.GZIPOutputStream(o).use { it.write(b) } }.toByteArray()
    private fun ungz(b: ByteArray) = java.util.zip.GZIPInputStream(b.inputStream()).readBytes()
    private fun dotLottie(json: ByteArray) = java.io.ByteArrayOutputStream().also { o ->
        java.util.zip.ZipOutputStream(o).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("manifest.json")); z.write("""{"animations":[{"id":"a"}]}""".toByteArray())
            z.putNextEntry(java.util.zip.ZipEntry("animations/a.json")); z.write(json)
        }
    }.toByteArray()

    @Test
    fun `plain, gzipped and dotLottie animations all come out as the same gzipped json`() {
        for (src in listOf(lottie, gz(lottie), dotLottie(lottie))) {
            val out = AnimatedSticker.vector(src)
            org.junit.Assert.assertNotNull(out)
            org.junit.Assert.assertTrue(AnimatedSticker.isGzip(out!!))
            org.junit.Assert.assertArrayEquals(lottie, ungz(out))
            org.junit.Assert.assertEquals(String(lottie), AnimatedSticker.vectorJson(out))
        }
    }

    @Test
    fun `json that is no animation, and pictures, are not vector stickers`() {
        org.junit.Assert.assertNull(AnimatedSticker.vector("""{"name":"pack","items":[]}""".toByteArray()))
        org.junit.Assert.assertNull(AnimatedSticker.vector("GIF89a....".toByteArray()))
        org.junit.Assert.assertNull(AnimatedSticker.vector(ByteArray(0)))
    }

    @Test
    fun `a small file that unpacks into a huge one is refused`() {
        val huge = ByteArray(AnimatedSticker.VECTOR_MAX_JSON + 10) { ' '.code.toByte() }
        org.junit.Assert.assertNull(AnimatedSticker.vector(gz(huge)))
    }
}

class RadialFixTest {
    private fun anim(gradient: String) = """{"v":"5.5.2","op":30,"layers":[{"ty":4,"shapes":[$gradient]}]}"""

    @Test
    fun `a radial gradient with its end on its start gets a radius`() {
        val fixed = org.json.JSONObject(AnimatedSticker.fixRadials(anim(
            """{"ty":"gs","t":2,"s":{"a":0,"k":[-0.693,-0.894]},"e":{"a":0,"k":[-0.693,-0.894]}}""")))
        val g = fixed.getJSONArray("layers").getJSONObject(0).getJSONArray("shapes").getJSONObject(0)
        val s = g.getJSONObject("s").getJSONArray("k"); val e = g.getJSONObject("e").getJSONArray("k")
        org.junit.Assert.assertNotEquals(s.getDouble(0), e.getDouble(0), 0.0)
        org.junit.Assert.assertEquals(s.getDouble(1), e.getDouble(1), 0.0)
    }

    @Test
    fun `animated ends move in every keyframe, linear gradients and plain files stay as they are`() {
        val fixed = org.json.JSONObject(AnimatedSticker.fixRadials(anim(
            """{"ty":"gf","t":2,"e":{"a":1,"k":[{"t":0,"s":[1,1]},{"t":10,"s":[2,2]}]}}""")))
        val k = fixed.getJSONArray("layers").getJSONObject(0).getJSONArray("shapes").getJSONObject(0).getJSONObject("e").getJSONArray("k")
        org.junit.Assert.assertEquals(1.01, k.getJSONObject(0).getJSONArray("s").getDouble(0), 1e-9)
        org.junit.Assert.assertEquals(2.01, k.getJSONObject(1).getJSONArray("s").getDouble(0), 1e-9)
        val linear = anim("""{"ty":"gs","t":1,"e":{"a":0,"k":[3,3]}}""")
        org.junit.Assert.assertEquals(linear, AnimatedSticker.fixRadials(linear))
        val plain = anim("""{"ty":"fl"}""")
        org.junit.Assert.assertSame(plain, AnimatedSticker.fixRadials(plain))
    }
}
