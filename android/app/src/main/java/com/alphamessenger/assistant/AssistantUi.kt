package com.alphamessenger.assistant

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.chat.AssistantThinkingUi
import com.alphamessenger.ui.chat.MessageUi
import com.alphamessenger.ui.theme.AlphaColorScheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val BADGE = Color(0xFF4EA4FF)
private val ERR = Color(0xFFFF6B6E)

/** A chat id the app shows as the assistant (only local chats can have it). */
fun isAssistantChat(chatId: String): Boolean = chatId.startsWith(AssistantProfile.CHAT_PREFIX)

/** The name given to the assistant in its settings; the chat's own name can lag behind a rename. */
@Composable
fun assistantNameOf(chatId: String): String? {
    val profiles by AssistantStore.profiles.collectAsState()
    if (!isAssistantChat(chatId)) return null
    return profiles.firstOrNull { it.chatId == chatId }?.name?.takeIf { it.isNotBlank() }
}

/** The small robot mark beside a bot's name, so a bot is told apart from a person at a glance. */
@Composable
fun BotMark(size: Dp = 16.dp, modifier: Modifier = Modifier) {
    androidx.compose.material3.Icon(
        com.alphamessenger.ui.icons.AlphaIcons.Outlined.Robot, null,
        tint = Color(0xFF8F9BFF), modifier = modifier.size(size),
    )
}

/** The verified seal: drawn only for the assistant's own local chat, never from a name. */
@Composable
fun VerifiedBadge(size: Dp = 16.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val r = this.size.minDimension / 2
        val seal = Path()
        val points = 24
        for (i in 0 until points) {
            val a = 2 * PI * i / points - PI / 2
            val rr = if (i % 2 == 0) r else r * 0.84f
            val p = Offset(c.x + (rr * cos(a)).toFloat(), c.y + (rr * sin(a)).toFloat())
            if (i == 0) seal.moveTo(p.x, p.y) else seal.lineTo(p.x, p.y)
        }
        seal.close()
        drawPath(seal, BADGE)
        val tick = Path().apply {
            moveTo(c.x - r * 0.38f, c.y + r * 0.02f)
            lineTo(c.x - r * 0.1f, c.y + r * 0.3f)
            lineTo(c.x + r * 0.4f, c.y - r * 0.26f)
        }
        drawPath(tick, Color.White, style = Stroke(width = r * 0.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** The assistant's avatar: the same everywhere, so it is recognised at a glance. */
@Composable
fun AssistantAvatar(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(CircleShape)
            .background(Brush.radialGradient(listOf(Color(0xFF8F9BFF), Color(0xFF4A3FD1)))),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            com.alphamessenger.ui.icons.AlphaIcons.Outlined.Robot, null,
            tint = Color.White, modifier = Modifier.size(size * 0.56f),
        )
    }
}

/**
 * The rows an assistant chat shows, newest first: each stored reply gets its
 * reasoning (and, in a council, its member) as a row above it, a stored error
 * becomes an error card, a picture of the assistant gets "Set as wallpaper",
 * and the answer on its way goes on top, one voice after another.
 */
fun assistantRows(newestFirst: List<MessageUi>, live: AssistantEngine.Live?, nowIso: String): List<MessageUi> {
    val out = ArrayList<MessageUi>(newestFirst.size + 8)
    if (live != null) {
        live.generating?.let { g -> out += MessageUi(id = LIVE_GEN + g.name, text = "", timestamp = nowIso, isOutgoing = false) }
        // A part whose row is already in the list gives way to it in this same frame.
        val shown = live.parts.filter { p -> p.storedId.isEmpty() || newestFirst.none { it.id == p.storedId } }
        // Newest first: the last voice is the lowest.
        shown.reversed().forEach { p ->
            // The keys the stored rows will have: the list sees one item grow, then settle.
            if (p.answer.isNotEmpty()) out += MessageUi(id = p.rowId, text = p.answer, timestamp = nowIso, isOutgoing = false, assistantWriting = true)
            if (p.thinking.isNotEmpty() || p.answer.isEmpty() || p.agent.isNotEmpty()) {
                out += MessageUi(
                    id = p.rowId + THINK_SUFFIX, text = "", timestamp = nowIso, isOutgoing = false, assistantWriting = true,
                    assistantThinking = AssistantThinkingUi(
                        p.thinking, p.thoughtSeconds,
                        live = p.answer.isEmpty(), connecting = p.phase == AssistantEngine.Phase.CONNECTING, agent = p.agent,
                    ),
                )
            }
        }
    }
    for (m in newestFirst) {
        if (m.isOutgoing) { out += m; continue }
        if (m.isMedia) {
            if (m.mediaType == "image" && m.localMediaPath != null) {
                out += MessageUi(id = m.id + WALLPAPER_SUFFIX, text = m.localMediaPath, timestamp = m.timestamp, isOutgoing = false)
            }
            out += m
            continue
        }
        val err = AssistantMarks.errorOf(m.text)
        if (err != null) { out += m.copy(text = "", assistantError = err); continue }
        val plan = AssistantMarks.relaysOf(m.text)
        if (plan != null) { out += m.copy(text = "", assistantRelays = plan); continue }
        val meta = AssistantMarks.metaOf(m.text)
        out += m.copy(text = AssistantMarks.answerOf(m.text))
        if (meta != null) {
            out += MessageUi(
                id = m.id + THINK_SUFFIX, text = "", timestamp = m.timestamp, isOutgoing = false,
                assistantThinking = AssistantThinkingUi(meta.thinking, meta.seconds, agent = meta.agent),
            )
        }
    }
    return out
}

const val LIVE_GEN = "assistant-live-gen:"
private const val THINK_SUFFIX = "#think"
private const val WALLPAPER_SUFFIX = "#wallpaper"

/**
 * What the list shows of [live]: the answer being written, or a written one until
 * its rows are in [messages] — the chat reloads a moment after the engine is done,
 * and a frame with neither would let the list fall to the bottom.
 */
fun shownLive(live: AssistantEngine.Live?, messages: List<MessageUi>): AssistantEngine.Live? {
    if (live == null || !live.done) return live
    val waiting = live.parts.filter { p -> messages.none { it.id == p.storedId } }
    return if (waiting.isEmpty()) null else live.copy(parts = waiting)
}

/**
 * Lets an answer onto the screen at an even pace however the service sends it (a word
 * or a whole page at a time), the newest letters fading in. Ticked once per frame, so
 * the list grows by a line at most and its held start never sees a jump.
 */
@androidx.compose.runtime.Stable
class AnswerReveal {
    class Shown(val text: String, val fade: FloatArray)

    private class Row(var full: String, var writing: Boolean) {
        var shown = 0f
        var end = 0
        /** Start of the oldest stretch still fading in. */
        var base = 0
        /** Where each stretch let in ends, and the frame it came in. */
        val steps = ArrayDeque<Pair<Int, Long>>()
    }

    private val rows = HashMap<String, Row>()
    private val clusters = android.icu.text.BreakIterator.getCharacterInstance()
    private var frame by androidx.compose.runtime.mutableLongStateOf(0L)
    private var last = 0L

    /** Letters are still on their way or fading in. */
    var busy by mutableStateOf(false)
        private set

    /**
     * What row [id] shows now, or null for its whole text. An answer seen while written is
     * paced to its end, also once stored; one only read from history never is.
     */
    fun of(id: String, full: String, writing: Boolean): Shown? {
        val now = frame
        val row = rows[id] ?: run {
            if (!writing || full.isEmpty()) return null
            Row(full, true).also { rows[id] = it; busy = true }
        }
        row.full = full
        row.writing = writing
        var end = row.end.coerceAtMost(full.length)
        // Whole letters only: an emoji of several code points (a flag, a skin tone, a family) cut
        // in two shows as a stray sign until the rest comes.
        if (end in 1 until full.length) {
            clusters.setText(full)
            if (!clusters.isBoundary(end)) end = clusters.preceding(end).coerceAtLeast(0)
        }
        if (end >= full.length && row.steps.isEmpty()) {
            if (!writing) rows.remove(id)
            return null
        }
        // More text than was paced (the last piece came with "done", or the stored answer is
        // longer): the frames must run again, or the rest would never come in.
        if (!busy) busy = true
        // fade[k]: the k-th letter from the end.
        val fade = FloatArray((end - row.base).coerceAtLeast(0))
        var start = row.base
        for ((stepEnd, at) in row.steps) {
            val a = ((now - at).toFloat() / FADE_NS).coerceIn(0.08f, 1f)
            for (c in start until stepEnd.coerceAtMost(end)) fade[end - 1 - c] = a
            start = stepEnd
        }
        return Shown(full.substring(0, end), fade)
    }

    /** One frame: every row lets in what its backlog asks for. */
    fun tick(nanos: Long) {
        val dt = if (last == 0L) 1f / 60 else ((nanos - last) / 1e9f).coerceIn(0f, 0.05f)
        last = nanos
        var behind = false
        for (row in rows.values) {
            val backlog = row.full.length - row.shown
            if (backlog > 0f) {
                val rate = (backlog / LAG_S).coerceIn(MIN_CPS, MAX_CPS)
                row.shown = (row.shown + rate * dt).coerceAtMost(row.full.length.toFloat())
                val end = row.shown.toInt()
                if (end > row.end) {
                    if (row.steps.isEmpty()) row.base = row.end
                    row.steps.addLast(end to nanos)
                    row.end = end
                }
            }
            while (row.steps.isNotEmpty() && nanos - row.steps.first().second >= FADE_NS) {
                row.base = row.steps.removeFirst().first
            }
            if (row.end < row.full.length || row.steps.isNotEmpty()) behind = true
        }
        frame = nanos
        if (!behind) {
            last = 0L
            if (busy) busy = false
        }
    }

    private companion object {
        /** How far behind the service the screen may fall before it speeds up. */
        const val LAG_S = 0.7f
        const val MIN_CPS = 45f
        const val MAX_CPS = 900f
        const val FADE_NS = 320_000_000L
    }
}

/** Where the answer's start is held: the row above it, and whether the list moves itself. */
class AnswerPin {
    var key: String? = null
    var ownScroll = false
    /** The newest message when the hold ended: already where it should be, not scrolled to. */
    var releasedFor: String? = null
}


/** What is being made, if [id] is its placeholder row. */
fun liveGenOf(id: String): GenKind? = id.takeIf { it.startsWith(LIVE_GEN) }?.let { runCatching { GenKind.valueOf(it.removePrefix(LIVE_GEN)) }.getOrNull() }

/** The picture's file, if [id] is its "Set as wallpaper" row. */
fun wallpaperRowPath(m: MessageUi): String? = m.text.takeIf { m.id.endsWith(WALLPAPER_SUFFIX) && it.isNotBlank() }

/** "Thought for N s": open while the model thinks, folded after; a tap toggles it. */
@Composable
fun AssistantThinkingRow(t: AssistantThinkingUi, colors: AlphaColorScheme) {
    var open by rememberSaveable(t.live) { mutableStateOf(t.live) }
    val onlyName = !t.live && t.text.isBlank() && t.agent.isNotBlank()
    Column(
        Modifier
            .padding(start = 12.dp, end = 56.dp, top = 4.dp, bottom = 2.dp)
            .widthIn(max = 420.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.bgSecondary.copy(alpha = 0.92f))
            .border(1.dp, colors.alphaBorder, RoundedCornerShape(14.dp))
            .clickable(enabled = t.text.isNotBlank()) { open = !open }
            .animateContentSize()
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (t.live) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = colors.accent)
            if (t.agent.isNotBlank()) {
                Text(t.agent, color = colors.accent, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            }
            if (!onlyName) {
                val label = when {
                    t.connecting -> stringResource(R.string.assistant_connecting)
                    t.live -> stringResource(R.string.assistant_thinking_live, t.seconds)
                    else -> stringResource(R.string.assistant_thought, t.seconds.coerceAtLeast(1))
                }
                Text(label, color = colors.textSecondary, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
            }
            if (t.text.isNotBlank()) {
                Text("›", color = colors.textMuted, fontSize = 14.sp, modifier = Modifier.rotate(if (open) 90f else 0f))
            }
        }
        if (open && t.text.isNotBlank()) {
            Row {
                Box(Modifier.padding(end = 9.dp).size(width = 2.dp, height = 12.dp).background(colors.alphaBorder))
                SelectionContainer {
                    Text(
                        t.text.trim().takeLast(if (t.live) 1500 else 20_000),
                        color = colors.textMuted, fontSize = 12.5.sp, lineHeight = 17.sp,
                    )
                }
            }
        }
    }
}

/**
 * What is being made, before it exists: a soft wave drifts through the bubble
 * (a voice gets dancing bars), then the real picture or voice replaces it.
 */
@Composable
fun AssistantGenPlaceholder(kind: GenKind, colors: AlphaColorScheme) {
    val t = rememberInfiniteTransition(label = "gen")
    val drift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Reverse), label = "drift")
    val sheen by t.animateFloat(-0.4f, 1.4f, infiniteRepeatable(tween(1700, easing = LinearEasing)), label = "sheen")
    val label = stringResource(when (kind) {
        GenKind.IMAGE -> R.string.assistant_gen_image
        GenKind.VIDEO -> R.string.assistant_gen_video
        GenKind.VOICE -> R.string.assistant_gen_voice
    })
    if (kind == GenKind.VOICE) {
        Row(
            Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp).clip(RoundedCornerShape(16.dp))
                .background(colors.msgIn).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            repeat(22) { i ->
                val phase = ((drift * 6f + i * 0.37f) % 1f)
                val h = 6f + 18f * kotlin.math.abs(kotlin.math.sin(phase * PI.toFloat()))
                Box(Modifier.size(width = 3.dp, height = h.dp).clip(RoundedCornerShape(2.dp)).background(colors.accent.copy(alpha = 0.85f)))
            }
            Text(label, color = colors.textSecondary, fontSize = 12.5.sp, modifier = Modifier.padding(start = 8.dp))
        }
        return
    }
    Box(
        Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp)
            .size(width = 240.dp, height = if (kind == GenKind.VIDEO) 150.dp else 240.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF1C1F27)),
    ) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            fun blob(cx: Float, cy: Float, r: Float, c: Color) = drawCircle(
                Brush.radialGradient(listOf(c, Color.Transparent), center = Offset(cx, cy), radius = r), radius = r, center = Offset(cx, cy),
            )
            blob(w * (0.25f + 0.35f * drift), h * (0.35f + 0.2f * drift), w * 0.6f, Color(0xFF7D8BFF).copy(alpha = 0.55f))
            blob(w * (0.75f - 0.3f * drift), h * (0.7f - 0.25f * drift), w * 0.55f, Color(0xFF4FD8C8).copy(alpha = 0.45f))
            blob(w * (0.5f + 0.2f * kotlin.math.sin(drift * 6.28f)), h * 0.15f, w * 0.4f, Color(0xFFFF8CC8).copy(alpha = 0.35f))
            // A band of light sweeping across.
            val x = w * sheen
            drawRect(Brush.linearGradient(
                listOf(Color.Transparent, Color.White.copy(alpha = 0.13f), Color.Transparent),
                start = Offset(x - w * 0.3f, 0f), end = Offset(x + w * 0.3f, h),
            ))
        }
        Text(
            label, color = Color.White, fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)
                .clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.4f)).padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** Under a picture of the assistant: put it behind the chats, after a yes. */
@Composable
fun AssistantWallpaperChip(path: String, colors: AlphaColorScheme) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val themeVm: com.alphamessenger.ui.theme.ThemeViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    var asking by remember { mutableStateOf(false) }
    Text(
        stringResource(R.string.assistant_set_wallpaper),
        color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 14.dp, top = 2.dp, bottom = 6.dp)
            .clip(RoundedCornerShape(12.dp)).background(colors.bgSecondary)
            .clickable { asking = true }.padding(horizontal = 12.dp, vertical = 6.dp),
    )
    if (asking) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { asking = false },
            containerColor = colors.bgSecondary,
            title = { Text(stringResource(R.string.assistant_set_wallpaper_q), color = colors.textPrimary) },
            text = { Text(stringResource(R.string.assistant_set_wallpaper_text), color = colors.textSecondary) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    asking = false
                    // The wallpaper is read by the image loader: it gets a plain copy.
                    val dir = java.io.File(ctx.filesDir, "wallpapers").apply { mkdirs() }
                    val out = java.io.File(dir, "ai_${System.currentTimeMillis()}.png")
                    val ok = runCatching { com.alphamessenger.data.MediaVault.copyPlain(java.io.File(path), out) }.isSuccess
                    if (ok) {
                        themeVm.setChatWallpaper(com.alphamessenger.ui.theme.ChatWallpaper.Gallery("gallery:${out.path}", "file://${out.path}"))
                        AssistantStore.log("", JournalEntry.Kind.ACT, "wallpaper", "")
                        android.widget.Toast.makeText(ctx, ctx.getString(R.string.assistant_wallpaper_set), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }) { Text(stringResource(R.string.assistant_set_wallpaper_yes), color = colors.accent) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { asking = false }) { Text(stringResource(R.string.common_cancel), color = colors.textMuted) }
            },
        )
    }
}

/** Relay changes offered by the assistant: applied only by the user's tap. */
@Composable
fun AssistantRelayCard(messageId: String, plan: RelayPlan, colors: AlphaColorScheme, onApply: (done: (Int) -> Unit) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val applied by AssistantStore.applied.collectAsState()
    var busy by remember { mutableStateOf(false) }
    val done = messageId in applied
    Column(
        Modifier
            .padding(start = 12.dp, end = 48.dp, top = 4.dp, bottom = 4.dp)
            .widthIn(max = 440.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.bgSecondary)
            .border(1.dp, colors.accent.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.assistant_relays_title), color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        if (plan.reason.isNotBlank()) Text(plan.reason, color = colors.textSecondary, fontSize = 13.sp, lineHeight = 18.sp)
        plan.add.forEach { RelayLine("+", stringResource(R.string.assistant_relays_add), it, OK, colors) }
        plan.enable.forEach { RelayLine("●", stringResource(R.string.assistant_relays_enable), it, OK, colors) }
        plan.disable.forEach { RelayLine("○", stringResource(R.string.assistant_relays_disable), it, colors.textMuted, colors) }
        plan.remove.forEach { RelayLine("✕", stringResource(R.string.assistant_relays_remove), it, ERR, colors) }
        if (plan.reconnect) RelayLine("↻", stringResource(R.string.assistant_relays_reconnect), "", colors.accent, colors)
        if (done) {
            Text("✓ " + stringResource(R.string.assistant_relays_applied), color = colors.textMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        } else {
            ErrorButton(stringResource(if (busy) R.string.assistant_relays_applying else R.string.assistant_relays_apply), colors, primary = true) {
                if (busy) return@ErrorButton
                busy = true
                onApply { failed ->
                    busy = false
                    if (failed > 0) android.widget.Toast.makeText(ctx, ctx.getString(R.string.assistant_relays_failed, failed), android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

@Composable
private fun RelayLine(mark: String, action: String, url: String, tint: Color, colors: AlphaColorScheme) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(mark, color = tint, fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.widthIn(min = 14.dp))
        Text(
            if (url.isEmpty()) action else action + " · " + url.removePrefix("wss://").trimEnd('/'),
            color = colors.textPrimary, fontSize = 13.sp, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

private val OK = Color(0xFF34C759)

/** A failed request: what happened, in words, and what to do about it. */
@Composable
fun AssistantErrorCard(
    e: AssistantError,
    colors: AlphaColorScheme,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
) {
    var details by remember { mutableStateOf(false) }
    val (title, text) = errorWords(e)
    Column(
        Modifier
            .padding(start = 12.dp, end = 48.dp, top = 4.dp, bottom = 4.dp)
            .widthIn(max = 440.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(ERR.copy(alpha = 0.12f))
            .border(1.dp, ERR.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("⚠", color = ERR, fontSize = 14.sp)
            Text(title, color = ERR, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
            if (e.code > 0) {
                Text(
                    e.code.toString(), color = ERR, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(ERR.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
        Text(text, color = colors.textPrimary, fontSize = 13.5.sp, lineHeight = 18.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ErrorButton(stringResource(R.string.assistant_retry), colors, primary = true, onClick = onRetry)
            if (e.kind in SETTINGS_KINDS) ErrorButton(stringResource(R.string.assistant_open_settings), colors, primary = false, onClick = onSettings)
        }
        if (e.detail.isNotBlank()) {
            Text(
                stringResource(if (details) R.string.assistant_hide_answer else R.string.assistant_service_answer),
                color = colors.textMuted, fontSize = 12.sp,
                modifier = Modifier.clickable { details = !details },
            )
            if (details) SelectionContainer {
                Text(
                    e.detail, color = colors.textSecondary, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.bgSecondary).padding(8.dp),
                )
            }
        }
    }
}

private val SETTINGS_KINDS = setOf(
    AssistantError.Kind.BAD_KEY, AssistantError.Kind.NOT_FOUND, AssistantError.Kind.NOT_SET_UP,
    AssistantError.Kind.BAD_ADDRESS, AssistantError.Kind.UNSUPPORTED_MEDIA, AssistantError.Kind.TOR_DOWN,
)

@Composable
private fun ErrorButton(text: String, colors: AlphaColorScheme, primary: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (primary) Color.White else colors.textPrimary,
        fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(10.dp))
            .background(if (primary) colors.accent else colors.bgSecondary)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun errorWords(e: AssistantError): Pair<String, String> = when (e.kind) {
    AssistantError.Kind.NO_NETWORK -> stringResource(R.string.assistant_err_network) to stringResource(R.string.assistant_err_network_text)
    AssistantError.Kind.TIMEOUT -> stringResource(R.string.assistant_err_timeout) to stringResource(R.string.assistant_err_timeout_text)
    AssistantError.Kind.TOR_DOWN -> stringResource(R.string.assistant_err_tor) to stringResource(R.string.assistant_err_tor_text)
    AssistantError.Kind.BAD_REQUEST -> stringResource(R.string.assistant_err_bad_request) to
        (e.detail.takeIf { it.isNotBlank() } ?: stringResource(R.string.assistant_err_bad_request_text))
    AssistantError.Kind.BAD_KEY -> stringResource(R.string.assistant_err_key) to stringResource(R.string.assistant_err_key_text)
    AssistantError.Kind.NOT_FOUND -> stringResource(R.string.assistant_err_not_found) to stringResource(R.string.assistant_err_not_found_text)
    AssistantError.Kind.TOO_LARGE -> stringResource(R.string.assistant_err_too_large) to stringResource(R.string.assistant_err_too_large_text)
    AssistantError.Kind.RATE_LIMIT -> stringResource(R.string.assistant_err_rate) to
        (if (e.retryAfter > 0) stringResource(R.string.assistant_err_rate_wait, e.retryAfter) else stringResource(R.string.assistant_err_rate_text))
    AssistantError.Kind.SERVER -> stringResource(R.string.assistant_err_server) to stringResource(R.string.assistant_err_server_text)
    AssistantError.Kind.CUT_OFF -> stringResource(R.string.assistant_err_cut) to stringResource(R.string.assistant_err_cut_text)
    AssistantError.Kind.EMPTY -> stringResource(R.string.assistant_err_empty) to stringResource(R.string.assistant_err_empty_text)
    AssistantError.Kind.REFUSED -> stringResource(R.string.assistant_err_refused) to stringResource(R.string.assistant_err_refused_text)
    AssistantError.Kind.UNSUPPORTED_MEDIA -> stringResource(R.string.assistant_err_media) to
        stringResource(if (e.detail == "voice") R.string.assistant_err_voice_text else R.string.assistant_err_media_text)
    AssistantError.Kind.NOT_SET_UP -> stringResource(R.string.assistant_err_setup) to stringResource(R.string.assistant_err_setup_text)
    AssistantError.Kind.BAD_ADDRESS -> stringResource(R.string.assistant_err_address) to stringResource(R.string.assistant_err_address_text)
}

/** "Stop" under the composer while an answer is coming. */
@Composable
fun AssistantStopChip(colors: AlphaColorScheme, onStop: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "stop")
    val a by pulse.animateFloat(0.55f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    Row(
        Modifier.clip(RoundedCornerShape(18.dp)).background(colors.bgSecondary)
            .border(1.dp, colors.alphaBorder, RoundedCornerShape(18.dp))
            .clickable(onClick = onStop).padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(9.dp).alpha(a).clip(RoundedCornerShape(2.dp)).background(colors.textPrimary))
        Text(stringResource(R.string.assistant_stop), color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Keeps the live label's seconds moving between stream updates. */
@Composable
fun rememberTicker(active: Boolean): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        while (active) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) }
    }
    return now
}
