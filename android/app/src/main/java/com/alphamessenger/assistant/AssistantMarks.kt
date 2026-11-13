package com.alphamessenger.assistant

import org.json.JSONObject

/**
 * How an assistant reply is stored in its message body. The answer comes first
 * as plain text (so search and the chat list show it); the reasoning follows
 * after an invisible mark. A failed request is stored as an error mark.
 * Built from Char(2) so the source stays ASCII, like the other marks.
 */
object AssistantMarks {
    val THINK: String = Char(2) + "AIT" + Char(2)
    val ERROR: String = Char(2) + "AIE" + Char(2)
    val RELAYS: String = Char(2) + "AIR" + Char(2)

    fun reply(answer: String, thinking: String, seconds: Int, agent: String = ""): String =
        if (thinking.isBlank() && agent.isBlank()) answer
        else answer + THINK + JSONObject().put("t", thinking).put("s", seconds).put("a", agent).toString()

    fun error(e: AssistantError): String = ERROR + e.toJson().toString()

    fun relays(plan: RelayPlan): String = RELAYS + plan.toJson().toString()

    fun relaysOf(body: String): RelayPlan? =
        if (!body.startsWith(RELAYS)) null
        else runCatching { RelayPlan.fromJson(JSONObject(body.substring(RELAYS.length))) }.getOrNull()

    /** The visible answer of a stored body (what a preview or a copy shows). */
    fun answerOf(body: String): String = when {
        body.startsWith(ERROR) -> ""
        body.startsWith(RELAYS) -> relaysOf(body)?.reason.orEmpty()
        else -> body.substringBefore(THINK)
    }

    /** Reasoning, seconds and the council member who wrote the reply. */
    data class Meta(val thinking: String, val seconds: Int, val agent: String)

    fun metaOf(body: String): Meta? {
        val i = body.indexOf(THINK).takeIf { it >= 0 } ?: return null
        return runCatching {
            val o = JSONObject(body.substring(i + THINK.length))
            Meta(o.optString("t"), o.optInt("s"), o.optString("a"))
        }.getOrNull()?.takeIf { it.thinking.isNotBlank() || it.agent.isNotBlank() }
    }

    fun errorOf(body: String): AssistantError? =
        if (!body.startsWith(ERROR)) null
        else runCatching { AssistantError.fromJson(JSONObject(body.substring(ERROR.length))) }.getOrNull()
}

/** What went wrong with a request, in terms a person can act on. */
data class AssistantError(
    val kind: Kind,
    /** HTTP status, 0 when there was no answer at all. */
    val code: Int = 0,
    /** The service's own message, shortened; never contains the key. */
    val detail: String = "",
    /** Seconds the service asked to wait (429). */
    val retryAfter: Int = 0,
) {
    enum class Kind {
        // TOR_DOWN: only in answers stored while assistants could go through Tor.
        NO_NETWORK, TIMEOUT, TOR_DOWN, BAD_REQUEST, BAD_KEY, NOT_FOUND, TOO_LARGE,
        RATE_LIMIT, SERVER, CUT_OFF, EMPTY, REFUSED, UNSUPPORTED_MEDIA, NOT_SET_UP, BAD_ADDRESS,
    }

    fun toJson(): JSONObject = JSONObject().put("k", kind.name).put("c", code).put("d", detail).put("r", retryAfter)

    companion object {
        fun fromJson(o: JSONObject) = AssistantError(
            runCatching { Kind.valueOf(o.optString("k")) }.getOrDefault(Kind.SERVER),
            o.optInt("c"), o.optString("d"), o.optInt("r"),
        )

        /** An HTTP failure: the status decides the kind, the body adds the reason. */
        fun ofStatus(code: Int, body: String, retryAfter: Int): AssistantError {
            val msg = serviceMessage(body)
            val media = Regex("image|vision|video|audio|multimodal|content type", RegexOption.IGNORE_CASE).containsMatchIn(msg)
            val kind = when {
                code == 401 || code == 403 -> Kind.BAD_KEY
                code == 404 -> Kind.NOT_FOUND
                code == 413 -> Kind.TOO_LARGE
                code == 429 -> Kind.RATE_LIMIT
                code in 500..599 -> Kind.SERVER
                code == 400 && media -> Kind.UNSUPPORTED_MEDIA
                code == 400 && Regex("content.?(filter|policy)|safety", RegexOption.IGNORE_CASE).containsMatchIn(msg) -> Kind.REFUSED
                else -> Kind.BAD_REQUEST
            }
            return AssistantError(kind, code, msg, retryAfter)
        }

        /** The readable part of an error body ({"error":{"message":…}} or plain text). */
        private fun serviceMessage(body: String): String {
            val m = runCatching {
                val o = JSONObject(body)
                o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: o.optString("error").takeIf { it.isNotBlank() }
                    ?: o.optString("message").takeIf { it.isNotBlank() }
                    ?: o.optString("detail").takeIf { it.isNotBlank() }
            }.getOrNull() ?: body
            return redact(m).trim().take(400)
        }

        /** Some services echo the key back in their errors: never store it. */
        fun redact(s: String): String = s.replace(Regex("(sk|key|pk|rk)-[A-Za-z0-9_\\-]{6,}"), "•••")
    }
}

/** Relay changes the assistant offers; nothing happens until the user applies them. */
@androidx.compose.runtime.Immutable
data class RelayPlan(
    val add: List<String>,
    val enable: List<String>,
    val disable: List<String>,
    val remove: List<String>,
    val reconnect: Boolean,
    val reason: String,
) {
    val isEmpty: Boolean get() = add.isEmpty() && enable.isEmpty() && disable.isEmpty() && remove.isEmpty() && !reconnect

    /** For the journal and the model's own history. */
    fun summary(): String = listOfNotNull(
        add.takeIf { it.isNotEmpty() }?.let { "add " + it.joinToString() },
        enable.takeIf { it.isNotEmpty() }?.let { "on " + it.joinToString() },
        disable.takeIf { it.isNotEmpty() }?.let { "off " + it.joinToString() },
        remove.takeIf { it.isNotEmpty() }?.let { "remove " + it.joinToString() },
        "reconnect".takeIf { reconnect },
    ).joinToString("; ")

    fun toJson(): JSONObject = JSONObject()
        .put("add", org.json.JSONArray(add)).put("on", org.json.JSONArray(enable))
        .put("off", org.json.JSONArray(disable)).put("rm", org.json.JSONArray(remove))
        .put("rc", reconnect).put("why", reason)

    companion object {
        fun fromJson(o: JSONObject): RelayPlan {
            fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            return RelayPlan(list("add"), list("on"), list("off"), list("rm"), o.optBoolean("rc"), o.optString("why"))
        }
    }
}
