package com.alphamessenger.calls.group

import com.alphamessenger.ui.icons.AlphaIcons
import android.Manifest
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import com.alphamessenger.R
import com.alphamessenger.calls.ui.CallLookPrefs
import com.alphamessenger.calls.ui.CallTopBar
import com.alphamessenger.calls.ui.ControlPanel
import com.alphamessenger.calls.ui.EndPill
import com.alphamessenger.calls.ui.PanelButton
import com.alphamessenger.calls.ui.TopCircle
import com.alphamessenger.calls.ui.callBackdrop
import com.alphamessenger.data.AlphaCoreRepository
import com.alphamessenger.ui.theme.AlphaTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import javax.inject.Inject

private val Talk = Color(0xFF2FB37A)

private data class Face(val name: String, val avatar: String, val role: String = "")

/** The group call screen: ringing, then the call itself. Back folds it into the app's call bar. */
@AndroidEntryPoint
class GroupCallActivity : androidx.appcompat.app.AppCompatActivity() {
    @Inject lateinit var manager: GroupCallManager
    @Inject lateinit var engine: GroupWebRtcEngine
    @Inject lateinit var core: AlphaCoreRepository
    private val themeViewModel: com.alphamessenger.ui.theme.ThemeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // The call's backdrop runs under the status and navigation bars.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION") window.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION") window.navigationBarColor = android.graphics.Color.TRANSPARENT
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContent {
            val variant by themeViewModel.themeVariant.collectAsState()
            val custom by themeViewModel.activeCustom.collectAsState()
            AlphaTheme(variant = variant, custom = custom, navigationScrim = false) { Screen(variant.isLight) }
        }
    }

    /**
     * Leave the screen, not the call: the app's call bar brings it back.
     * The call lives in a task of its own; only moving that task back left it to
     * the system what showed next — the first time the home screen (the app was
     * "closed"), later the chat. The app is brought forward explicitly instead.
     */
    private fun minimize() {
        runCatching {
            startActivity(
                android.content.Intent(this, com.alphamessenger.ui.MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }.onFailure { moveTaskToBack(true) }
    }

    @Composable
    private fun Screen(light: Boolean) {
        val st by manager.state.collectAsState()
        val colors = AlphaTheme.colorScheme
        val ctx = androidx.compose.ui.platform.LocalContext.current
        // Opened to start or join: ask for the microphone (and camera) first.
        val startChat = remember { intent.getStringExtra(GroupCalls.EXTRA_START_CHAT) }
        val joinCall = remember { intent.getStringExtra(GroupCalls.EXTRA_JOIN_CALL) }
        val wantVideo = remember { intent.getBooleanExtra(GroupCalls.EXTRA_VIDEO, false) }
        val ringAgain = remember { intent.getBooleanExtra(GroupCalls.EXTRA_RING_AGAIN, false) }
        var pending by remember { mutableStateOf(startChat != null && st.phase == GroupCallPhase.IDLE) }
        val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { got ->
            if (got[Manifest.permission.RECORD_AUDIO] == true && startChat != null) {
                val video = wantVideo && got[Manifest.permission.CAMERA] == true
                if (joinCall != null) manager.join(startChat, joinCall, video) else manager.start(startChat, video, ringAgain)
            }
            pending = false
        }
        val torOk = remember { !com.alphamessenger.calls.TorCallWarning.needed(ctx) }
        if (pending && !torOk) {
            com.alphamessenger.calls.TorCallWarningDialog(onDismiss = { pending = false })
        }
        LaunchedEffect(torOk) {
            if (pending && torOk) ask.launch(if (wantVideo) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) else arrayOf(Manifest.permission.RECORD_AUDIO))
        }
        LaunchedEffect(st.phase, pending) {
            if (!pending && st.phase == GroupCallPhase.IDLE) { delay(1200); if (manager.state.value.phase == GroupCallPhase.IDLE) finish() }
        }
        var askedMic by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            manager.notices.collect { n ->
                val text = when (n) {
                    GroupCallNotice.MUTED_BY_ADMIN -> ctx.getString(R.string.gcall_muted_by_admin)
                    GroupCallNotice.CALLS_OFF -> ctx.getString(R.string.gcall_calls_off)
                    GroupCallNotice.ASKED_TO_UNMUTE -> { askedMic = true; null }
                }
                text?.let { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_SHORT).show() }
            }
        }
        if (askedMic) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { askedMic = false },
                containerColor = Color(0xFF181B21),
                title = { Text(stringResource(R.string.gcall_asked_mic), color = Color.White) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { askedMic = false; manager.setMic(true) }) {
                        Text(stringResource(R.string.gcall_mic_on), color = Talk)
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { askedMic = false }) {
                        Text(stringResource(R.string.gcall_not_now), color = Color.White.copy(alpha = 0.7f))
                    }
                },
            )
        }
        BackHandler {
            when (st.phase) {
                GroupCallPhase.IN_CALL -> minimize()
                GroupCallPhase.RINGING -> manager.decline()
                GroupCallPhase.IDLE -> finish()
            }
        }
        val chat by produceState("" to "", st.chatId) {
            value = runCatching {
                core.listChats(includeRequests = true).firstOrNull { it.id == st.chatId }?.let { it.name.orEmpty() to it.avatarPath }
            }.getOrNull() ?: ("" to "")
        }
        val cfg by produceState(com.alphamessenger.ui.channel.ChannelCfg(), st.chatId) {
            if (st.chatId.isNotBlank()) value = core.channelCfg(st.chatId)
        }
        val faces by produceState(emptyMap<String, Face>(), st.chatId, st.members.keys, chat, cfg.anon) {
            if (st.chatId.isBlank()) return@produceState
            value = runCatching {
                val a = org.json.JSONArray(core.listGroupMembers(st.chatId))
                (0 until a.length()).associate { i ->
                    val o = a.getJSONObject(i)
                    val role = o.optString("role")
                    // A hidden owner is the chat itself to everyone else, in calls too.
                    val face = if (cfg.anon && role == "owner" && !o.optBoolean("is_self", false))
                        Face(chat.first, chat.second, role)
                    else Face(o.optString("display_name"), o.optString("avatar_path"), role)
                    o.optString("sender_id") to face
                }
            }.getOrDefault(emptyMap())
        }
        // Our own hidden ownership: say once what a call can still give away.
        var warned by remember(st.callId) { mutableStateOf(false) }
        if (st.phase == GroupCallPhase.IN_CALL && cfg.anon && cfg.persona && !warned) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { warned = true },
                containerColor = Color(0xFF181B21),
                title = { Text(stringResource(R.string.gcall_hidden_title), color = Color.White) },
                text = { Text(stringResource(R.string.gcall_hidden_text), color = Color.White.copy(alpha = 0.8f)) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { warned = true }) { Text(stringResource(R.string.common_ok), color = Talk) }
                },
            )
        }
        CallLookPrefs.load(ctx)
        Box(Modifier.fillMaxSize().callBackdrop(colors, light, CallLookPrefs.backdrop)) {
            when (st.phase) {
                GroupCallPhase.RINGING -> Ringing(chat, faces[st.ringFrom]?.name.orEmpty(), st.ringVideo)
                GroupCallPhase.IN_CALL -> InCall(st, chat.first, faces)
                GroupCallPhase.IDLE -> Unit
            }
        }
    }

    @Composable
    private fun Ringing(chat: Pair<String, String>, from: String, video: Boolean) {
        val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { got ->
            if (got[Manifest.permission.RECORD_AUDIO] == true) manager.accept(got[Manifest.permission.CAMERA] == true)
        }
        // Answering in private mode without a TURN server could never connect: say why instead.
        val ctx = androidx.compose.ui.platform.LocalContext.current
        var torRefused by remember { mutableStateOf(false) }
        if (torRefused) com.alphamessenger.calls.TorCallWarningDialog(onDismiss = { torRefused = false })
        fun answer(perms: Array<String>) {
            if (com.alphamessenger.calls.TorCallWarning.needed(ctx)) torRefused = true else ask.launch(perms)
        }
        Column(Modifier.fillMaxSize().statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(72.dp))
            Text(stringResource(if (video) R.string.gcall_incoming_video else R.string.gcall_incoming), color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp)
            Spacer(Modifier.height(28.dp))
            Avatar(Face(chat.first, chat.second), 132.dp, talking = false)
            Spacer(Modifier.height(22.dp))
            Text(chat.first, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(8.dp))
            if (from.isNotBlank()) Text(stringResource(R.string.gcall_calls_you, from), color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            ControlPanel {
                EndPill(stringResource(R.string.gcall_not_now), { manager.decline() })
                PanelButton(AlphaIcons.Filled.Mic, stringResource(R.string.gcall_voice), false, { answer(arrayOf(Manifest.permission.RECORD_AUDIO)) })
                PanelButton(AlphaIcons.Filled.Videocam, stringResource(R.string.gcall_video), true, {
                    answer(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA))
                })
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun InCall(st: GroupCallState, title: String, faces: Map<String, Face>) {
        val peers by engine.peers.collectAsState()
        val local by engine.localVideo.collectAsState()
        val speaking by manager.speaking.collectAsState()
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
        var people by remember { mutableStateOf(false) }
        val secs = ((now - st.since) / 1000).coerceAtLeast(0)
        val ids = st.members.keys.toList()
        val anyVideo = st.cam || st.members.values.any { it.cam }
        val me = Face(stringResource(R.string.gcall_you), faces[st.self]?.avatar.orEmpty())
        Column(Modifier.fillMaxSize()) {
            CallTopBar(onMinimize = { minimize() }) {
                TopCircle(AlphaIcons.Filled.Group, stringResource(R.string.gcall_people), { people = true })
            }
            Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
            Text(
                stringResource(R.string.gcall_status, ids.size + 1, "%d:%02d".format(secs / 60, secs % 60)),
                color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 10.dp),
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (anyVideo) {
                    LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize().padding(horizontal = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(ids, key = { it }) { id ->
                            val m = st.members[id]
                            VideoTile(faces[id] ?: Face("", ""), if (m?.cam == true) peers[id]?.video else null, mirror = false,
                                mic = m?.mic != false, connected = peers[id]?.connected == true, talking = id in speaking)
                        }
                        item(key = "self") {
                            VideoTile(me, if (st.cam) local else null, mirror = true, mic = st.mic, connected = true, talking = st.self in speaking)
                        }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        val admin = faces[st.self]?.role.let { it == "owner" || it == "admin" }
                        items(ids, key = { it }) { id ->
                            val m = st.members[id]
                            val micOn = m?.mic != false
                            Person(
                                faces[id] ?: Face("", ""), mic = micOn, connected = peers[id]?.connected == true, talking = id in speaking,
                                onMic = if (admin && faces[id]?.role != "owner") ({ if (micOn) manager.muteMember(id) else manager.askToUnmute(id) }) else null,
                            )
                        }
                        item(key = "self") { Person(me, mic = st.mic, connected = true, talking = st.self in speaking) }
                    }
                }
                if (ids.isEmpty()) {
                    Text(stringResource(R.string.gcall_waiting), color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp, modifier = Modifier.align(Alignment.Center))
                }
            }
            val camAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) manager.setCam(true) }
            ControlPanel {
                PanelButton(AlphaIcons.Filled.VolumeUp, stringResource(R.string.gcall_speaker), st.speaker, { manager.setSpeaker(!st.speaker) })
                PanelButton(if (st.cam) AlphaIcons.Filled.Videocam else AlphaIcons.Filled.VideocamOff, stringResource(R.string.gcall_camera), st.cam, {
                    if (st.cam) manager.setCam(false) else camAsk.launch(Manifest.permission.CAMERA)
                })
                if (st.cam) PanelButton(AlphaIcons.Filled.Cameraswitch, stringResource(R.string.gcall_flip), false, { manager.switchCamera() })
                PanelButton(if (st.mic) AlphaIcons.Filled.Mic else AlphaIcons.Filled.MicOff, stringResource(R.string.gcall_mic), !st.mic, { manager.setMic(!st.mic) })
                EndPill(stringResource(R.string.gcall_leave), { manager.leave() })
            }
        }
        if (people) {
            ModalBottomSheet(
                onDismissRequest = { people = false },
                containerColor = Color(0xFF181B21),
                contentWindowInsets = { androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0) },
            ) {
                PeopleSheet(st, faces, speaking)
            }
        }
    }

    /** Who is in the call (an admin can switch a microphone off) and who can be invited. */
    @Composable
    private fun PeopleSheet(st: GroupCallState, faces: Map<String, Face>, speaking: Set<String>) {
        val myRole = faces[st.self]?.role.orEmpty()
        val admin = myRole == "owner" || myRole == "admin"
        val outside = faces.filterKeys { it != st.self && it !in st.members.keys && it.isNotBlank() }
        var invited by remember { mutableStateOf(emptySet<String>()) }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(horizontal = 18.dp).navigationBarsPadding()) {
            item {
                Text(stringResource(R.string.gcall_in_call, st.members.size + 1, GroupCallManager.MAX_MEMBERS),
                    color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 10.dp))
            }
            items(st.members.keys.toList(), key = { "in_$it" }) { id ->
                val m = st.members[id]
                SheetRow(faces[id] ?: Face("", ""), talking = id in speaking) {
                    val micOn = m?.mic != false
                    if (admin && faces[id]?.role != "owner") {
                        // Off: an admin can switch it off; on: only the member can, so we ask.
                        Text(stringResource(if (micOn) R.string.gcall_mute_member else R.string.gcall_ask_mic), color = Color(0xFF79BCFF), fontSize = 13.sp,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp))
                                .clickable { if (micOn) manager.muteMember(id) else manager.askToUnmute(id) }.padding(8.dp))
                    } else {
                        Icon(if (micOn) AlphaIcons.Filled.Mic else AlphaIcons.Filled.MicOff, null, tint = Color.White.copy(alpha = 0.7f))
                    }
                }
            }
            if (outside.isNotEmpty() && st.members.size + 1 < GroupCallManager.MAX_MEMBERS) {
                item {
                    Text(stringResource(R.string.gcall_invite_section), color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp,
                        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                }
                items(outside.keys.toList(), key = { "out_$it" }) { id ->
                    SheetRow(outside.getValue(id), talking = false) {
                        val done = id in invited
                        Text(stringResource(if (done) R.string.gcall_invited else R.string.gcall_invite),
                            color = if (done) Color.White.copy(alpha = 0.5f) else Talk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = !done) { manager.invite(id); invited = invited + id }.padding(8.dp))
                    }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }

    @Composable
    private fun SheetRow(face: Face, talking: Boolean, trailing: @Composable () -> Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(face, 40.dp, talking)
            Spacer(Modifier.width(12.dp))
            Text(face.name.ifBlank { "…" }, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            trailing()
        }
    }

    @Composable
    private fun VideoTile(face: Face, track: VideoTrack?, mirror: Boolean, mic: Boolean, connected: Boolean, talking: Boolean) {
        val ring by animateFloatAsState(if (talking) 1f else 0f, label = "talk")
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.78f).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.08f))
                .border(3.dp, Talk.copy(alpha = ring), RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (track != null) {
                AndroidView(
                    factory = { SurfaceViewRenderer(it) },
                    update = { engine.bind(it, track, mirror) },
                    onRelease = { engine.unbind(it) },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Avatar(face, 72.dp, talking = false)
            }
            Row(
                Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!mic) { Icon(AlphaIcons.Filled.MicOff, null, tint = Color.White, modifier = Modifier.size(12.dp)); Spacer(Modifier.width(4.dp)) }
                Text(if (connected) face.name.ifBlank { "…" } else stringResource(R.string.gcall_connecting), color = Color.White, fontSize = 12.sp, maxLines = 1)
            }
        }
    }

    @Composable
    private fun Person(face: Face, mic: Boolean, connected: Boolean, talking: Boolean, onMic: (() -> Unit)? = null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(face, 52.dp, talking)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(face.name.ifBlank { "…" }, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(
                    when { !connected -> stringResource(R.string.gcall_connecting); talking -> stringResource(R.string.gcall_talking); else -> "" },
                    color = if (talking) Talk else Color.White.copy(alpha = 0.6f), fontSize = 12.sp,
                )
            }
            // For an admin it is a control: switch off, or ask to switch on.
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .then(if (onMic != null) Modifier.background(Color.White.copy(alpha = 0.10f)).clickable(onClick = onMic) else Modifier),
                contentAlignment = Alignment.Center,
            ) { Icon(if (mic) AlphaIcons.Filled.Mic else AlphaIcons.Filled.MicOff, null, tint = Color.White.copy(alpha = 0.75f)) }
        }
    }

    @Composable
    private fun Avatar(face: Face, size: Dp, talking: Boolean) {
        val ring by animateFloatAsState(if (talking) 1f else 0f, label = "talkRing")
        Box(
            Modifier.size(size).border(3.dp, Talk.copy(alpha = ring), CircleShape).padding(3.dp).clip(CircleShape)
                .background(AlphaTheme.colorScheme.accent),
            contentAlignment = Alignment.Center,
        ) {
            Text(face.name.firstOrNull()?.uppercase() ?: "?", color = Color.White, fontSize = (size.value / 2.4f).sp, fontWeight = FontWeight.SemiBold)
            if (face.avatar.isNotBlank()) AsyncImage(java.io.File(face.avatar), null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
    }
}
