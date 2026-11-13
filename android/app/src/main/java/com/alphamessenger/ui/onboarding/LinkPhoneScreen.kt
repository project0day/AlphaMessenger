package com.alphamessenger.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alphamessenger.R
import com.alphamessenger.data.AlphaCoreRepository
import com.alphamessenger.ui.theme.AlphaTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/** This phone joins an account that already lives on another phone. */
object LinkFlow {
    /** Linking new phones is frozen: what is linked can still be unlinked. */
    const val LINKING_ENABLED = false

    /** The account came from another phone: its profile is set already. */
    @Volatile var active: Boolean = false
}

@HiltViewModel
class LinkPhoneViewModel @Inject constructor(
    private val repository: AlphaCoreRepository,
) : ViewModel() {

    data class State(
        val qr: String = "",
        val offerName: String? = null,
        val offerNpub: String = "",
        val busy: Boolean = false,
        val linked: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> get() = _state

    init {
        viewModelScope.launch {
            val qr = runCatching { repository.linkBegin() }.getOrDefault("")
            _state.value = _state.value.copy(qr = qr)
            // The linked phone answers within seconds of the scan; a slow relay may take longer.
            while (isActive && _state.value.offerName == null) {
                delay(3_000)
                val v = runCatching { org.json.JSONObject(repository.linkPoll()) }.getOrNull() ?: continue
                if (v.optString("state") == "offer") {
                    _state.value = _state.value.copy(offerName = v.optString("name"), offerNpub = v.optString("npub"))
                }
            }
        }
    }

    fun accept() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            runCatching { repository.linkAccept() }
                .onSuccess {
                    LinkFlow.active = true
                    _state.value = _state.value.copy(busy = false, linked = true)
                }
                .onFailure { _state.value = _state.value.copy(busy = false, error = it.message ?: it.javaClass.simpleName) }
        }
    }

    /** Not this account: wait for another scan. */
    fun decline() {
        _state.value = _state.value.copy(offerName = null, offerNpub = "")
    }
}

@Composable
fun LinkPhoneScreen(viewModel: LinkPhoneViewModel, onBack: () -> Unit, onLinked: () -> Unit) {
    val cs = AlphaTheme.colorScheme
    val state by viewModel.state.collectAsState()
    SecureScreenEffect()
    LaunchedEffect(state.linked) { if (state.linked) onLinked() }
    val offer = state.offerName

    OnbScreen(
        title = stringResource(R.string.link_title),
        subtitle = if (offer == null) stringResource(R.string.link_hint) else null,
        onBack = onBack,
        bottom = if (offer != null) ({
            OnbPrimaryButton(stringResource(R.string.link_accept), onClick = viewModel::accept, loading = state.busy)
            OnbTextButton(stringResource(R.string.common_cancel), onClick = viewModel::decline, modifier = Modifier.fillMaxWidth())
        }) else null,
    ) {
        if (offer == null) {
            Spacer(Modifier.height(24.dp))
            val bitmap = remember(state.qr) {
                state.qr.takeIf { it.isNotEmpty() }?.let { com.alphamessenger.ui.components.QRCodeGenerator.generateBitmap(it, 720) }
            }
            Box(
                Modifier.align(Alignment.CenterHorizontally).size(260.dp).clip(RoundedCornerShape(24.dp)).background(Color.White).padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxWidth())
                else CircularProgressIndicator(color = cs.accent)
            }
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.link_waiting), color = cs.textMuted, fontSize = 14.sp,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.link_offer_title, offer.ifBlank { "…" }), color = cs.textPrimary, fontSize = 22.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.link_offer_text, state.offerNpub.take(16) + "…"),
                color = cs.textSecondary, fontSize = 15.sp, lineHeight = 21.sp,
            )
        }
        state.error?.let {
            Spacer(Modifier.height(16.dp))
            OnbCallout(stringResource(R.string.link_error, it), kind = CalloutKind.Error)
        }
    }
}
