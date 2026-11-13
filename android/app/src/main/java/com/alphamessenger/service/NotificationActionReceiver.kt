package com.alphamessenger.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import com.alphamessenger.R
import com.alphamessenger.data.AlphaCoreRepository
import com.alphamessenger.data.CoreHolder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles the inline notification actions posted by [MessageNotifier]:
 *
 * - **Reply** — reads the [RemoteInput] text, sends it through the core, echoes
 * the sent line back into the conversation card and marks the
 *   chat read.
 * - **Mark read** — marks the chat read and dismisses its card.
 *
 * Both run off the main thread via [BroadcastReceiver.goAsync] so the work
 * survives the brief receiver lifetime.
 */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: AlphaCoreRepository
    @Inject lateinit var coreHolder: CoreHolder

    override fun onReceive(context: Context, intent: Intent) {
        val chatId = intent.getStringExtra(MessageNotifier.EXTRA_CHAT_ID) ?: return
        val notifier = MessageNotifier(context.applicationContext)
        val unlocked = coreHolder.peek() != null

        when (intent.action) {
            MessageNotifier.ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(MessageNotifier.KEY_REPLY_TEXT)
                    ?.toString()
                    ?.trim()
                    .orEmpty()
                val chatTitle = intent.getStringExtra(MessageNotifier.EXTRA_CHAT_TITLE).orEmpty()
                val isGroup = intent.getBooleanExtra(MessageNotifier.EXTRA_IS_GROUP, false)
                // The reply button spins until the card is posted again — so it is
                // posted on every outcome, right after the send, before anything else.
                val repost = { sent: Boolean ->
                    MessageNotifier.appendSelfLine(
                        chatId,
                        MessageNotifier.Line(
                            senderName = context.getString(R.string.notif_you),
                            avatarPath = "",
                            text = if (sent) text else context.getString(R.string.notif_reply_failed, text),
                            timeMs = System.currentTimeMillis(),
                            isSelf = true,
                        ),
                    )
                    notifier.post(
                        chatId = chatId,
                        chatTitle = chatTitle,
                        isGroup = isGroup,
                        unreadCount = 0,
                        muted = true, // don't re-alert for our own reply
                        preview = MessageNotifier.currentPreview,
                    )
                }
                if (text.isBlank()) return
                // Never touch the DB/core before unlock, nor send past an app lock
                // (a card posted before the lock was set may still carry Reply).
                if (!unlocked || !com.alphamessenger.data.AppLockManager(context).notificationReplyAllowed) { repost(false); return }

                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        // In a group a reply answers the message it came from; in a 1:1 it needs no quote.
                        val target = if (isGroup) MessageNotifier.linesFor(chatId).lastOrNull { !it.isSelf && it.msgId.isNotBlank() } else null
                        val sent = runCatching {
                            if (target != null) repository.sendReply(chatId, target.msgId, text, target.text.take(120))
                            else repository.sendText(chatId, text)
                        }
                            .onFailure { Log.w("NotifAction", "reply send failed: ${it.message}") }
                            .isSuccess
                        repost(sent)
                        if (sent) runCatching { repository.markChatRead(chatId) }
                    } finally {
                        pending.finish()
                    }
                }
            }

            MessageNotifier.ACTION_MARK_READ -> {
                if (!unlocked) return
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        runCatching { repository.markChatRead(chatId) }
                        notifier.cancelChat(chatId)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
