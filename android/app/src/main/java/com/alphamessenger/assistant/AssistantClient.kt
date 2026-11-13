package com.alphamessenger.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.TimeUnit

/** A request failed in a way the chat shows as [error]. */
class AssistantFailure(val error: AssistantError) : Exception(error.kind.name)

/** One turn sent to the model: text, images as data URLs, and the tool exchange. */
data class ChatTurn(
    val role: String,
    val text: String,
    val images: List<String> = emptyList(),
    /** An assistant turn that asked for tools. */
    val toolCalls: List<ToolCall> = emptyList(),
    /** A tool's answer: the call it answers. */
    val toolCallId: String? = null,
)

/**
 * The OpenAI-compatible protocol: /models, /chat/completions (streamed),
 * /audio/transcriptions. Talks only to the address the user configured.
 */
object AssistantClient {
    private val JSON = "application/json".toMediaType()

    fun baseUrl(endpoint: String): String = endpoint.trim().trimEnd('/')

    /** https everywhere; plain http only to this phone or the local network (a model at home). */
    fun checkAddress(endpoint: String) {
        val u = runCatching { URL(baseUrl(endpoint)) }.getOrNull()
            ?: throw AssistantFailure(AssistantError(AssistantError.Kind.BAD_ADDRESS, detail = endpoint.take(80)))
        if (!u.userInfo.isNullOrEmpty()) throw AssistantFailure(AssistantError(AssistantError.Kind.BAD_ADDRESS))
        when (u.protocol) {
            "https" -> Unit
            "http" -> {
                val local = runCatching { InetAddress.getByName(u.host) }.getOrNull()
                    ?.let { it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress } == true
                if (!local) throw AssistantFailure(AssistantError(AssistantError.Kind.BAD_ADDRESS, detail = "http"))
            }
            else -> throw AssistantFailure(AssistantError(AssistantError.Kind.BAD_ADDRESS))
        }
    }

    private fun client(p: AssistantProfile, readSeconds: Long): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            // Between two pieces of a streamed answer, not for the whole answer.
            .readTimeout(readSeconds, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
        return b.build()
    }

    private fun request(p: AssistantProfile, key: String, path: String): Request.Builder =
        Request.Builder().url(baseUrl(p.endpoint) + path).apply {
            if (key.isNotBlank()) header("Authorization", "Bearer $key")
            header("User-Agent", "Alpha")
        }

    /** The service's models, for "Check connection". */
    suspend fun models(p: AssistantProfile, key: String): List<String> = withContext(Dispatchers.IO) {
        checkAddress(p.endpoint)
        val body = exec(client(p, 30).newCall(request(p, key, "/models").get().build()))
        runCatching {
            val a = JSONObject(body).optJSONArray("data") ?: JSONArray()
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("id")?.takeIf { s -> s.isNotBlank() } }.sorted()
        }.getOrDefault(emptyList())
    }

    /** Speech → text through the service's transcription model. */
    suspend fun transcribe(p: AssistantProfile, key: String, audio: java.io.File, mime: String): String = withContext(Dispatchers.IO) {
        checkAddress(p.endpoint)
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", p.sttModel)
            .addFormDataPart("file", audio.name, audio.asRequestBody(mime.toMediaType()))
            .build()
        val body = exec(client(p, 120).newCall(request(p, key, "/audio/transcriptions").post(form).build()))
        runCatching { JSONObject(body).optString("text") }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: body.trim().takeIf { it.isNotEmpty() && !it.startsWith("{") }
            ?: throw AssistantFailure(AssistantError(AssistantError.Kind.EMPTY))
    }

    /**
     * Ask the model, streaming. [onDelta] gets reasoning and answer pieces as
     * they come. Returns normally when the answer is complete; throws
     * [AssistantFailure] otherwise (a cancelled coroutine cancels the call).
     */
    suspend fun chat(
        p: AssistantProfile,
        key: String,
        turns: List<ChatTurn>,
        tools: JSONArray? = null,
        onDelta: (thinking: String, answer: String) -> Unit,
    ): List<ToolCall> = withContext(Dispatchers.IO) {
        checkAddress(p.endpoint)
        val messages = JSONArray()
        for (t in turns) {
            val o = JSONObject().put("role", t.role)
            when {
                t.toolCallId != null -> o.put("tool_call_id", t.toolCallId).put("content", t.text)
                t.toolCalls.isNotEmpty() -> o.put("content", t.text.ifBlank { JSONObject.NULL })
                    .put("tool_calls", JSONArray(t.toolCalls.map { c ->
                        JSONObject().put("id", c.id).put("type", "function")
                            .put("function", JSONObject().put("name", c.name).put("arguments", c.arguments))
                            .apply { c.extra?.let { x -> runCatching { put("extra_content", JSONObject(x)) } } }
                    }))
                t.images.isEmpty() -> o.put("content", t.text)
                else -> o.put("content", JSONArray().apply {
                    if (t.text.isNotBlank()) put(JSONObject().put("type", "text").put("text", t.text))
                    t.images.forEach { put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", it))) }
                })
            }
            messages.put(o)
        }
        val payload = JSONObject().put("model", p.model).put("messages", messages).put("stream", true)
        if (tools != null && tools.length() > 0) payload.put("tools", tools)
        val calls = java.util.TreeMap<Int, Array<String>>() // index → id, name, arguments, extra_content
        val call = client(p, 120).newCall(
            request(p, key, "/chat/completions").header("Accept", "text/event-stream")
                .post(payload.toString().toRequestBody(JSON)).build(),
        )
        val cancel = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.invokeOnCompletion { call.cancel() }
        try {
            val response = try { call.execute() } catch (e: IOException) { throw networkFailure(p, e) }
            response.use { r ->
                if (!r.isSuccessful) {
                    val retry = r.header("Retry-After")?.toIntOrNull() ?: 0
                    throw AssistantFailure(AssistantError.ofStatus(r.code, r.body?.string().orEmpty(), retry))
                }
                val type = r.header("Content-Type").orEmpty()
                val source = r.body?.source() ?: throw AssistantFailure(AssistantError(AssistantError.Kind.EMPTY))
                val split = ThinkSplitter(onDelta)
                if (!type.contains("event-stream")) {
                    // The service ignored "stream": one whole answer.
                    val o = runCatching { JSONObject(source.readUtf8()) }.getOrNull()
                        ?: throw AssistantFailure(AssistantError(AssistantError.Kind.EMPTY))
                    val m = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                    split.feed(m?.optString("reasoning_content").orEmpty() + m?.optString("reasoning").orEmpty(), m?.optString("content")?.takeIf { it != "null" }.orEmpty())
                    m?.optJSONArray("tool_calls")?.let { addCalls(calls, it) }
                    finishOf(o.optJSONArray("choices")?.optJSONObject(0)?.optString("finish_reason"))
                    return@use
                }
                var done = false
                try {
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val data = line.substring(5).trim()
                        if (data == "[DONE]") { done = true; break }
                        val o = runCatching { JSONObject(data) }.getOrNull() ?: continue
                        o.optJSONObject("error")?.let { err ->
                            throw AssistantFailure(AssistantError.ofStatus(err.optInt("code", 500).takeIf { it in 400..599 } ?: 500, data, 0))
                        }
                        val choice = o.optJSONArray("choices")?.optJSONObject(0) ?: continue
                        val d = choice.optJSONObject("delta")
                        if (d != null) {
                            val think = d.optString("reasoning_content").takeIf { it != "null" }.orEmpty() +
                                d.optString("reasoning").takeIf { it != "null" }.orEmpty()
                            val text = d.optString("content").takeIf { it != "null" }.orEmpty()
                            if (think.isNotEmpty() || text.isNotEmpty()) split.feed(think, text)
                            d.optJSONArray("tool_calls")?.let { addCalls(calls, it) }
                        }
                        val fin = choice.optString("finish_reason").takeIf { it.isNotBlank() && it != "null" }
                        if (fin != null) { finishOf(fin); done = true }
                    }
                } catch (e: IOException) {
                    throw AssistantFailure(AssistantError(AssistantError.Kind.CUT_OFF, detail = e.message.orEmpty().take(120)))
                }
                if (!done) throw AssistantFailure(AssistantError(AssistantError.Kind.CUT_OFF))
            }
        } finally {
            cancel?.dispose()
        }
        calls.values.filter { it[1].isNotBlank() }.mapIndexed { i, c -> ToolCall(c[0].ifBlank { "call_$i" }, c[1], c[2], c[3].ifBlank { null }) }
    }

    /** Tool calls arrive in pieces: an index, then the name, then the arguments bit by bit. */
    private fun addCalls(acc: java.util.TreeMap<Int, Array<String>>, a: JSONArray) {
        for (i in 0 until a.length()) {
            val c = a.optJSONObject(i) ?: continue
            val slot = acc.getOrPut(c.optInt("index", i)) { arrayOf("", "", "", "") }
            c.optString("id").takeIf { it.isNotBlank() && it != "null" }?.let { slot[0] = it }
            c.optJSONObject("extra_content")?.let { slot[3] = it.toString() }
            val f = c.optJSONObject("function") ?: continue
            f.optString("name").takeIf { it.isNotBlank() && it != "null" }?.let { slot[1] = it }
            f.optString("arguments").takeIf { it != "null" }?.let { slot[2] += it }
        }
    }

    /** A picture from the service's image model, as bytes. */
    suspend fun image(p: AssistantProfile, key: String, prompt: String, size: String): ByteArray = withContext(Dispatchers.IO) {
        checkAddress(p.endpoint)
        val payload = JSONObject().put("model", p.imageModel).put("prompt", prompt).put("n", 1).put("size", size)
        val body = exec(client(p, 180).newCall(request(p, key, "/images/generations").post(payload.toString().toRequestBody(JSON)).build()))
        val d = runCatching { JSONObject(body).optJSONArray("data")?.optJSONObject(0) }.getOrNull()
            ?: throw AssistantFailure(AssistantError(AssistantError.Kind.EMPTY))
        d.optString("b64_json").takeIf { it.isNotBlank() && it != "null" }?.let { return@withContext android.util.Base64.decode(it, android.util.Base64.DEFAULT) }
        val url = d.optString("url").takeIf { it.startsWith("https://") } ?: throw AssistantFailure(AssistantError(AssistantError.Kind.EMPTY))
        // The picture waits on the service's storage: fetched the same way (no key sent there).
        client(p, 120).newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw AssistantFailure(AssistantError.ofStatus(r.code, "", 0))
            r.body?.bytes() ?: throw AssistantFailure(AssistantError(AssistantError.Kind.EMPTY))
        }
    }

    /** Speech from the service's voice model, as an Ogg/Opus file. */
    suspend fun speech(p: AssistantProfile, key: String, text: String): ByteArray = withContext(Dispatchers.IO) {
        checkAddress(p.endpoint)
        val payload = JSONObject().put("model", p.ttsModel).put("voice", p.ttsVoice.ifBlank { "alloy" })
            .put("input", text).put("response_format", "opus")
        val call = client(p, 120).newCall(request(p, key, "/audio/speech").post(payload.toString().toRequestBody(JSON)).build())
        try {
            call.execute().use { r ->
                if (!r.isSuccessful) throw AssistantFailure(AssistantError.ofStatus(r.code, r.body?.string().orEmpty(), r.header("Retry-After")?.toIntOrNull() ?: 0))
                r.body?.bytes()?.takeIf { it.isNotEmpty() } ?: throw AssistantFailure(AssistantError(AssistantError.Kind.EMPTY))
            }
        } catch (e: IOException) {
            throw networkFailure(p, e)
        }
    }

    private fun finishOf(reason: String?) {
        if (reason == "content_filter") throw AssistantFailure(AssistantError(AssistantError.Kind.REFUSED))
    }

    private suspend fun exec(call: Call): String = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        try {
            call.execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    cont.resumeWith(Result.failure(AssistantFailure(AssistantError.ofStatus(r.code, body, r.header("Retry-After")?.toIntOrNull() ?: 0))))
                } else cont.resumeWith(Result.success(body))
            }
        } catch (e: IOException) {
            cont.resumeWith(Result.failure(networkFailure(null, e)))
        }
    }

    private fun networkFailure(p: AssistantProfile?, e: IOException): AssistantFailure {
        val kind = when {
            e is java.net.SocketTimeoutException || e is java.io.InterruptedIOException -> AssistantError.Kind.TIMEOUT
            else -> AssistantError.Kind.NO_NETWORK
        }
        return AssistantFailure(AssistantError(kind, detail = AssistantError.redact(e.message.orEmpty()).take(160)))
    }

    /** Splits `<think>…</think>` written into the answer itself (some models do). */
    private class ThinkSplitter(val out: (String, String) -> Unit) {
        private var inThink = false
        private var pending = ""
        fun feed(think: String, text: String) {
            if (think.isNotEmpty()) out(think, "")
            if (text.isEmpty()) return
            var s = pending + text
            pending = ""
            while (s.isNotEmpty()) {
                val tag = if (inThink) "</think>" else "<think>"
                val i = s.indexOf(tag)
                if (i < 0) {
                    // A tag may be split across pieces: hold back a possible start of one.
                    val keep = (1 until tag.length).lastOrNull { s.endsWith(tag.substring(0, it)) } ?: 0
                    val emit = s.substring(0, s.length - keep)
                    pending = s.substring(s.length - keep)
                    if (emit.isNotEmpty()) { if (inThink) out(emit, "") else out("", emit) }
                    return
                }
                val before = s.substring(0, i)
                if (before.isNotEmpty()) { if (inThink) out(before, "") else out("", before) }
                inThink = !inThink
                s = s.substring(i + tag.length)
            }
        }
    }
}
