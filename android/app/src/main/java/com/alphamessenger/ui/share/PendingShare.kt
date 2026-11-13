package com.alphamessenger.ui.share

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * A single accepted Share awaiting recipients. Process state, not navigation
 * state — modeled after [com.alphamessenger.ui.chat.ChatOpenHandoff].
 *
 * There may be several recipients. The first is the set's "owner": it opens,
 * takes the attachments into the shared set and shows the preview. The rest
 * receive the SAME uploaded refs after sending, with no second upload. So
 * [takeFor] hands the content to exactly one chat exactly once — otherwise
 * whichever chat screen is alive first would grab the set.
 *
 * Surviving process death is not required: the user is still choosing
 * recipients here, and there is nothing worth persisting to disk for it.
 */
object PendingShare {

    /** Share content; null — nothing came in, or it was already taken. */
    var payload by mutableStateOf<SharePayload?>(null)
        private set

    /** Recipients in selection order. Empty — the user hasn't confirmed yet. */
    var targets by mutableStateOf<List<String>>(emptyList())
        private set

    /** The taken set: "back" from the preview returns it to recipient selection. */
    private var spent: SharePayload? = null

    val active: Boolean get() = payload != null

    /** The chat that takes the set and shows the preview. */
    val host: String? get() = targets.firstOrNull()

    fun offer(p: SharePayload?) {
        payload = p
        targets = emptyList()
        spent = null
    }

    /** The user confirmed the picker selection. */
    fun aim(ids: List<String>) {
        if (payload != null && ids.isNotEmpty()) targets = ids
    }

    /** Hand the set to the OWNER chat only, along with the other recipients. */
    fun takeFor(chatId: String): ShareDelivery? {
        if (host != chatId) return null
        val p = payload ?: return null
        val extras = targets.drop(1)
        payload = null
        targets = emptyList()
        spent = p
        return ShareDelivery(p, extras)
    }

    /**
     * Return the same Share to recipient selection: the user pressed "back"
     * in the preview without sending. No need to re-parse the intent — the URIs
     * stay valid for as long as the task lives.
     */
    fun rearm(): Boolean {
        val p = spent ?: return false
        spent = null
        payload = p
        targets = emptyList()
        return true
    }

    fun clear() {
        payload = null
        targets = emptyList()
        spent = null
    }
}

/** What the owner chat gets: the set itself plus the remaining recipients. */
data class ShareDelivery(
    val payload: SharePayload,
    val extraTargets: List<String>,
)
