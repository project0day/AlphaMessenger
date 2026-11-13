package com.alphamessenger.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Reads received media kept sealed on disk (the core writes it so).
 *
 * A sealed file is `AMV1` + a 32-byte salt, then the content under AES-256-CTR
 * with the key HMAC-SHA256(vault key, salt). The vault key comes from the
 * encrypted database at unlock and is held only in memory. Files that are not
 * sealed (our own originals, older cache) read as they are.
 */
object MediaVault {
    private val MAGIC = byteArrayOf('A'.code.toByte(), 'M'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())
    private const val SALT_LEN = 32
    const val HEADER_LEN = 4 + SALT_LEN

    @Volatile private var key: ByteArray? = null

    fun install(vaultKey: ByteArray) {
        key = vaultKey.takeIf { it.size == 32 }?.copyOf()
    }

    fun isSealed(file: File): Boolean = runCatching {
        if (file.length() < HEADER_LEN) return false
        val head = ByteArray(4)
        file.inputStream().use { it.read(head) } == 4 && head.contentEquals(MAGIC)
    }.getOrDefault(false)

    /** The content length (the file's, less the header when sealed). */
    fun length(file: File): Long = if (isSealed(file)) file.length() - HEADER_LEN else file.length()

    internal fun fileKey(salt: ByteArray): SecretKeySpec {
        val k = key ?: throw IllegalStateException("media vault locked")
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(k, "HmacSHA256")) }
        return SecretKeySpec(mac.doFinal(salt), "AES")
    }

    /** A CTR cipher standing at content [offset]. */
    internal fun cipherAt(fileKey: SecretKeySpec, offset: Long, mode: Int = Cipher.DECRYPT_MODE): Cipher {
        val block = offset / 16
        val iv = ByteArray(16)
        var b = block
        for (i in 15 downTo 8) { iv[i] = (b and 0xFF).toByte(); b = b ushr 8 }
        val c = Cipher.getInstance("AES/CTR/NoPadding").apply { init(mode, fileKey, IvParameterSpec(iv)) }
        val skip = (offset % 16).toInt()
        if (skip > 0) c.update(ByteArray(skip))
        return c
    }

    internal fun saltOf(file: File): ByteArray = RandomAccessFile(file, "r").use { f ->
        ByteArray(SALT_LEN).also { f.seek(4); f.readFully(it) }
    }

    /** The content from [offset] on, decrypted as it is read. */
    fun open(file: File, offset: Long = 0): InputStream {
        if (!isSealed(file)) return file.inputStream().also { if (offset > 0) it.skipFully(offset) }
        val fk = fileKey(saltOf(file))
        val raw = file.inputStream().also { it.skipFully(HEADER_LEN + offset) }
        return CtrInputStream(raw, cipherAt(fk, offset))
    }

    fun readBytes(file: File): ByteArray = open(file).use { it.readBytes() }

    /** [BitmapFactory.decodeFile] for sealed and plain files alike. */
    fun decodeFile(path: String?, opts: BitmapFactory.Options? = null): Bitmap? = runCatching {
        open(File(path ?: return null)).use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrNull()

    /** A new sealed file at [file], written through the returned stream. */
    fun sealedOutput(file: File): java.io.OutputStream {
        val salt = ByteArray(SALT_LEN).also { java.security.SecureRandom().nextBytes(it) }
        val fk = fileKey(salt)
        val out = file.outputStream()
        out.write(MAGIC)
        out.write(salt)
        return javax.crypto.CipherOutputStream(out, cipherAt(fk, 0, Cipher.ENCRYPT_MODE))
    }

    fun writeSealed(file: File, bytes: ByteArray) {
        sealedOutput(file).use { it.write(bytes) }
    }

    /** A retriever, player or extractor reading [path], sealed or plain. */
    fun setSource(r: android.media.MediaMetadataRetriever, path: String?) {
        val f = File(path ?: throw IllegalArgumentException("no media path"))
        if (isSealed(f)) r.setDataSource(SealedMediaSource(f)) else r.setDataSource(path)
    }

    fun setSource(p: android.media.MediaPlayer, path: String?) {
        val f = File(path ?: throw IllegalArgumentException("no media path"))
        if (isSealed(f)) p.setDataSource(SealedMediaSource(f)) else p.setDataSource(path)
    }

    fun setSource(e: android.media.MediaExtractor, path: String?) {
        val f = File(path ?: throw IllegalArgumentException("no media path"))
        if (isSealed(f)) e.setDataSource(SealedMediaSource(f)) else e.setDataSource(path)
    }

    /** Random access to a sealed file's content for the platform media classes. */
    private class SealedMediaSource(file: File) : android.media.MediaDataSource() {
        private val raf = RandomAccessFile(file, "r")
        private val fk = fileKey(saltOf(file))
        private val size = file.length() - HEADER_LEN

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= this.size) return -1
            val n = minOf(size.toLong(), this.size - position).toInt()
            synchronized(raf) {
                raf.seek(HEADER_LEN + position)
                raf.readFully(buffer, offset, n)
            }
            cipherAt(fk, position).update(buffer, offset, n, buffer, offset)
            return n
        }

        override fun getSize(): Long = size

        override fun close() = raf.close()
    }

    /**
     * Seal what was cached before the vault existed. Complete files and whole
     * stream chunks only: a core download's `.part` restarts sealed by itself.
     * Dates are kept, the cache trim orders by them. Returns how many were sealed.
     */
    fun sealExisting(dirs: List<File>): Int {
        if (key == null) return 0
        var n = 0
        for (dir in dirs) {
            for (f in dir.listFiles().orEmpty()) {
                if (!f.isFile || f.length() == 0L || isSealed(f)) continue
                val name = f.name
                val chunk = Regex("""_c\d+\.part$""").containsMatchIn(name)
                if (!chunk && (name.endsWith(".part") || name.endsWith("tmp"))) continue
                runCatching {
                    val tmp = File(dir, "$name.seal.tmp")
                    f.inputStream().use { i -> sealedOutput(tmp).use { i.copyTo(it, 1 shl 16) } }
                    tmp.setLastModified(f.lastModified())
                    if (!tmp.renameTo(f)) tmp.delete() else n++
                }
            }
        }
        return n
    }

    /** A plain copy of [src] at [dest] (for another app or the gallery). */
    fun copyPlain(src: File, dest: File) {
        open(src).use { i -> dest.outputStream().use { i.copyTo(it, 1 shl 16) } }
    }

    private fun InputStream.skipFully(n: Long) {
        var left = n
        while (left > 0) {
            val s = skip(left)
            if (s <= 0) { if (read() < 0) break else left-- } else left -= s
        }
    }

    private class CtrInputStream(raw: InputStream, private val cipher: Cipher) : FilterInputStream(raw) {
        private val one = ByteArray(1)

        override fun read(): Int = if (read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) cipher.update(b, off, n, b, off)
            return n
        }

        // Skipping must move the keystream too: read and drop.
        override fun skip(n: Long): Long {
            val buf = ByteArray(8192)
            var left = n
            while (left > 0) {
                val r = read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (r < 0) break
                left -= r
            }
            return n - left
        }

        override fun markSupported() = false
    }
}
