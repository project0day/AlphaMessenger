package com.alphamessenger.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.IconCompat
import com.alphamessenger.ui.MainActivity
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import com.alphamessenger.R

/**
 * classic message notifications.
 *
 * One notification per chat (stable id = chat hash), built with
 * [NotificationCompat.MessagingStyle] so multiple messages from the same
 * conversation collapse into a single threaded card instead of stacking. All
 * chats share a group key so the system bundles them under one summary with a
 * badge count. Each card carries inline **Reply** (RemoteInput) and **Mark read**
 * actions, handled by [NotificationActionReceiver].
 *
 * Privacy: the preview level is caller-controlled ([Preview]) — full text, name
 * only, or nothing on screen — mirroring notification privacy setting.
 */
class MessageNotifier(private val context: Context) {

    enum class Preview { FULL, NAME_ONLY, NONE;
        companion object {
            fun from(s: String?): Preview = when (s) {
                "name" -> NAME_ONLY
                "none" -> NONE
                else -> FULL
            }
        }
    }

    /** One line in a chat's notification thread. */
    data class Line(
        val senderName: String,
        val avatarPath: String,
        val text: String,
        val timeMs: Long,
        val isSelf: Boolean,
        /** Optional FileProvider content URI of an image/video thumbnail to show
 * inline. Only used in FULL preview. */
        val imageUri: String? = null,
        /** Source message id — lets a later edit/delete update THIS exact line in
         *  the already-posted card (so the peer's notification reflects the edit,
         *  not the stale original text). */
        val msgId: String = "",
    )

    companion object {
        const val CHANNEL_ID = "alpha_messages_channel"
        /** Brand violet for the small icon and the card accent. */
        const val ACCENT = 0xFF8B5CF6.toInt()
        const val GROUP_KEY = "com.alphamessenger.MESSAGES"
        const val SUMMARY_ID = 1999

        const val ACTION_REPLY = "com.alphamessenger.action.NOTIF_REPLY"
        const val ACTION_MARK_READ = "com.alphamessenger.action.NOTIF_MARK_READ"
        private const val REACT_REQUEST_BASE = 0x4000000
        const val EXTRA_MSG_ID = "msg_id"
        const val EXTRA_CHAT_ID = "chatId"
        const val EXTRA_CHAT_TITLE = "chatTitle"
        const val EXTRA_IS_GROUP = "isGroup"
        const val KEY_REPLY_TEXT = "key_reply_text"

        /** chatId currently on screen — incoming messages for it are not
         *  notified (the user is already looking at the conversation). Set by
         *  the chat screen's lifecycle. */
        @Volatile
        var activeChatId: String? = null

        /** Process-wide thread store so the action receiver can append the user's
         *  own sent reply into the same conversation card the notifier built. */
        private val threads = ConcurrentHashMap<String, MutableList<Line>>()
        private const val MAX_LINES = 12

        /** Bounded LRU of message ids we've already shown a notification for —
         *  survives card dismissal so the same message re-delivered later (catch-up
         *  reprocessing) NEVER pops a second notification. */
        private val notified = object : LinkedHashMap<String, Unit>(512, 0.75f, false) {
            override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Unit>) = size > 400
        }

        /** Record [msgId] as notified. Returns false if it was already seen — the
         *  caller must then skip notifying (it's a duplicate delivery). */
        fun markNotified(msgId: String): Boolean = synchronized(notified) {
            if (msgId.isBlank()) return true
            if (notified.containsKey(msgId)) false else { notified[msgId] = Unit; true }
        }

        /** Append a message line to the chat's thread. Dedup is handled upstream
         *  by [markNotified]; this always appends. */
        fun appendLine(chatId: String, line: Line) {
            val list = threads.getOrPut(chatId) { mutableListOf() }
            synchronized(list) {
                list.add(line)
                while (list.size > MAX_LINES) list.removeAt(0)
            }
        }

        /** Append the user's own reply (always shown). */
        fun appendSelfLine(chatId: String, line: Line) = appendLine(chatId, line)

        /** Our avatar from the last post that knew it: a reply re-post must not wait on the core for it. */
        @Volatile private var lastSelfAvatar: String = ""

        /** The user's preview setting, kept current by the sync service: every
         *  re-post (after a reply too) honours it. */
        @Volatile var currentPreview: Preview = Preview.FULL

        fun linesFor(chatId: String): List<Line> =
            threads[chatId]?.let { synchronized(it) { it.toList() } } ?: emptyList()

        fun clearThread(chatId: String) { threads.remove(chatId) }

        fun stableId(chatId: String): Int =
            2000 + (chatId.hashCode() and 0x7FFFFF)

        /** Update the text of an already-shown line after its message was edited.
         *  true if a line with [msgId] is currently in the card (so the
         *  caller re-posts silently). Keeps the original time/sender; an edited
         *  text message replaces any inline thumbnail. */
        fun editLine(chatId: String, msgId: String, newText: String): Boolean {
            if (msgId.isBlank()) return false
            val list = threads[chatId] ?: return false
            synchronized(list) {
                val idx = list.indexOfLast { it.msgId == msgId }
                if (idx < 0) return false
                list[idx] = list[idx].copy(text = newText, imageUri = null)
                return true
            }
        }

        /** A deleted message leaves no trace in the card: its lines go. Returns how
         *  many of the peer's lines are left, or null when none of [msgIds] was shown. */
        fun removeLines(chatId: String, msgIds: Set<String>): Int? {
            val list = threads[chatId] ?: return null
            synchronized(list) {
                if (!list.removeAll { it.msgId.isNotBlank() && it.msgId in msgIds }) return null
                return list.count { !it.isSelf }
            }
        }

        /** True if a notification card for [chatId] is currently on screen — so an
         *  edit/delete update does NOT create a fresh card for a chat the user
         *  already cleared. */
        fun hasActiveCard(context: Context, chatId: String): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
            val nm = context.getSystemService(NotificationManager::class.java) ?: return false
            val id = stableId(chatId)
            return runCatching { nm.activeNotifications.any { it.id == id } }.getOrDefault(false)
        }
    }

    private val nm get() = NotificationManagerCompat.from(context)

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_messages),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notif_channel_messages_desc)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                enableVibration(true)
                setShowBadge(true)
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    /**
     * Post (or update) the notification card for [chatId]. The newest message
     * has already been appended to the thread store by the caller. [unreadCount]
     *  drives the badge number; [muted] suppresses sound/vibration.
     */
    fun post(
        chatId: String,
        chatTitle: String,
        isGroup: Boolean,
        unreadCount: Int,
        muted: Boolean,
        preview: Preview,
        selfAvatarPath: String? = null,
        /** Silent in-place refresh (edit/delete reflected in an existing card):
         *  no sound/vibration/heads-up re-alert — just updates the text. */
        silent: Boolean = false,
        /** Group or channel: its avatar with a small badge saying which. Null =
         *  keep what this chat's card had (re-posts from replies, edits). */
        badge: ChatBadge? = null,
    ) {
        ensureChannel()

        if (!selfAvatarPath.isNullOrBlank()) lastSelfAvatar = selfAvatarPath
        val selfPerson = Person.Builder().setName(context.getString(R.string.notif_you)).setKey("self").apply {
            loadAvatar(selfAvatarPath?.takeIf { it.isNotBlank() } ?: lastSelfAvatar)?.let { setIcon(it) }
        }.build()
        val style = NotificationCompat.MessagingStyle(selfPerson)
        if (isGroup) {
            style.conversationTitle = chatTitle
            style.isGroupConversation = true
        } else {
            style.isGroupConversation = false
        }

        val chatBadge = badge?.also { badges[chatId] = it } ?: badges[chatId]
        // A channel card reads as one sender — the channel — and Android draws
        // that sender's icon, not the large icon: the badge goes on it.
        val channelIcon = chatBadge?.takeIf { it.channel }
            ?.let { b -> runCatching { IconCompat.createWithBitmap(badgedChatIcon(b, chatTitle)) }.getOrNull() }

        val lines = linesFor(chatId)
        when (preview) {
            Preview.NONE -> {
                // No content/sender on screen — a single neutral line. Never a null
                // sender: Android would draw it as ours ("You" and our avatar).
                style.addMessage(
                    context.getString(R.string.notif_new_message),
                    System.currentTimeMillis(),
                    channelIcon?.let { Person.Builder().setName(chatTitle).setKey(chatId).setIcon(it).build() }
                        ?: Person.Builder().setName(context.getString(R.string.app_name)).setKey("hidden").build(),
                )
            }
            else -> {
                for (l in lines) {
                    val person = if (l.isSelf) selfPerson else {
                        val b = Person.Builder().setName(
                            l.senderName.ifBlank { if (isGroup) context.getString(R.string.participant_fallback) else chatTitle }
                        ).setKey(l.senderName)
                        // Channel: the channel itself. Group: the member, badged "group".
                        val icon = channelIcon
                            ?: chatBadge?.takeIf { isGroup }?.let { runCatching {
                                IconCompat.createWithBitmap(badgedChatIcon(ChatBadge(false, l.avatarPath), l.senderName, member = true))
                            }.getOrNull() }
                            ?: loadAvatar(l.avatarPath)
                        icon?.let { b.setIcon(it) }
                        b.build()
                    }
                    if (preview == Preview.NAME_ONLY) {
                        style.addMessage(context.getString(R.string.notif_new_message), l.timeMs, person)
                        continue
                    }
                    // FULL preview. For media with a thumbnail we add TWO lines:
                    //  1) the icon + caption/label as TEXT (Android turns a message
                    //     that carries image data into image-only — the text becomes
                    //     alt-text and is hidden — so the caption needs its own line);
 // 2) the thumbnail itself (tap to expand, classic).
                    if (l.imageUri != null) {
                        if (l.text.isNotBlank()) {
                            style.addMessage(
                                NotificationCompat.MessagingStyle.Message(l.text, l.timeMs, person)
                            )
                        }
                        val img = NotificationCompat.MessagingStyle.Message(" ", l.timeMs, person)
                        runCatching { img.setData("image/jpeg", android.net.Uri.parse(l.imageUri)) }
                        style.addMessage(img)
                    } else {
                        style.addMessage(
                            NotificationCompat.MessagingStyle.Message(l.text, l.timeMs, person)
                        )
                    }
                }
            }
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            stableId(chatId),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("chatId", chatId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alpha)
            .setColor(ACCENT)
            .setStyle(style)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(GROUP_KEY)
            .setOnlyAlertOnce(silent)
            .setPriority(if (muted || silent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            // No buttons the system guesses from the text: a file name like
            // "app.ver.apk" read as a web address got an "Open link" button.
            .setAllowSystemGeneratedContextualActions(false)
        if (unreadCount > 1) builder.setNumber(unreadCount)
        chatBadge?.let { b -> runCatching { badgedChatIcon(b, chatTitle) }.getOrNull()?.let(builder::setLargeIcon) }
        if (muted || silent) builder.setSilent(true)

        // Inline actions are pointless when the screen must show nothing.
        if (preview != Preview.NONE) {
            // A channel is read, not answered: a reply would land in the channel itself.
            // With an app lock nothing is sent from the shade, unless the user allowed it.
            val replyOk = com.alphamessenger.data.AppLockManager(context).notificationReplyAllowed
            if (chatBadge?.channel != true && replyOk) builder.addAction(buildReplyAction(chatId, chatTitle, isGroup))
            // A reaction to the newest message, picked from a row right in the shade.
            val target = linesFor(chatId).lastOrNull { !it.isSelf && !it.msgId.isNullOrBlank() }?.msgId
            if (target != null && replyOk) builder.addAction(buildReactAction(chatId, chatTitle, isGroup, target))
            builder.addAction(buildMarkReadAction(chatId))
        }

        safeNotify(stableId(chatId), builder.build())
        refreshSummary()
    }

    private fun buildReplyAction(
        chatId: String,
        chatTitle: String,
        isGroup: Boolean,
    ): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(KEY_REPLY_TEXT)
            .setLabel(context.getString(R.string.notif_reply_label))
            .build()
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = ACTION_REPLY
            putExtra(EXTRA_CHAT_ID, chatId)
            putExtra(EXTRA_CHAT_TITLE, chatTitle)
            putExtra(EXTRA_IS_GROUP, isGroup)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            stableId(chatId) * 2,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send, context.getString(R.string.notif_reply), pi,
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .build()
    }

    // Our own small window with the emoji: inline reply choices are not shown by
    // many system skins, and the button then did nothing.
    private fun buildReactAction(chatId: String, chatTitle: String, isGroup: Boolean, messageId: String): NotificationCompat.Action {
        val intent = Intent(context, ReactActivity::class.java).apply {
            putExtra(EXTRA_CHAT_ID, chatId)
            putExtra(EXTRA_CHAT_TITLE, chatTitle)
            putExtra(EXTRA_IS_GROUP, isGroup)
            putExtra(EXTRA_MSG_ID, messageId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }
        val pi = PendingIntent.getActivity(
            context,
            REACT_REQUEST_BASE + stableId(chatId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(android.R.drawable.btn_star, context.getString(R.string.notif_react), pi)
            .setShowsUserInterface(true)
            .build()
    }

    private fun buildMarkReadAction(chatId: String): NotificationCompat.Action {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = ACTION_MARK_READ
            putExtra(EXTRA_CHAT_ID, chatId)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            stableId(chatId) * 2 + 1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_view, context.getString(R.string.notif_action_read), pi,
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
    }

    /** Maintain the group-summary card. Android only needs (and should only show)
     *  a summary when there are 2+ bundled chat cards — posting one for a single
     *  chat makes a stray "new messages" card flash when that chat is cleared.
     *  So: post the summary only with ≥2 active children, otherwise cancel it. */
    private fun refreshSummary() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val children = (context.getSystemService(NotificationManager::class.java))
            .activeNotifications.count {
                it.id != SUMMARY_ID && it.notification.group == GROUP_KEY
            }
        if (children >= 2) {
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_alpha)
                .setColor(ACCENT)
                .setContentTitle("AlphaMessenger")
                .setContentText(context.getString(R.string.notif_new_messages))
                .setGroup(GROUP_KEY)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
            safeNotify(SUMMARY_ID, builder.build())
        } else {
            nm.cancel(SUMMARY_ID)
        }
    }

    /** Cancel a chat's card and drop its thread (chat opened / marked read). */
    fun cancelChat(chatId: String) {
        clearThread(chatId)
        nm.cancel(stableId(chatId))
        refreshSummary()
    }

    private fun safeNotify(id: Int, notification: android.app.Notification) {
        try {
            nm.notify(id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted (API 33+) — skip silently.
        }
    }

    /** Load a square avatar file into a circular [IconCompat], best-effort. */
    /** What a chat card shows as its picture beyond the sender's face. */
    data class ChatBadge(val channel: Boolean, val avatarPath: String)

    private val badges = ConcurrentHashMap<String, ChatBadge>()

    /** The chat's avatar (or its initial on a tinted circle) with a small
     *  "people" / "megaphone" badge in the bottom-right corner. */
    private fun badgedChatIcon(b: ChatBadge, title: String, member: Boolean = false): Bitmap {
        val size = 192
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint().apply { isAntiAlias = true }
        val photo = b.avatarPath.takeIf { it.isNotBlank() && File(it).exists() }
            ?.let { runCatching { com.alphamessenger.data.MediaVault.decodeFile(it, BitmapFactory.Options().apply { inSampleSize = 2 }) }.getOrNull() }
        if (photo != null) {
            canvas.drawBitmap(circleCrop(photo), null, RectF(0f, 0f, size.toFloat(), size.toFloat()), paint)
        } else if (member) {
            // A member without a picture: their initial (a whole emoji, not half of one).
            paint.color = Color.parseColor("#8B5CF6")
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
            paint.color = Color.WHITE
            paint.textSize = size * 0.42f
            paint.textAlign = Paint.Align.CENTER
            paint.isFakeBoldText = true
            val letter = com.alphamessenger.ui.components.avatarInitial(title)
            canvas.drawText(letter, size / 2f, size / 2f - (paint.descent() + paint.ascent()) / 2f, paint)
        } else {
            // A group or channel without a picture: the ghost on the neutral disc, as in the app.
            paint.color = 0xFF5B6474.toInt()
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
            androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_stat_alpha)?.mutate()?.let { g ->
                g.setTint(0xFFE9EDF3.toInt())
                val pad = (size * 0.21f).toInt()
                g.setBounds(pad, pad, size - pad, size - pad)
                g.draw(canvas)
            }
        }
        // Badge: a ring in the background colour cut out of the avatar, then the glyph disc.
        val r = size * 0.21f
        val cx = size - r
        val cy = size - r
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, r + size * 0.03f, paint)
        paint.color = Color.parseColor(if (b.channel) "#C026D3" else "#8B5CF6")
        canvas.drawCircle(cx, cy, r, paint)
        val glyph = androidx.core.content.ContextCompat.getDrawable(
            context, if (b.channel) R.drawable.ic_notif_badge_channel else R.drawable.ic_notif_badge_group,
        )
        glyph?.let {
            val g = (r * 1.25f).toInt()
            it.setBounds((cx - g / 2).toInt(), (cy - g / 2).toInt(), (cx + g / 2).toInt(), (cy + g / 2).toInt())
            it.draw(canvas)
        }
        return out
    }

    private fun loadAvatar(path: String): IconCompat? {
        if (path.isBlank()) return null
        val f = File(path)
        if (!f.exists()) return null
        return runCatching {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            val bmp = com.alphamessenger.data.MediaVault.decodeFile(path, opts) ?: return null
            IconCompat.createWithBitmap(circleCrop(bmp))
        }.getOrNull()
    }

    private fun circleCrop(src: Bitmap): Bitmap {
        val size = minOf(src.width, src.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint().apply { isAntiAlias = true }
        val path = Path().apply {
            addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CCW)
        }
        canvas.clipPath(path)
        val srcRect = Rect(0, 0, size, size)
        canvas.drawBitmap(src, srcRect, RectF(0f, 0f, size.toFloat(), size.toFloat()), paint)
        return output
    }
}
