package com.alphamessenger.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.theme.AlphaTheme
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * "Check the model": a few scripted requests, outside any chat and stored
 * nowhere, that show what this service does with our tools: answers at all and
 * streams, makes a theme, makes a second one in a row, obeys "call this tool",
 * makes a background alone. So models are compared on facts, not on guesses.
 */
object AssistantProbe {
    class Result(val label: Int, val ok: Boolean, val detail: String)

    suspend fun run(p: AssistantProfile, key: String, out: (Result) -> Unit) {
        val look = AssistantTools.specs(p.copy(perms = p.perms + AssistantPerm.THEMES), only = setOf("propose_theme", "propose_pattern"))
        val sys = ChatTurn("system", "You are an assistant inside a messenger. When a tool does what is asked, call it.")
        fun secs(t0: Long) = "%.1f s".format((System.currentTimeMillis() - t0) / 1000.0)

        // 1. A plain answer, streamed: when the first word comes and when the last.
        run {
            val t0 = System.currentTimeMillis()
            var first = 0L
            val text = StringBuilder()
            val r = runCatching {
                AssistantClient.chat(p, key, listOf(sys, ChatTurn("user", "Say hello in five words."))) { _, a ->
                    if (a.isNotBlank() && first == 0L) first = System.currentTimeMillis()
                    text.append(a)
                }
            }
            out(Result(R.string.assistant_check_plain, r.isSuccess && text.isNotBlank(),
                r.exceptionOrNull()?.let { why(it) } ?: ("1st word " + (if (first > 0) "%.1f s".format((first - t0) / 1000.0) else "—") + " · " + secs(t0))))
        }
        // 2. A theme, then 3. another one in the same conversation.
        val ask1 = ChatTurn("user", "Make me a dark theme about coffee.")
        var firstCall: ToolCall? = null
        run {
            val t0 = System.currentTimeMillis()
            val r = runCatching { AssistantClient.chat(p, key, listOf(sys, ask1), look) { _, _ -> } }
            firstCall = r.getOrNull()?.firstOrNull { it.name == "propose_theme" }
            out(Result(R.string.assistant_check_theme, firstCall?.let { AssistantTools.themeArgsOk(it.arguments) } == true,
                r.exceptionOrNull()?.let { why(it) } ?: (firstCall?.let { "propose_theme · " + secs(t0) } ?: "no tool call · " + secs(t0))))
        }
        run {
            val c = firstCall
            if (c == null) { out(Result(R.string.assistant_check_again, false, "—")); return@run }
            val t0 = System.currentTimeMillis()
            val turns = listOf(
                sys, ask1,
                ChatTurn("assistant", "", toolCalls = listOf(c)),
                ChatTurn("tool", "Done: the theme card is in the chat.", toolCallId = c.id),
                ChatTurn("assistant", "Here is your coffee theme."),
                ChatTurn("user", "Nice! Now another one, light, about the sea."),
            )
            val r = runCatching { AssistantClient.chat(p, key, turns, look) { _, _ -> } }
            val call = r.getOrNull()?.firstOrNull { it.name == "propose_theme" }
            out(Result(R.string.assistant_check_again, call?.let { AssistantTools.themeArgsOk(it.arguments) } == true,
                r.exceptionOrNull()?.let { why(it) } ?: (if (call != null) "propose_theme · " + secs(t0) else "no tool call · " + secs(t0))))
        }
        // 4. Told which tool to call (tool_choice): the app's way to make sure a theme comes.
        run {
            val t0 = System.currentTimeMillis()
            val r = runCatching { AssistantClient.chat(p, key, listOf(sys, ChatTurn("user", "A theme about forests, please.")), look, force = "propose_theme") { _, _ -> } }
            val call = r.getOrNull()?.firstOrNull { it.name == "propose_theme" }
            out(Result(R.string.assistant_check_forced, call != null,
                r.exceptionOrNull()?.let { why(it) } ?: (if (call != null) "tool_choice ✓ · " + secs(t0) else "ignored · " + secs(t0))))
        }
        // 5. A background alone.
        run {
            val t0 = System.currentTimeMillis()
            val r = runCatching { AssistantClient.chat(p, key, listOf(sys, ChatTurn("user", "Make a chat background with cats.")), look) { _, _ -> } }
            val call = r.getOrNull()?.firstOrNull { it.name == "propose_pattern" || it.name == "propose_theme" }
            out(Result(R.string.assistant_check_pattern, call != null,
                r.exceptionOrNull()?.let { why(it) } ?: ((call?.name ?: "no tool call") + " · " + secs(t0))))
        }
    }

    private fun why(e: Throwable): String = (e as? AssistantFailure)?.error?.let { it.kind.name.lowercase() + (it.code.takeIf { c -> c > 0 }?.let { c -> " $c" } ?: "") }
        ?: (e.message ?: e.javaClass.simpleName).take(60)
}

/** The "Check the model" block of a profile's settings. */
@Composable
fun ModelCheck(profile: () -> AssistantProfile, key: () -> String) {
    val cs = AlphaTheme.colorScheme
    val scope = rememberCoroutineScope()
    val results = remember { mutableStateListOf<AssistantProbe.Result>() }
    var running by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.assistant_check_hint), color = cs.textMuted, fontSize = 12.5.sp)
        Text(
            stringResource(if (running) R.string.assistant_check_running else R.string.assistant_check),
            color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(cs.accent)
                .clickable(enabled = !running) {
                    running = true
                    results.clear()
                    scope.launch {
                        try { AssistantProbe.run(profile(), key()) { results += it } } finally { running = false }
                    }
                }
                .padding(horizontal = 14.dp, vertical = 9.dp),
        )
        results.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (r.ok) "✓" else "✗", color = if (r.ok) Color(0xFF34C759) else cs.danger, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(stringResource(r.label), color = cs.textPrimary, fontSize = 13.5.sp)
                    Text(r.detail, color = cs.textMuted, fontSize = 12.sp)
                }
            }
        }
    }
}
