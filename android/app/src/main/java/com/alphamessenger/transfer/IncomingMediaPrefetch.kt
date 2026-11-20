package com.alphamessenger.transfer

import com.alphamessenger.data.AlphaCoreRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Media starts downloading when its message arrives, not when its bubble is first
 * drawn, so the file is there by the time the chat is opened. Whether it downloads
 * is still the auto-download policy's call, made in [TransferEngine.consider].
 */
@Singleton
class IncomingMediaPrefetch @Inject constructor(
    private val repository: AlphaCoreRepository,
    private val transfers: TransferEngine,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            repository.incomingMedia.collect { (chatId, json) ->
                runCatching { offer(chatId, JSONObject(json)) }
            }
        }
    }

    private suspend fun offer(chatId: String, message: JSONObject) {
        // Seen once, under the reader's finger: never fetched ahead.
        if (message.optJSONObject("expiry")?.optString("mode") in ONE_TIME) return
        // A stranger's media waits for a tap.
        if (repository.isChatRequest(chatId)) return
        val id = message.optString("id")
        val ref = message.optString("media_ref").takeIf { it.isNotBlank() } ?: return
        val items = JSONObject(ref).optJSONArray("items")
        if (items == null) {
            consider(ref, message.optString("media_type"), message.optString("filename"), message.optLong("size_bytes"), id)
            return
        }
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            consider(item.toString(), item.optString("media_type"), item.optString("filename"), item.optLong("original_size"), "$id#$i")
        }
    }

    /** On the main thread, as a bubble calls it: two calls for one file never both start it. */
    private suspend fun consider(ref: String, mediaType: String, filename: String, size: Long, opId: String) = withContext(Dispatchers.Main) {
        transfers.consider(
            TransferKey.download(ref), mediaType, filename, size,
            isOutgoing = false, senderLocalPath = null, opId = opId,
        )
    }

    private companion object {
        val ONE_TIME = setOf("ViewOnce", "view_once", "whisper")
    }
}
