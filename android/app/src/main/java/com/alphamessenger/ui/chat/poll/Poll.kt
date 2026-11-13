package com.alphamessenger.ui.chat.poll

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.data.AlphaCoreRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** What a poll asks (from its message's ref). */
data class PollSpec(
    val question: String,
    val options: List<String>,
    val anon: Boolean,
    val multi: Boolean,
    val quiz: Boolean,
    val right: Int,
    val explanation: String,
    /** Unix seconds; 0 = never closes by itself. */
    val closeAt: Long,
) {
    companion object {
        fun parse(ref: String?): PollSpec? = runCatching {
            val o = JSONObject(ref ?: return null)
            if (!o.optBoolean("poll")) return null
            val arr = o.optJSONArray("o") ?: return null
            PollSpec(
                o.optString("q"), (0 until arr.length()).map { arr.optString(it) },
                o.optBoolean("anon", true), o.optBoolean("multi"), o.optBoolean("quiz"),
                o.optInt("right"), o.optString("expl"), o.optLong("close_at"),
            )
        }.getOrNull()
    }
}

/** Where a poll stands. */
data class PollResults(
    val counts: List<Int> = emptyList(),
    val voters: Int = 0,
    val closed: Boolean = false,
    val mine: Set<Int> = emptySet(),
    /** Open polls: who chose what (voter id → options). */
    val by: List<Pair<String, List<Int>>> = emptyList(),
) {
    companion object {
        fun parse(json: String): PollResults? = runCatching {
            if (json.isBlank()) return null
            val o = JSONObject(json)
            fun ints(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.optInt(it) }
            PollResults(
                ints(o.optJSONArray("counts")), o.optInt("voters"), o.optBoolean("closed"),
                ints(o.optJSONArray("mine")).toSet(),
                (0 until (o.optJSONArray("by")?.length() ?: 0)).map { i ->
                    val b = o.getJSONArray("by").getJSONObject(i)
                    b.optString("v") to ints(b.optJSONArray("o"))
                },
            )
        }.getOrNull()
    }
}

/** The chat's polls: its id, the core, a tick that moves with every vote, voters' names. */
class PollHost(val chatId: String, val repo: AlphaCoreRepository, val tick: Int, val bump: () -> Unit, private val names: PollNames) {
    val voterName: (String) -> String get() = names.of

    /** Name voters like the chat names its members ("self" = [you]). */
    fun names(you: String, profiles: Map<String, com.alphamessenger.ui.chat.ChatBubblePeer>) {
        names.of = { id -> if (id == "self") you else profiles[id]?.displayName?.takeIf { it.isNotBlank() } ?: id.take(8) }
    }
}

/** How the chat names a voter (set by the chat screen, which knows its members). */
class PollNames { var of: (String) -> String = { it.take(8) } }

/** "New poll" asked for (the attach panel's action), answered by the chat screen. */
object PollUi {
    var compose by mutableStateOf(false)
}

val LocalPollHost = staticCompositionLocalOf<PollHost?> { null }

/** Wraps a chat: its polls re-read when votes arrive (the chat's metadata event). */
@Composable
fun PollScope(chatId: String, repo: AlphaCoreRepository, content: @Composable () -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(chatId) { repo.chatMetadataUpdated.filter { it == chatId }.collect { tick++ } }
    val names = remember(chatId) { PollNames() }
    val host = remember(chatId, repo, tick) { PollHost(chatId, repo, tick, { tick++ }, names) }
    CompositionLocalProvider(LocalPollHost provides host, content = content)
}

/**
 * A poll inside its bubble: the question, the options (tap to vote), the
 * results once voted (or closed) with bars that fill in, a quiz's right and
 * wrong answers, and the footer (votes · withdraw · who chose what · close).
 */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun PollCard(
    messageId: String,
    ref: String?,
    outgoing: Boolean,
    textColor: Color,
    accent: Color,
    /** A voter's name ("self" = you). */
    voterName: (String) -> String,
) {
    val spec = remember(ref) { PollSpec.parse(ref) } ?: return
    val host = LocalPollHost.current
    val scope = rememberCoroutineScope()
    val results by produceState(PollResults(), messageId, host?.tick) {
        val h = host ?: return@produceState
        value = withContext(Dispatchers.IO) { PollResults.parse(h.repo.pollResults(h.chatId, messageId)) } ?: value
    }
    var whoOpen by remember { mutableStateOf(false) }
    var confetti by remember { mutableIntStateOf(0) }
    val ctx = LocalContext.current
    val now = System.currentTimeMillis() / 1000
    val closed = results.closed || (spec.closeAt in 1 until now)
    val voted = results.mine.isNotEmpty()
    val showResults = voted || closed
    val total = results.counts.sum().coerceAtLeast(0)
    val onBubble = if (outgoing) Color.White else accent
    val right = Color(0xFF2FB37A)
    val wrong = Color(0xFFE0505A)

    fun vote(choice: List<Int>) {
        val h = host ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { h.repo.pollVote(h.chatId, messageId, choice) }.isSuccess }
            if (ok) {
                h.bump()
                if (spec.quiz && choice.firstOrNull() == spec.right) confetti++
            }
        }
    }

    Box {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 2.dp).width(258.dp)) {
            Text(spec.question, color = textColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)
            val kind = stringResource(when {
                spec.quiz -> R.string.poll_kind_quiz
                spec.multi && spec.anon -> R.string.poll_kind_multi_anon
                spec.multi -> R.string.poll_kind_multi
                spec.anon -> R.string.poll_kind_anon
                else -> R.string.poll_kind_open
            })
            val time = when {
                closed -> stringResource(R.string.poll_closed)
                spec.closeAt > now -> stringResource(R.string.poll_closes_in, closesIn(spec.closeAt - now))
                else -> null
            }
            Text(listOfNotNull(kind, time).joinToString(" · "), color = textColor.copy(alpha = 0.7f), fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))

            spec.options.forEachIndexed { i, label ->
                val count = results.counts.getOrElse(i) { 0 }
                val share = if (total > 0) count.toFloat() / total else 0f
                val mine = i in results.mine
                val isRight = spec.quiz && showResults && i == spec.right
                val isWrong = spec.quiz && showResults && mine && i != spec.right
                val fill by animateFloatAsState(if (showResults) share else 0f, tween(550), label = "pollBar")
                val canTap = !closed && (!voted || spec.multi)
                Box(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp).heightIn(min = 40.dp).clip(RoundedCornerShape(12.dp))
                        .background(textColor.copy(alpha = 0.07f))
                        .clickable(enabled = canTap && host != null) {
                            if (spec.multi) vote((results.mine.toMutableSet().apply { if (!add(i)) remove(i) }).sorted())
                            else vote(listOf(i))
                        },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    // The bar of this option's share.
                    Box(Modifier.matchParentSize()) {
                        Box(
                            Modifier.fillMaxHeight().fillMaxWidth(fill).background(
                                when { isRight -> right.copy(alpha = 0.40f); isWrong -> wrong.copy(alpha = 0.40f); else -> textColor.copy(alpha = 0.14f) },
                            ),
                        )
                    }
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        val markOn = mine || isRight
                        Box(
                            Modifier.size(19.dp).clip(if (spec.multi) RoundedCornerShape(5.dp) else CircleShape)
                                .background(when { isRight -> right; isWrong -> wrong; mine -> onBubble; else -> Color.Transparent })
                                .border(2.dp, if (markOn || isWrong) Color.Transparent else textColor.copy(alpha = 0.5f), if (spec.multi) RoundedCornerShape(5.dp) else CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            when {
                                isWrong -> androidx.compose.material3.Icon(AlphaIcons.Filled.Close, null, tint = Color.White, modifier = Modifier.size(13.dp))
                                markOn -> androidx.compose.material3.Icon(AlphaIcons.Filled.Check, null,
                                    tint = if (isRight) Color.White else if (outgoing) accent else Color.White, modifier = Modifier.size(13.dp))
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(label, color = textColor, fontSize = 14.5.sp, modifier = Modifier.weight(1f))
                        if (showResults) Text("${(share * 100).toInt()}%", color = textColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (spec.quiz && showResults && spec.explanation.isNotBlank()) {
                Text("💡 " + spec.explanation, color = textColor, fontSize = 12.5.sp, lineHeight = 17.sp,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(10.dp))
                        .background(right.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    pluralStringResource(if (spec.quiz) R.plurals.poll_answers else R.plurals.poll_votes, results.voters, results.voters),
                    color = textColor.copy(alpha = 0.7f), fontSize = 12.sp, modifier = Modifier.weight(1f),
                )
                if (voted && !closed && !spec.quiz) FooterAction(stringResource(R.string.poll_withdraw), onBubble) { vote(emptyList()) }
                if (!spec.anon && results.by.isNotEmpty()) FooterAction(stringResource(R.string.poll_who), onBubble) { whoOpen = true }
                if (outgoing && !closed) FooterAction(stringResource(R.string.poll_close), onBubble) {
                    val h = host ?: return@FooterAction
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { runCatching { h.repo.pollClose(h.chatId, messageId) }.isSuccess }
                        if (ok) h.bump() else android.widget.Toast.makeText(ctx, R.string.poll_close_failed, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        if (confetti > 0) Confetti(confetti, Modifier.matchParentSize())
    }

    if (whoOpen) com.alphamessenger.ui.components.AlphaSheet(onDismissRequest = { whoOpen = false }) {
        val cs = com.alphamessenger.ui.theme.AlphaTheme.colorScheme
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(spec.question, color = cs.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
            spec.options.forEachIndexed { i, label ->
                val names = results.by.filter { i in it.second }.map { voterName(it.first) }
                Text("$label · ${names.size}", color = cs.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                if (names.isEmpty()) Text("—", color = cs.textMuted, fontSize = 14.sp)
                names.forEach { Text(it, color = cs.textPrimary, fontSize = 15.sp, modifier = Modifier.padding(vertical = 3.dp)) }
            }
        }
    }
}

@Composable
private fun Text(text: String, color: Color, fontSize: androidx.compose.ui.unit.TextUnit, modifier: Modifier = Modifier,
                 fontWeight: FontWeight? = null, lineHeight: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified) =
    androidx.compose.material3.Text(text, color = color, fontSize = fontSize, fontWeight = fontWeight, lineHeight = lineHeight, modifier = modifier)

@Composable
private fun FooterAction(label: String, color: Color, onClick: () -> Unit) {
    Text(label, color = color, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 2.dp, vertical = 4.dp))
}

@Composable
private fun closesIn(secs: Long): String = when {
    secs >= 86_400 -> pluralStringResource(R.plurals.poll_days, (secs / 86_400).toInt(), (secs / 86_400).toInt())
    secs >= 3_600 -> pluralStringResource(R.plurals.poll_hours, (secs / 3_600).toInt(), (secs / 3_600).toInt())
    else -> pluralStringResource(R.plurals.poll_minutes, (secs / 60).toInt().coerceAtLeast(1), (secs / 60).toInt().coerceAtLeast(1))
}

/** A burst of confetti over the poll (a right quiz answer). */
@Composable
private fun Confetti(key: Int, modifier: Modifier) {
    val t = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { t.snapTo(0f); t.animateTo(1f, tween(1400)) }
    if (t.value >= 1f) return
    val parts = remember(key) {
        val r = java.util.Random(key.toLong())
        List(46) { floatArrayOf((r.nextFloat() - 0.5f) * 2f, -0.6f - r.nextFloat() * 1.1f, r.nextFloat() * 6f, r.nextInt(5).toFloat()) }
    }
    val colors = listOf(Color(0xFF2FB37A), Color(0xFF3D9DF0), Color(0xFFE0A21F), Color(0xFFE0507A), Color(0xFF7C5CF0))
    Canvas(modifier) {
        val p = t.value
        parts.forEach { (vx, vy, spin, c) ->
            val x = size.width / 2 + vx * size.width * 0.55f * p
            val y = size.height * 0.45f + (vy * p + 1.6f * p * p) * size.height * 0.5f
            rotate(spin * 57f + p * 540f, Offset(x, y)) {
                drawRect(colors[c.toInt()].copy(alpha = 1f - p * p), Offset(x - 4f, y - 2f), Size(9f, 5f))
            }
        }
    }
}
