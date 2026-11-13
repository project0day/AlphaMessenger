package com.alphamessenger.ui.chat.interaction

import androidx.compose.runtime.Stable
import com.alphamessenger.ui.chat.MusicPlaybackController
import com.alphamessenger.ui.chat.VoicePlaybackController
import kotlinx.coroutines.flow.SharedFlow

/**
 * The ONE object a message bubble receives instead of ~15 separate callbacks +
 * render params (`V2ConversationContext`). Built once per ChatScreen
 * composition and handed to every bubble.
 *
 * Split of concerns:
 *  • [onEvent] — the single interaction channel → [MessageInteractionController].
 *  • selection/highlight readers — live per-message render-state (lambdas so a
 *    bubble reading them subscribes to the controller's Compose state directly).
 *  • media providers — unchanged render dependencies, just grouped here.
 *
 * A bubble never learns who handles its events, nor holds ChatScreen state.
 */
@Stable
class MessageInteractionScope(
    val onEvent: (MessageEvent) -> Unit,
    val isSelected: (String) -> Boolean,
    val isHighlighted: (String) -> Boolean,
    val selectionActive: () -> Boolean,
    // ── Render providers (same as before; not interaction) ────────
    val mediaLoader: (suspend (String) -> String?)? = null,
    val cachedMediaPath: ((String) -> String?)? = null,
    val mediaActions: com.alphamessenger.transfer.TransferUi? = null,
    val mediaProgress: SharedFlow<Triple<String, Long, Long>>? = null,
    val voiceController: VoicePlaybackController? = null,
    val musicController: MusicPlaybackController? = null,
    val repository: com.alphamessenger.data.AlphaCoreRepository? = null,
)
