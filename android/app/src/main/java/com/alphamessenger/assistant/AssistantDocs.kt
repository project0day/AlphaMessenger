package com.alphamessenger.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.alphamessenger.data.MediaVault
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * A file the user sends an assistant, made readable on the phone: text and code of any
 * kind, office documents, e-books, archives (zip, tar, gz) and PDF pages as pictures (the
 * phone has no PDF text reader). Every read is capped, so no archive unpacks into more
 * than the model is sent.
 */
object AssistantDocs {
    class Doc(val text: String, val images: List<String> = emptyList())

    /** [f] as the model should see it, or null for a kind of file that holds no text. */
    fun read(ctx: Context, f: File, name: String, picture: (Bitmap) -> String): Doc? {
        val lower = name.lowercase()
        val ext = lower.substringAfterLast('.', "")
        return when {
            ext in setOf("docx", "docm", "dotx") -> office(f) { docx(it) }
            ext in setOf("xlsx", "xlsm") -> office(f) { xlsx(it) }
            ext in setOf("pptx", "pptm") -> office(f) { pptx(it) }
            ext in setOf("odt", "ods", "odp", "odg") -> office(f) { z -> z["content.xml"]?.let { odf(utf8(it)) } }
            ext == "epub" -> office(f) { z -> epub(z) }
            ext == "pdf" -> pdf(ctx, f, picture)
            lower.endsWith(".tar.gz") || ext == "tgz" -> Doc(archive(name, tarEntries(GZIPInputStream(MediaVault.open(f)))))
            ext == "tar" -> Doc(archive(name, tarEntries(MediaVault.open(f))))
            ext in setOf("zip", "jar", "apk", "aar", "whl", "nupkg") -> Doc(archive(name, zipEntries(MediaVault.open(f))))
            ext == "gz" -> GZIPInputStream(MediaVault.open(f)).use { s -> textOf(capped(s), lower.removeSuffix(".gz"))?.let(::Doc) }
            ext == "rtf" -> textOf(MediaVault.open(f).use(::capped), lower)?.let { Doc(rtf(it)) }
            else -> MediaVault.open(f).use { s -> textOf(capped(s), lower)?.let { Doc(fenced(it, ext)) } }
        }?.let { Doc(it.text.take(MAX_CHARS), it.images) }
    }

    // --- Text ---

    /** Bytes as text when they are text: UTF-8/16 or a one-byte Cyrillic page; never binary. */
    private fun textOf(b: ByteArray, name: String): String? {
        if (b.size >= 2 && ((b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) || (b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()))) {
            return String(b, Charsets.UTF_16)
        }
        val head = b.copyOf(minOf(b.size, 8192))
        if (head.any { it == 0.toByte() }) return null
        val control = head.count { val c = it.toInt() and 0xFF; c < 0x09 || c in 0x0E..0x1F }
        if (head.isNotEmpty() && control * 50 > head.size) return null
        val text = strict(b, Charsets.UTF_8) ?: String(b, Charset.forName("windows-1251"))
        val html = name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".xhtml")
        return if (html) html(text) else text.removePrefix("\uFEFF")
    }

    private fun strict(b: ByteArray, cs: Charset): String? = try {
        cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(b)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun utf8(b: ByteArray) = String(b, Charsets.UTF_8)

    private fun fenced(text: String, ext: String) = "```" + ext.takeIf { it.length in 1..8 }.orEmpty() + "\n" + text.take(MAX_CHARS) + "\n```"

    // --- Archives ---

    private class Entry(val path: String, val size: Long, val bytes: ByteArray?)

    private fun zipEntries(input: InputStream): List<Entry> = ZipInputStream(input).use { z ->
        val out = ArrayList<Entry>()
        var total = 0L
        while (true) {
            val e = z.nextEntry ?: break
            if (e.isDirectory) continue
            val bytes = if (total < MAX_TOTAL_BYTES && wanted(e.name)) capped(z).also { total += it.size } else null
            out += Entry(e.name, if (e.size >= 0) e.size else (bytes?.size?.toLong() ?: -1), bytes)
            if (out.size >= MAX_ENTRIES) break
        }
        out
    }

    /** POSIX tar: 512-byte headers, contents padded to 512. */
    private fun tarEntries(input: InputStream): List<Entry> = input.use { s ->
        val out = ArrayList<Entry>()
        val header = ByteArray(512)
        var total = 0L
        while (out.size < MAX_ENTRIES) {
            if (!fill(s, header)) break
            if (header.all { it == 0.toByte() }) break
            val name = field(header, 0, 100)
            val prefix = if (field(header, 257, 6).startsWith("ustar")) field(header, 345, 155) else ""
            val path = if (prefix.isNotEmpty()) "$prefix/$name" else name
            val size = field(header, 124, 12).trim().toLongOrNull(8) ?: 0L
            val type = header[156].toInt().toChar()
            val file = type == '0' || type == '\u0000'
            val keep = file && total < MAX_TOTAL_BYTES && wanted(path) && size <= MAX_ENTRY_BYTES
            val bytes = if (keep) ByteArray(size.toInt()).also { if (!fill(s, it)) return@use out; total += size } else { skip(s, size); null }
            skip(s, (512 - size % 512) % 512)
            if (file) out += Entry(path, size, bytes)
        }
        out
    }

    private fun field(h: ByteArray, at: Int, len: Int): String =
        String(h, at, len, Charsets.UTF_8).substringBefore('\u0000')

    private fun fill(s: InputStream, buf: ByteArray): Boolean {
        var n = 0
        while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r < 0) return false; n += r }
        return true
    }

    private fun skip(s: InputStream, n: Long) {
        var left = n
        val buf = ByteArray(8192)
        while (left > 0) { val r = s.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (r < 0) return; left -= r }
    }

    /** An archive: what is in it, then the text of the files that are text, while room is left. */
    private fun archive(name: String, entries: List<Entry>): String = buildString {
        append("Archive ").append(name).append(": ").append(entries.size).append(if (entries.size >= MAX_ENTRIES) "+ files\n" else " files\n")
        entries.take(MAX_LISTED).forEach { append("- ").append(it.path).append(" (").append(sizeOf(it.size)).append(")\n") }
        for (e in entries) {
            if (length >= MAX_CHARS) break
            val bytes = e.bytes ?: continue
            val lower = e.path.lowercase()
            val ext = lower.substringAfterLast('.', "")
            val text = when {
                ext == "docx" || ext == "xlsx" || ext == "pptx" -> null // a document inside an archive: listed only
                else -> textOf(bytes, lower)
            } ?: continue
            append("\n--- ").append(e.path).append(" ---\n").append(text.take(MAX_CHARS - length)).append('\n')
        }
    }

    private fun wanted(path: String): Boolean {
        val ext = path.lowercase().substringAfterLast('.', "")
        return ext !in BINARY
    }

    private fun sizeOf(n: Long): String = when {
        n < 0 -> "?"
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> "${n / 1024} KB"
        else -> "${n / 1024 / 1024} MB"
    }

    /** Up to [MAX_ENTRY_BYTES] of [s]; the rest is left unread. */
    private fun capped(s: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < MAX_ENTRY_BYTES) {
            val r = s.read(buf, 0, minOf(buf.size, MAX_ENTRY_BYTES - out.size()))
            if (r < 0) break
            out.write(buf, 0, r)
        }
        return out.toByteArray()
    }

    // --- Office documents and e-books (zip of XML) ---

    private fun office(f: File, read: (Map<String, ByteArray>) -> String?): Doc? {
        val parts = zipEntries(MediaVault.open(f)).filter { it.bytes != null }.associate { it.path to it.bytes!! }
        return read(parts)?.takeIf { it.isNotBlank() }?.let(::Doc)
    }

    private fun docx(z: Map<String, ByteArray>): String? = z["word/document.xml"]?.let { b ->
        xml(utf8(b).replace(Regex("</w:p>"), "\n").replace(Regex("<w:tab/>"), "\t").replace(Regex("<w:br[^>]*/>"), "\n"))
    }

    private fun pptx(z: Map<String, ByteArray>): String = z.keys
        .mapNotNull { k -> Regex("""ppt/slides/slide(\d+)\.xml""").matchEntire(k)?.let { it.groupValues[1].toInt() to k } }
        .sortedBy { it.first }
        .joinToString("\n\n") { (n, k) ->
            "Slide $n:\n" + xml(utf8(z.getValue(k)).replace("</a:p>", "\n")).trim()
        }

    private fun xlsx(z: Map<String, ByteArray>): String {
        val shared = z["xl/sharedStrings.xml"]?.let { b ->
            Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL).findAll(utf8(b)).map { si ->
                Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).findAll(si.groupValues[1]).joinToString("") { unescape(it.groupValues[1]) }
            }.toList()
        }.orEmpty()
        val sheets = z.keys
            .mapNotNull { k -> Regex("""xl/worksheets/sheet(\d+)\.xml""").matchEntire(k)?.let { it.groupValues[1].toInt() to k } }
            .sortedBy { it.first }
        val cell = Regex("""<c\b([^>]*?)(?:/>|>(.*?)</c>)""", RegexOption.DOT_MATCHES_ALL)
        return buildString {
            for ((n, k) in sheets) {
                append("Sheet ").append(n).append(":\n")
                for (row in Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL).findAll(utf8(z.getValue(k)))) {
                    val values = cell.findAll(row.groupValues[1]).map { c ->
                        val attrs = c.groupValues[1]
                        val body = c.groupValues[2]
                        val v = Regex("<v>(.*?)</v>").find(body)?.groupValues?.get(1)
                        when {
                            attrs.contains("t=\"s\"") -> v?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                            attrs.contains("t=\"inlineStr\"") -> Regex("<t[^>]*>(.*?)</t>").find(body)?.groupValues?.get(1)?.let(::unescape).orEmpty()
                            else -> v?.let(::unescape).orEmpty()
                        }
                    }.toList()
                    if (values.any { it.isNotBlank() }) append(values.joinToString("\t")).append('\n')
                    if (length >= MAX_CHARS) return@buildString
                }
                append('\n')
            }
        }
    }

    private fun odf(content: String): String =
        xml(content.replace(Regex("</text:(p|h)>"), "\n").replace("<text:tab/>", "\t").replace("<text:line-break/>", "\n"))

    private fun epub(z: Map<String, ByteArray>): String = z.keys
        .filter { it.endsWith(".xhtml") || it.endsWith(".html") || it.endsWith(".htm") }
        .sorted()
        .joinToString("\n\n") { html(utf8(z.getValue(it))) }

    // --- PDF: pages as pictures ---

    private fun pdf(ctx: Context, f: File, picture: (Bitmap) -> String): Doc? {
        // The renderer needs a plain file: media rest encrypted on the phone.
        val tmp = File(ctx.cacheDir, "assistant_pdf_${System.nanoTime()}.pdf")
        return try {
            MediaVault.copyPlain(f, tmp)
            ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { r ->
                    val shown = minOf(r.pageCount, PDF_PAGES)
                    val images = (0 until shown).map { i ->
                        r.openPage(i).use { page ->
                            val w = PDF_WIDTH
                            val h = (w.toFloat() * page.height / page.width).toInt().coerceIn(1, w * 3)
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            picture(bmp).also { bmp.recycle() }
                        }
                    }
                    Doc("PDF, ${r.pageCount} pages; pages 1–$shown are attached as pictures" +
                        (if (r.pageCount > shown) " (ask the user to send the rest as pictures if needed)" else "") + ".", images)
                }
            }
        } catch (_: Exception) {
            null // a locked or broken PDF
        } finally {
            tmp.delete()
        }
    }

    // --- Markup ---

    private fun rtf(s: String): String = s
        .replace(Regex("""\\par[d]?\b"""), "\n")
        .replace(Regex("""\\'([0-9a-fA-F]{2})""")) { it.groupValues[1].toInt(16).toChar().toString() }
        .replace(Regex("""\\[a-zA-Z]+-?\d* ?"""), "")
        .replace(Regex("[{}]"), "")
        .trim()

    private fun html(s: String): String = xml(
        s.replace(Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>|</h[1-6]>|</tr>"), "\n"),
    ).replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n\n").trim()

    private fun xml(s: String): String = unescape(s.replace(Regex("<[^>]+>"), ""))

    private fun unescape(s: String): String = s
        .replace(Regex("&#x([0-9a-fA-F]+);")) { runCatching { String(Character.toChars(it.groupValues[1].toInt(16))) }.getOrDefault("") }
        .replace(Regex("&#(\\d+);")) { runCatching { String(Character.toChars(it.groupValues[1].toInt())) }.getOrDefault("") }
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&nbsp;", " ").replace("&amp;", "&")

    /** What the model is sent of one file, at most (about 10 000 tokens). */
    private const val MAX_CHARS = 40_000
    private const val MAX_ENTRY_BYTES = 2_000_000
    private const val MAX_TOTAL_BYTES = 8_000_000L
    private const val MAX_ENTRIES = 2_000
    private const val MAX_LISTED = 150
    private const val PDF_PAGES = 6
    private const val PDF_WIDTH = 1100

    /** Never read as text inside an archive. */
    private val BINARY = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "heic", "mp3", "mp4", "m4a", "ogg", "wav", "mov", "mkv", "webm",
        "so", "dll", "exe", "class", "dex", "bin", "o", "a", "jar", "zip", "gz", "7z", "rar", "pdf", "ttf", "otf", "woff", "woff2",
    )
}
