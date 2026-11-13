package com.alphamessenger.export

import android.content.Context
import android.util.JsonWriter
import com.alphamessenger.R
import com.alphamessenger.data.AlphaCoreRepository
import com.alphamessenger.data.MediaVault
import com.alphamessenger.ui.chat.MessageUi
import com.alphamessenger.ui.chat.MessageUiLabels
import com.alphamessenger.ui.chat.localZdt
import com.alphamessenger.ui.chat.toMessageUi
import com.alphamessenger.util.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStream
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/**
 * Writes chats into one ZIP: readable pages (HTML, attachments beside them)
 * and/or JSON for programs. Each chat's pages and JSON grow in temporary
 * files while its attachments stream into the archive, so memory stays flat
 * whatever the size of the chat.
 */
class ChatExporter(
    private val context: Context,
    private val repository: AlphaCoreRepository,
    private val options: ExportOptions,
    private val onProgress: (ExportProgress.State.Running) -> Unit,
) {
    data class Summary(val chats: Int, val messages: Int, val files: Int, val missing: Int)

    private class Target(val id: String, val title: String, val type: String, val dir: String)

    private class Attachment(
        val kind: MediaKind, val label: String, val path: String?, val name: String?, val size: Long?,
        val audio: Boolean, val state: State, val durationMs: Long?, val width: Int?, val height: Int?,
    ) { enum class State { OK, SKIPPED, MISSING } }

    private class Record(
        val id: String, val at: ZonedDateTime?, val from: String, val fromId: String, val out: Boolean,
        val text: String, val forwarded: Boolean, val edited: Boolean,
        val replyTo: String?, val replySnippet: String?,
        val attachments: List<Attachment>, val sticker: String?,
        val location: JSONObject?, val poll: JSONObject?, val link: JSONObject?,
        val reactions: List<Pair<String, Int>>,
    ) {
        val snippet: String get() = text.ifBlank { attachments.firstOrNull()?.label ?: sticker.orEmpty() }.take(80)
    }

    private class ChatResult(val json: File?, val firstPage: String, val count: Int)

    private val labels = MessageUiLabels(Strings(context))
    private val dayFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    private val root = (if (options.singleChat) "ChatExport_" else "AlphaExport_") + LocalDate.now()
    private val tmp = File(context.cacheDir, "export_tmp")
    private val maxBytes = if (options.maxFileMb > 0) options.maxFileMb * 1024L * 1024L else Long.MAX_VALUE
    private val senderNames = HashMap<String, String>()
    private var myName: String? = null
    private var messages = 0
    private var files = 0
    private var missing = 0

    suspend fun run(out: OutputStream): Summary = withContext(Dispatchers.IO) {
        tmp.deleteRecursively()
        tmp.mkdirs()
        try {
            val targets = targets()
            ZipOutputStream(BufferedOutputStream(out, 256 * 1024)).use { zip ->
                // Photos and videos are compressed already: spend little time on them.
                zip.setLevel(Deflater.BEST_SPEED)
                if (options.html) putText(zip, "style.css", EXPORT_CSS)
                val results = ArrayList<Pair<Target, ChatResult>>()
                targets.forEachIndexed { i, t ->
                    ensureActive()
                    results += t to exportChat(zip, t, i, targets.size)
                }
                finish(zip, results)
            }
            Summary(targets.size, messages, files, missing)
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** One chat: its JSON is the archive's result.json. The profile: an index page and one JSON for all. */
    private suspend fun finish(zip: ZipOutputStream, results: List<Pair<Target, ChatResult>>) {
        if (options.singleChat) {
            results.firstOrNull()?.second?.json?.let { putFile(zip, it, "result.json") }
            return
        }
        val me = if (options.profile) runCatching { repository.getMyProfile() }.getOrNull() else null
        val avatar = me?.avatarPath?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isFile }
            ?.let { runCatching { copyFile(zip, it, "profile/avatar.jpg"); "profile/avatar.jpg" }.getOrNull() }
        val contacts = if (options.contacts) contacts() else emptyList()
        if (options.html) putText(zip, "index.html", indexHtml(me, avatar, contacts, results))
        if (options.json) putResultJson(zip, me, avatar, contacts, results.mapNotNull { it.second.json })
    }

    // --- What goes in ---------------------------------------------------------

    private suspend fun targets(): List<Target> {
        val saved = repository.savedMessagesChatId
        val ownNpub = runCatching { repository.getIdentityInfo().npub }.getOrDefault("")
        val out = ArrayList<Target>()
        for (c in repository.listChats()) {
            if (options.chatId != null && c.id != options.chatId) continue
            // Secret chats live on this phone only; they never leave it in a file.
            if (repository.secretChatOrigin(c.id).isNotEmpty()) continue
            val cfg = if (c.chatType == "group") repository.channelCfg(c.id) else null
            val type = when {
                c.id == saved -> "saved"
                c.chatType != "group" -> "personal"
                cfg?.on == true -> "channel"
                else -> "group"
            }
            if (options.chatId == null && !when (type) {
                    "saved" -> options.saved
                    "personal" -> options.personal
                    "channel" -> options.channels
                    else -> options.groups
                }) continue
            // Copying forbidden by the owner: only its admins may take it out.
            if (cfg?.protect == true && c.myRole != "owner" && c.myRole != "admin") continue
            val title = when (type) {
                "saved" -> context.getString(R.string.menu_saved)
                "personal" -> directTitle(c.id, c.name, ownNpub)
                else -> c.name?.takeIf { it.isNotBlank() } ?: context.getString(R.string.group_chat)
            }
            out += Target(c.id, title, type, if (options.singleChat) "" else "chats/chat_%03d/".format(out.size + 1))
        }
        return out
    }

    private suspend fun directTitle(chatId: String, name: String?, ownNpub: String): String {
        val peer = runCatching { repository.resolvePeerSenderId(chatId, ownNpub) }.getOrDefault("")
        val prof = peer.takeIf { it.isNotBlank() }?.let { runCatching { repository.getPeerProfile(it) }.getOrNull() }
        return prof?.displayName?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
            ?: runCatching { repository.getPeerNpubForChat(chatId) }.getOrDefault("").let { if (it.length > 16) it.take(12) + "…" else it }
            .ifBlank { context.getString(R.string.peer_fallback) }
    }

    private suspend fun contacts(): List<Pair<String, String>> {
        val ownNpub = runCatching { repository.getIdentityInfo().npub }.getOrDefault("")
        return repository.contacts().map { (chatId, npub) -> directTitle(chatId, null, ownNpub) to npub }.sortedBy { it.first.lowercase() }
    }

    // --- One chat ---------------------------------------------------------------

    private suspend fun exportChat(zip: ZipOutputStream, t: Target, index: Int, total: Int): ChatResult {
        val ownId = repository.ownMemberId(t.id)
        val reactions = runCatching { JSONObject(repository.exportReactionsJson(t.id)) }.getOrNull()
        val html = if (options.html) HtmlPages(t, if (options.singleChat) "style.css" else "../../style.css") else null
        val jsonFile = if (options.json) File(tmp, "chat_$index.json") else null
        val json = jsonFile?.let { JsonWriter(it.bufferedWriter()).apply { setIndent(" ") } }
        json?.beginObject()?.name("name")?.value(t.title)?.name("type")?.value(t.type)?.name("id")?.value(t.id)
        json?.name("messages")?.beginArray()
        val snippets = HashMap<String, String>()
        val usedNames = HashSet<String>()
        var afterAt = ""
        var afterId = ""
        var count = 0
        report(t, index, total)
        try {
            while (true) {
                coroutineContext.ensureActive()
                val page = repository.exportMessagesPage(t.id, afterAt, afterId, options.sinceSec, options.untilSec, PAGE_FETCH)
                if (page.isEmpty()) break
                for (info in page) {
                    afterAt = info.createdAt
                    afterId = info.id
                    val ui = info.toMessageUi(ownId, labels)
                    // A timer change or a secret-chat invitation is not a message of the conversation.
                    if (ui.timerSeconds != null || ui.secretInvite != null) continue
                    val quoted = info.replyTo?.takeIf { it.isNotBlank() }
                    val r = record(zip, t, ui, info.senderId, reactions?.optJSONObject(info.id), usedNames,
                        quoted, quoted?.let { snippets[it] ?: info.mediaRef.takeIf { info.msgType == uniffi.alphamessenger.MessageType.REPLY } })
                    html?.add(r)
                    json?.let { writeJson(it, r) }
                    snippets[r.id] = r.snippet
                    messages++
                    count++
                    if (count % 25 == 0) report(t, index, total)
                }
            }
        } finally {
            runCatching { json?.endArray()?.endObject() }
            runCatching { json?.close() }
        }
        val first = html?.finish(zip) ?: ""
        report(t, index, total)
        return ChatResult(jsonFile, first, count)
    }

    private fun report(t: Target, index: Int, total: Int) =
        onProgress(ExportProgress.State.Running(t.title, index + 1, total, messages, files))

    private suspend fun record(
        zip: ZipOutputStream, t: Target, ui: MessageUi, senderId: String, reacts: JSONObject?,
        usedNames: MutableSet<String>, quotedId: String?, quotedSnippet: String?,
    ): Record {
        val ref = ui.mediaRefJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        var location: JSONObject? = null
        var poll: JSONObject? = null
        var link: JSONObject? = null
        val atts = ArrayList<Attachment>()
        if (ui.isMedia) {
            val items = ui.albumItems
            when {
                items != null -> items.forEach { atts += attachment(zip, t, it, usedNames) }
                ui.mediaType == "location" || ref?.optBoolean("location") == true -> location = ref
                ui.mediaType == "poll" || ref?.optBoolean("poll") == true -> poll = ref?.let { pollOf(t.id, ui.id, it) }
                ui.mediaType == "link" || ref?.optBoolean("link") == true -> link = ref
                // A built-in sticker has no file: its emoji stands for it.
                ui.stickerBuiltin != null -> {}
                else -> atts += attachment(zip, t, ui, usedNames)
            }
        }
        val story = ui.replyTo
        return Record(
            id = ui.id,
            at = localZdt(ui.timestamp),
            from = senderName(t, ui, senderId),
            fromId = if (ui.isOutgoing) "self" else senderId,
            out = ui.isOutgoing,
            text = (if (ui.isMedia) ui.mediaCaption else ui.text).orEmpty(),
            forwarded = ui.forwardedFrom != null,
            edited = ui.isEdited,
            replyTo = quotedId,
            replySnippet = story?.let { "${it.senderName}: ${it.text}" } ?: quotedSnippet,
            attachments = atts,
            sticker = if (ui.isStickerLike) ui.stickerEmoji ?: "🙂" else null,
            location = location, poll = poll, link = link,
            reactions = reacts?.let { o -> o.keys().asSequence().map { it to o.optInt(it) }.toList() }.orEmpty(),
        )
    }

    private suspend fun senderName(t: Target, ui: MessageUi, senderId: String): String {
        // A channel post comes from the channel, not from the admin who wrote it.
        if (t.type == "channel") return t.title
        if (ui.isOutgoing) {
            return myName ?: (runCatching { repository.getMyProfile().displayName }.getOrNull()?.takeIf { it.isNotBlank() } ?: labels.you)
                .also { myName = it }
        }
        return senderNames.getOrPut(senderId) {
            runCatching { repository.getPeerProfile(senderId).displayName }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: (if (t.type == "personal") t.title else "peer…" + senderId.takeLast(6))
        }
    }

    private fun pollOf(chatId: String, pollId: String, ref: JSONObject): JSONObject {
        val res = runCatching { JSONObject(repository.pollResults(chatId, pollId)) }.getOrNull()
        val counts = res?.optJSONArray("counts")
        val opts = ref.optJSONArray("o") ?: JSONArray()
        val answers = JSONArray()
        for (i in 0 until opts.length()) answers.put(JSONObject().put("text", opts.optString(i)).put("voters", counts?.optInt(i) ?: 0))
        return JSONObject().put("question", ref.optString("q")).put("answers", answers)
            .put("total_voters", res?.optInt("voters") ?: 0).put("closed", res?.optBoolean("closed") ?: false)
    }

    // --- Attachments --------------------------------------------------------------

    private fun kindOf(ui: MessageUi): MediaKind = when {
        ui.isStickerLike -> MediaKind.STICKER
        ui.isGif -> MediaKind.GIF
        ui.isRoundVideo -> MediaKind.ROUND
        ui.isVoiceNote -> MediaKind.VOICE
        ui.mediaType == "image" -> MediaKind.PHOTO
        ui.mediaType == "video" -> MediaKind.VIDEO
        else -> MediaKind.FILE
    }

    private fun labelOf(kind: MediaKind, ui: MessageUi): String = when (kind) {
        MediaKind.PHOTO -> context.getString(R.string.preview_photo)
        MediaKind.VIDEO -> context.getString(R.string.media_kind_video)
        MediaKind.VOICE -> context.getString(R.string.preview_voice)
        MediaKind.ROUND -> context.getString(R.string.round_label)
        MediaKind.STICKER -> context.getString(R.string.sticker_label)
        MediaKind.GIF -> "GIF"
        MediaKind.FILE -> ui.mediaFilename ?: context.getString(R.string.preview_file)
    }

    private suspend fun attachment(zip: ZipOutputStream, t: Target, ui: MessageUi, used: MutableSet<String>): Attachment {
        val kind = kindOf(ui)
        val size = ui.mediaSizeBytes
        fun result(state: Attachment.State, path: String? = null) = Attachment(
            kind, labelOf(kind, ui), path, ui.mediaFilename, size, ui.mediaType == "audio", state,
            ui.mediaDurationMs, ui.mediaWidth, ui.mediaHeight,
        )
        if (kind !in options.media || (size ?: 0L) > maxBytes) return result(Attachment.State.SKIPPED)
        val (src, temporary) = source(ui) ?: run { missing++; return result(Attachment.State.MISSING) }
        try {
            if (MediaVault.length(src) > maxBytes) return result(Attachment.State.SKIPPED)
            val rel = kind.dir + "/" + uniqueName(used, kind.dir, fileName(ui, kind))
            return runCatching {
                copyFile(zip, src, t.dir + rel)
                files++
                result(Attachment.State.OK, rel)
            }.getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                missing++
                result(Attachment.State.MISSING)
            }
        } finally {
            if (temporary) src.delete()
        }
    }

    /** The attachment's bytes: our own original, the cache, or a fresh download kept only for the copy. */
    private suspend fun source(ui: MessageUi): Pair<File, Boolean>? {
        ui.localMediaPath?.let { File(it) }?.takeIf { it.isFile && it.length() > 0 }?.let { return it to false }
        val ref = ui.mediaRefJson?.takeIf { !ui.mediaPending } ?: return null
        repository.cachedMediaPathIfPresent(ref)?.let { return File(it) to false }
        val dl = File(tmp, "dl_" + System.nanoTime())
        runCatching { repository.downloadMedia(ref, dl.absolutePath) }
        return if (dl.isFile && dl.length() > 0) dl to true else { dl.delete(); null }
    }

    private fun fileName(ui: MessageUi, kind: MediaKind): String {
        val given = ui.mediaFilename?.let(::cleanName)?.takeIf { it.isNotBlank() }
        if (given != null) return given
        val ext = when (ui.mediaType) { "image" -> "jpg"; "video" -> "mp4"; "audio" -> "ogg"; else -> "bin" }
        return kind.dir.removeSuffix("s") + "_" + ui.id.take(8) + "." + ext
    }

    private fun uniqueName(used: MutableSet<String>, dir: String, name: String): String {
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        var candidate = name
        var n = 2
        while (!used.add("$dir/${candidate.lowercase()}")) {
            candidate = name.substring(0, dot) + " ($n)" + name.substring(dot)
            n++
        }
        return candidate
    }

    // --- Archive ------------------------------------------------------------------

    private fun entry(zip: ZipOutputStream, path: String, write: (OutputStream) -> Unit) {
        zip.putNextEntry(ZipEntry("$root/$path"))
        try { write(zip) } finally { zip.closeEntry() }
    }

    private fun copyFile(zip: ZipOutputStream, src: File, path: String) =
        entry(zip, path) { out -> MediaVault.open(src).use { it.copyTo(out, 256 * 1024) } }

    private fun putFile(zip: ZipOutputStream, src: File, path: String) =
        entry(zip, path) { out -> src.inputStream().use { it.copyTo(out, 64 * 1024) } }

    private fun putText(zip: ZipOutputStream, path: String, text: String) =
        entry(zip, path) { it.write(text.toByteArray(Charsets.UTF_8)) }

    // --- HTML -------------------------------------------------------------------------

    /** A chat's pages, [PAGE_SIZE] messages each, written to temporary files. */
    private inner class HtmlPages(private val t: Target, private val css: String) {
        private val pages = ArrayList<File>()
        private val pageOf = HashMap<String, Int>()
        private var w: BufferedWriter? = null
        private var onPage = 0
        private var lastDay: LocalDate? = null

        fun add(r: Record) {
            if (w == null || onPage >= PAGE_SIZE) open()
            val out = w!!
            val day = r.at?.toLocalDate()
            if (day != null && day != lastDay) {
                out.write("<div class=\"day\">" + esc(dayFormat.format(day)) + "</div>\n")
                lastDay = day
            }
            pageOf[r.id] = pages.size
            out.write(message(r))
            onPage++
        }

        private fun open() {
            w?.let { close(it, next = true) }
            val f = File(tmp, "page_" + System.nanoTime() + ".html")
            pages += f
            onPage = 0
            lastDay = null
            w = f.bufferedWriter().also {
                it.write(head(t.title, css))
                if (pages.size > 1) it.write("<a class=\"pager\" href=\"" + pageName(pages.size - 1) + "\">← " + esc(context.getString(R.string.export_html_earlier)) + "</a>\n")
            }
        }

        private fun close(out: BufferedWriter, next: Boolean) {
            if (next) out.write("<a class=\"pager\" href=\"" + pageName(pages.size + 1) + "\">" + esc(context.getString(R.string.export_html_later)) + " →</a>\n")
            out.write("</div></body></html>\n")
            out.close()
        }

        /** Puts the pages into the archive; the path of the first. */
        fun finish(zip: ZipOutputStream): String {
            if (w == null) {
                open()
                w!!.write("<div class=\"empty\">" + esc(context.getString(R.string.export_html_empty)) + "</div>\n")
            }
            close(w!!, next = false)
            w = null
            pages.forEachIndexed { i, f -> putFile(zip, f, t.dir + pageName(i + 1)) }
            return t.dir + pageName(1)
        }

        private fun message(r: Record): String = buildString {
            append("<div class=\"msg ").append(if (r.out) "out" else "in").append("\" id=\"m-").append(esc(r.id)).append("\">")
            append("<div class=\"from\">").append(esc(r.from)).append("</div>")
            if (r.forwarded) append("<div class=\"fwd\">").append(esc(context.getString(R.string.chat_forwarded))).append("</div>")
            val quote = r.replySnippet ?: if (r.replyTo != null) labels.inReplyTo else null
            if (quote != null) {
                val page = r.replyTo?.let { pageOf[it] }
                if (page != null) {
                    val here = page == pages.size
                    append("<a class=\"reply\" href=\"").append(if (here) "" else pageName(page)).append("#m-").append(esc(r.replyTo)).append("\">")
                        .append(esc(quote)).append("</a>")
                } else append("<div class=\"reply\">").append(esc(quote)).append("</div>")
            }
            r.attachments.forEach { append(attachmentHtml(it)) }
            if (r.sticker != null && r.attachments.none { it.state == Attachment.State.OK }) {
                append("<div class=\"sticker\">").append(esc(r.sticker)).append("</div>")
            }
            r.location?.let { l ->
                val lat = l.optDouble("lat", 0.0)
                val lng = l.optDouble("lng", 0.0)
                val name = listOf(l.optString("name"), l.optString("address")).filter { it.isNotBlank() }.joinToString(", ")
                    .ifBlank { "%.5f, %.5f".format(java.util.Locale.ROOT, lat, lng) }
                append("<a class=\"file\" href=\"https://www.openstreetmap.org/?mlat=$lat&amp;mlon=$lng#map=16/$lat/$lng\">📍 ")
                    .append(esc(name)).append("</a>")
            }
            r.poll?.let { p ->
                append("<div class=\"poll\"><b>📊 ").append(esc(p.optString("question"))).append("</b><ul>")
                val a = p.optJSONArray("answers") ?: JSONArray()
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    append("<li>").append(esc(o.optString("text"))).append(" — ").append(o.optInt("voters")).append("</li>")
                }
                append("</ul></div>")
            }
            r.link?.let { l ->
                val url = l.optString("url")
                append("<a class=\"file\" href=\"").append(esc(url)).append("\">🔗 ").append(esc(l.optString("title").ifBlank { url })).append("</a>")
                l.optString("description").takeIf { it.isNotBlank() }?.let { append("<div class=\"missing\">").append(esc(it)).append("</div>") }
            }
            if (r.text.isNotEmpty()) append("<div class=\"text\">").append(linkify(esc(r.text))).append("</div>")
            if (r.reactions.isNotEmpty()) append("<div class=\"reacts\">").append(r.reactions.joinToString("  ") { esc(it.first) + " " + it.second }).append("</div>")
            append("<div class=\"meta\">").append(r.at?.let { timeFormat.format(it) }.orEmpty())
            if (r.edited) append(" · ").append(esc(context.getString(R.string.export_html_edited)))
            append("</div></div>\n")
        }

        private fun attachmentHtml(a: Attachment): String {
            if (a.state != Attachment.State.OK) {
                val why = context.getString(if (a.state == Attachment.State.SKIPPED) R.string.export_html_not_included else R.string.export_html_unavailable)
                return "<div class=\"missing\">" + esc(a.label) + " · " + esc(why) + "</div>"
            }
            val src = href(a.path!!)
            return when (a.kind) {
                MediaKind.PHOTO, MediaKind.STICKER -> "<a href=\"$src\"><img class=\"${if (a.kind == MediaKind.STICKER) "stk" else "photo"}\" src=\"$src\" loading=\"lazy\" alt=\"\"></a>"
                MediaKind.GIF -> "<video class=\"photo\" src=\"$src\" autoplay loop muted playsinline></video>"
                MediaKind.ROUND -> "<video class=\"round\" src=\"$src\" controls preload=\"metadata\"></video>"
                MediaKind.VIDEO -> "<video class=\"photo\" src=\"$src\" controls preload=\"metadata\"></video>"
                MediaKind.VOICE -> "<audio src=\"$src\" controls preload=\"none\"></audio>"
                MediaKind.FILE -> {
                    val size = a.size?.let { " · " + com.alphamessenger.media.MediaSizeFormat.short(it) }.orEmpty()
                    "<a class=\"file\" href=\"$src\">" + (if (a.audio) "🎵 " else "📄 ") + esc(a.name ?: a.label) + size + "</a>" +
                        (if (a.audio) "<audio src=\"$src\" controls preload=\"none\"></audio>" else "")
                }
            }
        }
    }

    private fun pageName(n: Int) = if (n <= 1) "messages.html" else "messages$n.html"

    private fun head(title: String, css: String) =
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<title>" + esc(title) + "</title><link rel=\"stylesheet\" href=\"" + css + "\"></head><body><div class=\"page\">" +
            "<div class=\"header\">" + esc(title) + "</div>\n"

    private fun indexHtml(
        me: uniffi.alphamessenger.ProfileInfo?, avatar: String?, contacts: List<Pair<String, String>>,
        chats: List<Pair<Target, ChatResult>>,
    ): String = buildString {
        append(head(context.getString(R.string.export_title_profile), "style.css"))
        if (me != null) {
            append("<div class=\"card\">")
            avatar?.let { append("<img class=\"avatar\" src=\"").append(href(it)).append("\" alt=\"\">") }
            append("<b>").append(esc(me.displayName)).append("</b>")
            if (me.bio.isNotBlank()) append("<div class=\"text\">").append(esc(me.bio)).append("</div>")
            append("<div class=\"npub\">").append(esc(me.npub)).append("</div><div style=\"clear:both\"></div></div>")
        }
        if (contacts.isNotEmpty()) {
            append("<h2>").append(esc(context.getString(R.string.contacts_title))).append("</h2>")
            contacts.forEach { (name, npub) ->
                append("<div class=\"card\"><b>").append(esc(name)).append("</b><div class=\"npub\">").append(esc(npub)).append("</div></div>")
            }
        }
        val groups = listOf(
            "personal" to R.string.export_personal, "group" to R.string.export_groups,
            "channel" to R.string.export_channels, "saved" to R.string.menu_saved,
        )
        for ((type, label) in groups) {
            val here = chats.filter { it.first.type == type }
            if (here.isEmpty()) continue
            append("<h2>").append(esc(context.getString(label))).append("</h2>")
            here.forEach { (t, r) ->
                append("<div class=\"card\"><a href=\"").append(href(r.firstPage)).append("\">").append(esc(t.title)).append("</a>")
                    .append("<div class=\"missing\">").append(esc(context.getString(R.string.export_html_count, r.count))).append("</div></div>")
            }
        }
        append("</div></body></html>\n")
    }

    // --- JSON -------------------------------------------------------------------------

    private fun writeJson(j: JsonWriter, r: Record) {
        j.beginObject()
        j.name("id").value(r.id)
        r.at?.let { j.name("date").value(it.toOffsetDateTime().toString()).name("date_unixtime").value(it.toEpochSecond()) }
        j.name("from").value(r.from).name("from_id").value(r.fromId).name("out").value(r.out)
        if (r.edited) j.name("edited").value(true)
        if (r.forwarded) j.name("forwarded").value(true)
        r.replyTo?.let { j.name("reply_to_message_id").value(it) }
        j.name("text").value(r.text)
        r.sticker?.let { j.name("sticker_emoji").value(it) }
        if (r.attachments.isNotEmpty()) {
            j.name("attachments").beginArray()
            for (a in r.attachments) {
                j.beginObject().name("media_type").value(a.kind.dir)
                a.name?.let { j.name("file_name").value(it) }
                a.size?.let { j.name("file_size").value(it) }
                a.durationMs?.let { j.name("duration_seconds").value(it / 1000) }
                a.width?.let { j.name("width").value(it.toLong()) }
                a.height?.let { j.name("height").value(it.toLong()) }
                j.name("file").value(when (a.state) {
                    Attachment.State.OK -> a.path
                    Attachment.State.SKIPPED -> "(not included)"
                    Attachment.State.MISSING -> "(not available)"
                })
                j.endObject()
            }
            j.endArray()
        }
        r.location?.let { l ->
            j.name("location").beginObject()
                .name("latitude").value(l.optDouble("lat", 0.0)).name("longitude").value(l.optDouble("lng", 0.0))
                .name("name").value(l.optString("name")).name("address").value(l.optString("address"))
                .endObject()
        }
        r.poll?.let { p ->
            j.name("poll").beginObject().name("question").value(p.optString("question"))
                .name("closed").value(p.optBoolean("closed")).name("total_voters").value(p.optInt("total_voters").toLong())
                .name("answers").beginArray()
            val a = p.optJSONArray("answers") ?: JSONArray()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                j.beginObject().name("text").value(o.optString("text")).name("voters").value(o.optInt("voters").toLong()).endObject()
            }
            j.endArray().endObject()
        }
        r.link?.let { l ->
            j.name("link").beginObject().name("url").value(l.optString("url")).name("title").value(l.optString("title"))
                .name("description").value(l.optString("description")).endObject()
        }
        if (r.reactions.isNotEmpty()) {
            j.name("reactions").beginArray()
            r.reactions.forEach { (e, n) -> j.beginObject().name("emoji").value(e).name("count").value(n.toLong()).endObject() }
            j.endArray()
        }
        j.endObject()
    }

    private fun putResultJson(
        zip: ZipOutputStream, me: uniffi.alphamessenger.ProfileInfo?, avatar: String?,
        contacts: List<Pair<String, String>>, chats: List<File>,
    ) = entry(zip, "result.json") { out ->
        val head = JSONObject().put("about", context.getString(R.string.export_json_about, context.getString(R.string.app_name)))
            .put("exported_at", java.time.OffsetDateTime.now().toString())
        if (me != null) head.put("personal_information", JSONObject().put("name", me.displayName).put("bio", me.bio)
            .put("npub", me.npub).put("avatar", avatar ?: JSONObject.NULL))
        if (options.contacts) head.put("contacts", JSONArray().apply { contacts.forEach { (n, k) -> put(JSONObject().put("name", n).put("npub", k)) } })
        // The chats are copied as written, one after another: none is held in memory whole.
        val text = head.toString()
        out.write(text.substring(0, text.length - 1).toByteArray(Charsets.UTF_8))
        out.write(",\"chats\":[".toByteArray(Charsets.UTF_8))
        chats.forEachIndexed { i, f ->
            if (i > 0) out.write(','.code)
            f.inputStream().use { it.copyTo(out, 64 * 1024) }
        }
        out.write("]}".toByteArray(Charsets.UTF_8))
    }

    private companion object {
        const val PAGE_SIZE = 1000
        const val PAGE_FETCH = 200u

        fun cleanName(s: String): String =
            s.replace(Regex("[\\\\/:*?\"<>|#%\\u0000-\\u001f]"), "_").trim().trimStart('.').take(120)

        fun esc(s: String): String {
            val b = StringBuilder(s.length + 16)
            for (c in s) when (c) {
                '&' -> b.append("&amp;")
                '<' -> b.append("&lt;")
                '>' -> b.append("&gt;")
                '"' -> b.append("&quot;")
                '\'' -> b.append("&#39;")
                else -> b.append(c)
            }
            return b.toString()
        }

        /** Each path segment encoded: names with spaces or letters of any script still open. */
        fun href(path: String): String = path.split('/').joinToString("/") { android.net.Uri.encode(it) }

        private val URL = Regex("https?://[^\\s<]+")

        /** Web addresses in already escaped text become links. */
        fun linkify(escaped: String): String = URL.replace(escaped) { m ->
            val url = m.value.trimEnd('.', ',', ')', ';')
            val tail = m.value.substring(url.length)
            "<a href=\"$url\" rel=\"noreferrer\">$url</a>$tail"
        }
    }
}

private const val EXPORT_CSS = """
:root{--bg:#f4f5f7;--card:#fff;--out:#e6efff;--text:#15171b;--muted:#6b7280;--accent:#3d7bfd}
@media (prefers-color-scheme:dark){:root{--bg:#0f1115;--card:#1b1e24;--out:#1f2b42;--text:#e8eaed;--muted:#9aa0a6;--accent:#6ea1ff}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--text);font:15px/1.45 -apple-system,"Segoe UI",Roboto,sans-serif}
.page{max-width:760px;margin:0 auto;padding:16px}
.header{font-size:20px;font-weight:600;padding:8px 4px 16px}
.day{text-align:center;color:var(--muted);font-size:13px;margin:18px 0 10px}
.msg{background:var(--card);border-radius:16px;padding:8px 12px;margin:6px 0;max-width:85%;width:fit-content;overflow-wrap:anywhere}
.msg.out{background:var(--out);margin-left:auto}
.from{font-weight:600;font-size:13px;color:var(--accent);margin-bottom:2px}
.fwd{font-size:12px;color:var(--muted);font-style:italic}
.reply{display:block;border-left:3px solid var(--accent);padding:2px 8px;margin:4px 0;font-size:13px;color:var(--muted);text-decoration:none;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.text{white-space:pre-wrap}
.text a,.file,.card a{color:var(--accent)}
.photo{display:block;max-width:100%;max-height:420px;border-radius:12px;margin:4px 0}
.stk{display:block;width:160px;height:160px;object-fit:contain;margin:4px 0}
.round{display:block;width:220px;height:220px;border-radius:50%;object-fit:cover;margin:4px 0}
audio{display:block;margin:6px 0;max-width:100%}
.file{display:block;margin:4px 0;text-decoration:none}
.missing{color:var(--muted);font-size:13px;margin:4px 0}
.sticker{font-size:44px}
.poll ul{margin:4px 0;padding-left:18px}
.meta{text-align:right;color:var(--muted);font-size:11px;margin-top:2px}
.reacts{font-size:13px;margin-top:4px}
.pager{display:block;text-align:center;padding:12px;color:var(--accent);text-decoration:none}
.card{background:var(--card);border-radius:16px;padding:12px 16px;margin:8px 0;overflow-wrap:anywhere}
.card a{text-decoration:none;font-weight:600}
.avatar{width:72px;height:72px;border-radius:50%;object-fit:cover;float:left;margin-right:14px}
.npub{font-family:monospace;font-size:12px;color:var(--muted);word-break:break-all}
h2{font-size:13px;color:var(--muted);text-transform:uppercase;letter-spacing:.3px;margin:22px 4px 6px}
.empty{color:var(--muted);text-align:center;padding:24px}
"""
