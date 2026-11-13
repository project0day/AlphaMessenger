package com.alphamessenger.ui.components

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.alphamessenger.R
import com.alphamessenger.data.EmbeddedTor
import com.alphamessenger.ui.theme.AlphaTheme
import kotlinx.coroutines.launch

/** Whether the Tor quick button sits on the chat list. On by default. */
object TorButtonPref {
    private const val PREFS = "ui_prefs"
    private const val KEY = "tor_button"
    var visible by mutableStateOf(true)
        private set
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        visible = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)
    }

    fun set(ctx: Context, value: Boolean) {
        visible = value
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
    }
}

/** An onion: bulb with inner layers and a sprout. */
@Composable
fun OnionIcon(color: Color, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = w * 0.085f
        fun bulb(inset: Float): Path = Path().apply {
            val top = h * 0.22f + inset * 0.55f
            val bottom = h * 0.94f - inset * 0.25f
            val half = w * 0.40f - inset
            val cx = w / 2f
            moveTo(cx, top)
            cubicTo(cx + half * 0.25f, top + h * 0.12f, cx + half, h * 0.45f, cx + half, h * 0.64f)
            cubicTo(cx + half, bottom - h * 0.02f, cx + half * 0.45f, bottom, cx, bottom)
            cubicTo(cx - half * 0.45f, bottom, cx - half, bottom - h * 0.02f, cx - half, h * 0.64f)
            cubicTo(cx - half, h * 0.45f, cx - half * 0.25f, top + h * 0.12f, cx, top)
            close()
        }
        drawPath(bulb(0f), color, style = Stroke(stroke))
        drawPath(bulb(w * 0.13f), color, style = Stroke(stroke * 0.8f))
        drawPath(bulb(w * 0.25f), color, style = Stroke(stroke * 0.7f))
        drawLine(color, Offset(w / 2f, h * 0.22f), Offset(w / 2f, h * 0.06f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w / 2f, h * 0.12f), Offset(w * 0.66f, h * 0.04f), stroke * 0.8f, StrokeCap.Round)
    }
}

/** Private mode as the current process runs it: on, connecting, off. */
@Composable
fun torIndicatorColor(): Color {
    val colors = AlphaTheme.colorScheme
    val state by EmbeddedTor.state.collectAsState()
    val on by EmbeddedTor.enabled.collectAsState()
    return when {
        !on -> colors.danger
        state == EmbeddedTor.State.ON -> colors.success
        else -> colors.warning
    }
}

/** Tap → confirm → private mode switches at once (the core's net switch reroutes live). */
@Composable
fun TorQuickButton(setCore: suspend (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val tint = torIndicatorColor()
    var confirm by remember { mutableStateOf(false) }
    val label = stringResource(R.string.tor_quick_cd)
    IconButton(onClick = { confirm = true }, modifier = modifier.semantics { contentDescription = label }) {
        OnionIcon(tint, 24.dp)
    }
    if (confirm) {
        TorToggleDialog(
            enable = !EmbeddedTor.enabled.value,
            setCore = setCore,
            onDismiss = { confirm = false },
        )
    }
}

/** Asks before switching private mode; on confirm switches it. */
@Composable
fun TorToggleDialog(enable: Boolean, setCore: suspend (Boolean) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val colors = AlphaTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.bgSecondary,
        title = { Text(stringResource(if (enable) R.string.tor_quick_on_title else R.string.tor_quick_off_title), color = colors.textPrimary) },
        text = { Text(stringResource(R.string.tor_quick_body), color = colors.textSecondary) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                // Not this dialog's scope: it is gone the moment the dialog closes.
                EmbeddedTor.scope.launch { runCatching { applyTorSetting(ctx, enable, setCore) } }
            }) { Text(stringResource(if (enable) R.string.tor_quick_enable else R.string.tor_quick_disable), color = colors.accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = colors.textSecondary) }
        },
    )
}

/** One place that switches private mode: core setting, Kotlin mirror, the Tor itself. */
suspend fun applyTorSetting(ctx: Context, enabled: Boolean, setCore: suspend (Boolean) -> Unit) {
    setCore(enabled)
    com.alphamessenger.data.NetworkPrivacyPrefs(ctx.applicationContext).torEnabled = enabled
    if (enabled) EmbeddedTor.start(ctx) else EmbeddedTor.stop(ctx)
}
