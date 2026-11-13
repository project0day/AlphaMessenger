package com.alphamessenger.calls

import com.alphamessenger.R
import com.alphamessenger.data.AlphaCoreRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

/** Peer identity for the call UI (name + avatar of the direct-chat peer). */
data class CallPeer(val npub: String, val displayName: String, val avatarPath: String)

/**
 * The calls module's ONLY window into the messenger core. Keeps the module
 * fully decoupled from chat internals: signaling send + peer resolution.
 */
@Singleton
class CallRepository @Inject constructor(
    private val core: AlphaCoreRepository,
    private val strings: com.alphamessenger.util.Strings,
) {

    /** Send an encoded call signal through the MLS-encrypted transient
     *  ChatState channel (GiftWrap underneath). Fire-and-forget. */
    fun sendSignal(chatId: String, encoded: String) {
        core.sendChatStateDetached(chatId, encoded)
    }

    /** Resolve the direct-chat peer (the member that isn't us) for the call
     *  UI. Falls back to a generic label when the lookup fails. */
    /** A text into the chat (a quick reply instead of answering the call). */
    suspend fun sendText(chatId: String, text: String) {
        core.sendText(chatId, text)
    }

    /** Our privacy rules (decided by the core on this phone): may this member ring us? */
    suspend fun callAllowed(chatId: String, senderId: String, video: Boolean): Boolean =
        withContext(Dispatchers.IO) { core.callAllowed(chatId, senderId, video) }

    suspend fun resolvePeer(chatId: String): CallPeer = withContext(Dispatchers.IO) {
        runCatching {
            val arr = JSONArray(core.listGroupMembers(chatId))
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                if (!m.optBoolean("is_self", false)) {
                    val npub = m.optString("npub")
                    val name = m.optString("display_name").ifBlank {
                        npub.take(12) + "…"
                    }
                    return@runCatching CallPeer(npub, name, m.optString("avatar_path"))
                }
            }
            CallPeer("", strings.get(R.string.peer_fallback), "")
        }.getOrDefault(CallPeer("", strings.get(R.string.peer_fallback), ""))
    }
}
