package com.alphamessenger.calls.ui

import androidx.lifecycle.ViewModel
import com.alphamessenger.calls.manager.CallManager
import com.alphamessenger.calls.CallUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Thin ViewModel — exposes the [CallManager]'s [CallUiState] flow + delegates
 *  every UI action straight to the manager. Keeps the UI free of Hilt deps. */
@HiltViewModel
class CallViewModel @Inject constructor(
    private val manager: CallManager,
) : ViewModel() {
    val state: kotlinx.coroutines.flow.StateFlow<CallUiState> = manager.uiState

    fun startOutgoing(chatId: String, type: com.alphamessenger.calls.CallType) =
        manager.startOutgoing(chatId, type)

    fun accept() = manager.acceptIncoming()
    fun reject() = manager.rejectIncoming()
    fun cancel() = manager.cancelOutgoing()
    fun hangup() = manager.hangup()
    fun toggleMute() = manager.toggleMute()
    fun setRoute(route: com.alphamessenger.calls.AudioRoute) = manager.setRoute(route)
    fun toggleVideo() = manager.toggleVideo()
    fun switchCamera() = manager.switchCamera()
    fun toggleHold() = manager.toggleHold()
    fun rejectWithMessage(text: String) = manager.rejectWithMessage(text)
    fun remindLater() = manager.remindLater()
    fun redial() = manager.redial()
    fun acceptVideoRequest() = manager.acceptVideoRequest()
    fun declineVideoRequest() = manager.declineVideoRequest()
}
