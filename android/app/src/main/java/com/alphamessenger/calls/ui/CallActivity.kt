package com.alphamessenger.calls.ui

import android.app.PictureInPictureParams
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.alphamessenger.R
import com.alphamessenger.calls.CallPhase
import com.alphamessenger.calls.CallType
import com.alphamessenger.calls.engine.CallRendererBridge
import com.alphamessenger.calls.isTerminal
import com.alphamessenger.ui.theme.AlphaTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * THE call Activity — full-screen, over the lockscreen, singleTask, PiP-capable.
 *
 * Survives rotation (manifest declares configChanges), so the WebRTC renderer
 * + state are NEVER recreated mid-call. Hosts [CallScreen] which adapts to the
 * current call phase.
 *
 * ## Permission-first dialing
 * For OUTGOING calls the launcher passes the chat + type via extras — the
 * Activity requests RECORD_AUDIO (+ CAMERA for video) FIRST and only then
 * asks the manager to dial. Dialing before the grants would start the
 * camera-typed foreground service without the CAMERA permission — a
 * SecurityException on Android 14.
 *
 * ## Picture-in-Picture
 * Enters PiP when the user backgrounds the app during a video call.
 */
@AndroidEntryPoint
class CallActivity : androidx.appcompat.app.AppCompatActivity() {

    companion object {
        const val EXTRA_OUTGOING_CHAT = "outgoing_chat_id"
        const val EXTRA_OUTGOING_VIDEO = "outgoing_video"
    }

    private val viewModel: CallViewModel by viewModels()
    /** The user's theme — the call screen is drawn in it. */
    private val themeViewModel: com.alphamessenger.ui.theme.ThemeViewModel by viewModels()
    /** In the system's floating window: only the picture, no controls. */
    private var inPip by androidx.compose.runtime.mutableStateOf(false)

    @Inject lateinit var renderer: CallRendererBridge
    @Inject lateinit var core: com.alphamessenger.data.AlphaCoreRepository
    @Inject lateinit var groupCalls: com.alphamessenger.calls.group.GroupCallManager

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    /** Runs after the permission dialog resolves (dial / accept). */
    private var pendingOnGranted: (() -> Unit)? = null
    private var pendingNeedsCamera: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Show over the lockscreen + turn the screen on.
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        } else {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Edge-to-edge: the call screen draws under the status + nav bars (#8, #9).
        // The gradient background fills the whole screen — no black bars.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        // Color the status + nav bars to match the call screen's top/bottom.

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { results ->
            val micOk = results[android.Manifest.permission.RECORD_AUDIO]
                ?: hasPermission(android.Manifest.permission.RECORD_AUDIO)
            val camOk = !pendingNeedsCamera ||
                (results[android.Manifest.permission.CAMERA]
                    ?: hasPermission(android.Manifest.permission.CAMERA))
            if (micOk && camOk) {
                pendingOnGranted?.invoke()
            } else {
                Toast.makeText(this, this.getString(R.string.call_need_mic) +
                    (if (pendingNeedsCamera) this.getString(R.string.call_need_camera_suffix) else ""), Toast.LENGTH_LONG).show()
                if (viewModel.state.value.phase == CallPhase.IDLE) finish()
            }
            pendingOnGranted = null
        }

        handleOutgoingIntent(intent)

        setContent {
            val variant by themeViewModel.themeVariant.collectAsState()
            val customTheme by themeViewModel.activeCustom.collectAsState()
            AlphaTheme(variant = variant, custom = customTheme, navigationScrim = false) {
                val state by viewModel.state.collectAsState()
                torGate?.let { cancel ->
                    com.alphamessenger.calls.TorCallWarningDialog(onDismiss = { torGate = null; cancel() })
                }
                // The call collapsed back to IDLE (terminal linger finished,
                // and we're not waiting on a permission dialog) — close.
                LaunchedEffect(state.phase) {
                    if (state.phase == CallPhase.IDLE && pendingOnGranted == null && torGate == null &&
                        intent?.hasExtra(EXTRA_OUTGOING_CHAT) != true
                    ) finish()
                }
                CallScreen(
                    state = state,
                    renderer = renderer,
                    lightTheme = variant.isLight,
                    inPip = inPip,
                    actions = CallScreenActions(
                        accept = { guardTor { withPermissions(video = state.type == CallType.VIDEO) { viewModel.accept() } } },
                        reject = viewModel::reject,
                        replyWithText = viewModel::rejectWithMessage,
                        replyCustom = {
                            val chat = state.chatId
                            viewModel.reject()
                            openChat(chat)
                        },
                        remindLater = viewModel::remindLater,
                        cancel = viewModel::cancel,
                        hangup = viewModel::hangup,
                        toggleMute = viewModel::toggleMute,
                        setRoute = viewModel::setRoute,
                        toggleVideo = { withPermissions(video = true) { viewModel.toggleVideo() } },
                        switchCamera = viewModel::switchCamera,
                        toggleHold = viewModel::toggleHold,
                        acceptVideo = { withPermissions(video = true) { viewModel.acceptVideoRequest() } },
                        declineVideo = viewModel::declineVideoRequest,
                        // Minimize: a video call goes to the floating window, a voice
                        // call to the bar over the chats (the call lives in the service).
                        minimize = { if (!enterPip()) finish() },
                        message = { openChat(state.chatId) },
                        redial = { withPermissions(video = state.type == CallType.VIDEO) { viewModel.redial() } },
                        addPeople = { addingPeople.value = true },
                    ),
                )
                if (addingPeople.value) {
                    com.alphamessenger.calls.group.AddToCallSheet(
                        core = core,
                        currentChatId = state.chatId,
                        onDismiss = { addingPeople.value = false },
                        onConfirm = { picked ->
                            addingPeople.value = false
                            moveToGroupCall(state.chatId, state.peerName, state.type == CallType.VIDEO, picked)
                        },
                    )
                }
            }
        }
    }

    private val addingPeople = androidx.compose.runtime.mutableStateOf(false)

    /** The one-to-one call grows: a small group with the person we talk to and
     *  the friends picked, a group call in it (everyone rung), this call ends. */
    private fun moveToGroupCall(chatId: String, peerName: String, video: Boolean, picked: List<com.alphamessenger.calls.group.CallFriend>) {
        val ctx = applicationContext
        lifecycleScope.launch {
            val peer = runCatching { core.getPeerNpubForChat(chatId) }.getOrNull().orEmpty()
            val names = (listOf(peerName) + picked.map { it.name }).filter { it.isNotBlank() }.joinToString(", ")
            val title = getString(R.string.gcall_group_name, names).take(64)
            val group = runCatching {
                core.createGroupFull(title, (listOf(peer) + picked.map { it.npub }).filter { it.isNotBlank() }.distinct(), null)
            }.getOrNull()
            if (group == null) {
                Toast.makeText(ctx, getString(R.string.gcall_add_failed), Toast.LENGTH_LONG).show()
                return@launch
            }
            viewModel.hangup()
            com.alphamessenger.calls.group.GroupCalls.start(ctx, group.id, video, ringAgain = true)
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOutgoingIntent(intent)
    }

    /** A call refused in private mode: what to do once the reason is read. */
    private var torGate by androidx.compose.runtime.mutableStateOf<(() -> Unit)?>(null)

    private fun guardTor(onCancel: () -> Unit = {}, action: () -> Unit) {
        if (com.alphamessenger.calls.TorCallWarning.needed(this)) torGate = onCancel else action()
    }

    /** Outgoing mode: permissions first, then dial. */
    private fun handleOutgoingIntent(intent: Intent?) {
        val chatId = intent?.getStringExtra(EXTRA_OUTGOING_CHAT) ?: return
        val video = intent.getBooleanExtra(EXTRA_OUTGOING_VIDEO, false)
        // Consume the extras so recreation doesn't redial.
        intent.removeExtra(EXTRA_OUTGOING_CHAT)
        if (viewModel.state.value.phase != CallPhase.IDLE) return
        guardTor(onCancel = { finish() }) {
            withPermissions(video) {
                viewModel.startOutgoing(chatId, if (video) CallType.VIDEO else CallType.AUDIO)
            }
        }
    }

    /** Run [action] once mic (+camera for video) permissions are granted. */
    private fun withPermissions(video: Boolean, action: () -> Unit) {
        val needed = buildList {
            if (!hasPermission(android.Manifest.permission.RECORD_AUDIO)) {
                add(android.Manifest.permission.RECORD_AUDIO)
            }
            if (video && !hasPermission(android.Manifest.permission.CAMERA)) {
                add(android.Manifest.permission.CAMERA)
            }
        }
        if (needed.isEmpty()) {
            action()
        } else {
            pendingOnGranted = action
            pendingNeedsCamera = video
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun hasPermission(p: String): Boolean =
        checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Enter PiP when the user leaves during a video call. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPip()
    }

    /** The floating window for a connected video call. False when not possible. */
    private fun enterPip(): Boolean {
        val s = viewModel.state.value
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || s.type != CallType.VIDEO || s.phase != CallPhase.CONNECTED) return false
        return runCatching {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational(9, 16)).build(),
            )
        }.getOrDefault(false)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    /** The chat with this person, over the call screen's task. */
    private fun openChat(chatId: String) {
        if (chatId.isBlank()) return
        runCatching {
            startActivity(
                Intent(this, com.alphamessenger.ui.MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("chatId", chatId)
                },
            )
        }
        finish()
    }

    /** When the user unlocks the phone after accepting a call from the
     *  lockscreen notification, onResume fires — bring the call window to
     *  the front so the user sees the call screen (#2). */
    override fun onResume() {
        super.onResume()
        val s = viewModel.state.value
        // If the call is still active (not IDLE/terminal), make sure we're
        // visible — the Activity may have been created over the lockscreen
        // and is now behind the keyguard after unlock.
        if (s.phase != CallPhase.IDLE && !s.phase.isTerminal) {
            // Re-assert show-when-locked + turn-screen-on (harmless if already set).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            }
            // Remove the keyguard flag so the activity is visible after unlock.
            @Suppress("DEPRECATION")
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
    }

    @Deprecated("Back closes the call UI but keeps the call alive in the FGS.")
    override fun onBackPressed() {
        val s = viewModel.state.value
        if (s.phase == CallPhase.IDLE || s.phase.isTerminal) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
        // Otherwise: ignore — the call continues in the background service.
    }
}
