package com.alphamessenger.service

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.alphamessenger.R
import com.alphamessenger.data.AlphaCoreRepository
import com.alphamessenger.data.CoreHolder
import com.alphamessenger.ui.icons.AlphaIcons
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * What a notification's "React" opens: a card over whatever is on screen, with
 * a row of emoji for the newest message and a voice reply. A window of our own
 * works on every system skin, where inline reply choices do not.
 */
@AndroidEntryPoint
class ReactActivity : ComponentActivity() {

    @Inject lateinit var repository: AlphaCoreRepository
    @Inject lateinit var coreHolder: CoreHolder

    private var recorder: MediaRecorder? = null
    private var recording: File? = null
    private var onMicGranted: (() -> Unit)? = null
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) onMicGranted?.invoke() else Toast.makeText(this, getString(R.string.notif_voice_no_mic), Toast.LENGTH_SHORT).show()
        onMicGranted = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val chatId = intent.getStringExtra(MessageNotifier.EXTRA_CHAT_ID).orEmpty()
        val messageId = intent.getStringExtra(MessageNotifier.EXTRA_MSG_ID).orEmpty()
        val chatTitle = intent.getStringExtra(MessageNotifier.EXTRA_CHAT_TITLE).orEmpty()
        val isGroup = intent.getBooleanExtra(MessageNotifier.EXTRA_IS_GROUP, false)
        // Locked, or sending from the shade not allowed: nothing to do here.
        if (chatId.isBlank() || messageId.isBlank() || coreHolder.peek() == null ||
            !com.alphamessenger.data.AppLockManager(this).notificationReplyAllowed
        ) {
            Toast.makeText(this, getString(R.string.notif_react_locked), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val quoted = MessageNotifier.linesFor(chatId).lastOrNull { it.msgId == messageId }?.text.orEmpty()
        setContent {
            var busy by remember { mutableStateOf(false) }
            var voice by remember { mutableStateOf(false) }
            var seconds by remember { mutableIntStateOf(0) }
            val levels = remember { mutableStateListOf<Float>() }
            val rise = remember { Animatable(0f) }
            LaunchedEffect(Unit) { rise.animateTo(1f, tween(220)) }
            // The clock and the level while it records.
            LaunchedEffect(voice) {
                if (!voice) return@LaunchedEffect
                var ms = 0
                while (true) {
                    delay(100); ms += 100
                    val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                    levels.add((amp / 9000f).coerceIn(0.05f, 1f)); if (levels.size > 40) levels.removeAt(0)
                    if (ms % 1000 == 0) seconds++
                    if (seconds >= MAX_SECONDS) break
                }
            }
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f * rise.value))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (!voice) finish() },
                contentAlignment = Alignment.BottomCenter,
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding()
                        .graphicsLayer { translationY = (1f - rise.value) * 120f; alpha = rise.value }
                        .clip(RoundedCornerShape(26.dp)).background(Color(0xF21B1B26))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                ) {
                    Text(chatTitle, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (quoted.isNotBlank()) Text(
                        quoted, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                    )
                    AnimatedContent(targetState = voice, label = "card") { rec ->
                        if (!rec) Column {
                            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                PICKS.forEach { emoji ->
                                    Box(
                                        Modifier.size(42.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f))
                                            .clickable(enabled = !busy) { busy = true; react(chatId, chatTitle, isGroup, messageId, emoji) },
                                        contentAlignment = Alignment.Center,
                                    ) { Text(emoji, fontSize = 23.sp) }
                                }
                            }
                            // A voice reply, recorded right here.
                            Row(
                                Modifier.fillMaxWidth().padding(top = 14.dp).height(48.dp).clip(RoundedCornerShape(15.dp))
                                    .background(Color.White.copy(alpha = 0.10f))
                                    .clickable(enabled = !busy) { withMic { if (startRecording()) { seconds = 0; levels.clear(); voice = true } } },
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(AlphaIcons.Outlined.Mic, null, tint = Color.White, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(getString(R.string.notif_voice), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            }
                        } else Row(
                            Modifier.fillMaxWidth().padding(top = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(46.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.10f))
                                    .clickable(enabled = !busy) { stopRecording(keep = false); voice = false },
                                contentAlignment = Alignment.Center,
                            ) { Icon(AlphaIcons.Outlined.Close, getString(R.string.common_cancel), tint = Color.White, modifier = Modifier.size(20.dp)) }
                            Spacer(Modifier.width(12.dp))
                            val pulse by rememberInfiniteTransition(label = "rec").animateFloat(
                                0.7f, 1.15f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "dot",
                            )
                            Box(Modifier.size(10.dp).scale(pulse).clip(CircleShape).background(Color(0xFFFF3B30)))
                            Spacer(Modifier.width(8.dp))
                            Text("%d:%02d".format(seconds / 60, seconds % 60), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.width(10.dp))
                            Row(Modifier.weight(1f).height(28.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                                levels.forEach { l -> Box(Modifier.width(3.dp).height((28 * l).dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.75f))) }
                            }
                            Spacer(Modifier.width(12.dp))
                            Box(
                                Modifier.size(46.dp).clip(CircleShape).background(Color(0xFF00C2A8))
                                    .clickable(enabled = !busy && seconds >= 1) { busy = true; sendVoice(chatId, chatTitle, isGroup, messageId) },
                                contentAlignment = Alignment.Center,
                            ) { Icon(AlphaIcons.AutoMirrored.Outlined.Send, getString(R.string.common_send), tint = Color.White, modifier = Modifier.size(20.dp)) }
                        }
                    }
                }
            }
        }
    }

    private fun withMic(then: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) then()
        else { onMicGranted = then; askMic.launch(Manifest.permission.RECORD_AUDIO) }
    }

    /** The same recording as the chat's: Opus in Ogg, a "voice_" file. */
    private fun startRecording(): Boolean = runCatching {
        val file = File(cacheDir, "voice_${System.currentTimeMillis()}.ogg")
        recording = file
        recorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.OGG)
            setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            setAudioSamplingRate(24000)
            setAudioEncodingBitRate(24000)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        true
    }.getOrElse {
        recording?.delete(); recording = null; recorder = null
        Toast.makeText(this, getString(R.string.notif_voice_no_mic), Toast.LENGTH_SHORT).show()
        false
    }

    /** Stop; the file is kept for sending, or thrown away. */
    private fun stopRecording(keep: Boolean): File? {
        val rec = recorder ?: return null
        val ok = runCatching { rec.stop() }.isSuccess
        runCatching { rec.release() }
        recorder = null
        val f = recording
        recording = null
        if (!keep || !ok) { f?.delete(); return null }
        return f
    }

    /** Through the core's send queue: it goes out (and survives a restart) like any attachment. */
    private fun sendVoice(chatId: String, chatTitle: String, isGroup: Boolean, messageId: String) {
        val file = stopRecording(keep = true)
        val app = applicationContext
        lifecycleScope.launch {
            val done = file != null && withContext(Dispatchers.IO) {
                runCatching {
                    val ttl = runCatching { repository.chatTimerSeconds(chatId) }.getOrDefault(0).coerceAtLeast(0).toUInt()
                    val id = repository.persistOutgoingMedia(chatId, file.absolutePath, "audio", null, ttl)
                    // In a group it answers the message the card is about, before it goes out.
                    if (isGroup) repository.setMediaReply(id, messageId)
                    repository.enqueuePendingMediaSingle(chatId, id, file.absolutePath, "audio", null, null, false, ttl)
                    // Sent now, outliving this card; the queue keeps it if the app dies first.
                    repository.detachedScope.launch { runCatching { repository.retryPendingMedia(id) } }
                    runCatching { repository.markChatRead(chatId) }
                    true
                }.getOrDefault(false)
            }
            repost(app, chatId, chatTitle, isGroup, app.getString(if (done) R.string.notif_voice_sent else R.string.notif_voice_failed))
            finish()
        }
    }

    private fun react(chatId: String, chatTitle: String, isGroup: Boolean, messageId: String, emoji: String) {
        val app = applicationContext
        lifecycleScope.launch {
            val done = withContext(Dispatchers.IO) {
                // A group that allows only some reactions is asked first, as in the chat.
                val allowed = !isGroup || runCatching { repository.groupReactionPolicy(chatId).allows(emoji) }.getOrDefault(true)
                val sent = allowed && runCatching { repository.sendReaction(chatId, messageId, emoji) }.isSuccess
                if (sent) runCatching { repository.markChatRead(chatId) }
                sent
            }
            repost(app, chatId, chatTitle, isGroup, app.getString(if (done) R.string.notif_reacted else R.string.notif_react_failed, emoji))
            finish()
        }
    }

    /** The card stays truthful: what was just done shows as our own line. */
    private fun repost(app: android.content.Context, chatId: String, chatTitle: String, isGroup: Boolean, line: String) {
        MessageNotifier.appendSelfLine(
            chatId,
            MessageNotifier.Line(
                senderName = app.getString(R.string.notif_you), avatarPath = "",
                text = line, timeMs = System.currentTimeMillis(), isSelf = true,
            ),
        )
        MessageNotifier(app).post(chatId = chatId, chatTitle = chatTitle, isGroup = isGroup, unreadCount = 0,
            muted = true, preview = MessageNotifier.currentPreview)
    }

    override fun onDestroy() {
        stopRecording(keep = false)
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private companion object {
        val PICKS = listOf("❤️", "👍", "😂", "🔥", "😮", "😢", "🙏", "👎")
        /** A voice reply from the shade is a quick answer, not a podcast. */
        const val MAX_SECONDS = 300
    }
}
