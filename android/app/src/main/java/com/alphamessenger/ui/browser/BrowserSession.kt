package com.alphamessenger.ui.browser

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.icons.AlphaIcons
import com.alphamessenger.ui.theme.AlphaTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The browser while it is folded away: the messenger shows it as a bar to come back to. */
object BrowserSession {
    data class Folded(val title: String, val host: String, val icon: Bitmap?, val tabs: Int)

    private val _folded = MutableStateFlow<Folded?>(null)
    val folded: StateFlow<Folded?> = _folded.asStateFlow()

    @Volatile internal var activity: java.lang.ref.WeakReference<BrowserActivity>? = null

    internal fun fold(f: Folded) { _folded.value = f }
    internal fun unfold() { _folded.value = null }

    fun expand(context: Context) {
        context.startActivity(Intent(context, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun close() {
        activity?.get()?.finish()
        _folded.value = null
    }
}

/**
 * Whether the folded bar takes the bottom of the screen now: not while the keyboard is
 * open, or the screens above would keep a bar-high gap between their input and the keyboard.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun foldedBarShown(): Boolean {
    val folded by BrowserSession.folded.collectAsState()
    return folded != null && !WindowInsets.isImeVisible
}

/** The folded browser at the bottom of the messenger; the screens above make room for it. */
@Composable
fun FoldedBrowserBar() {
    val folded by BrowserSession.folded.collectAsState()
    val f = folded ?: return
    if (!foldedBarShown()) return
    val cs = AlphaTheme.colorScheme
    val ctx = LocalContext.current
    // One sheet down to the screen's edge: its colour runs under the system navigation,
    // so nothing else shows below it whatever kind of navigation the phone uses.
    val top = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
    // Three-button navigation gets a see-through scrim from the system, which would make
    // the strip under the buttons a different colour from the sheet: off while it shows.
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        val before = if (android.os.Build.VERSION.SDK_INT >= 29) window?.isNavigationBarContrastEnforced else null
        if (android.os.Build.VERSION.SDK_INT >= 29) window?.isNavigationBarContrastEnforced = false
        onDispose { if (android.os.Build.VERSION.SDK_INT >= 29 && before != null) window?.isNavigationBarContrastEnforced = before }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().shadow(6.dp, top).clip(top).background(cs.bgSecondary)
                .clickable { BrowserSession.expand(ctx) }
                .navigationBarsPadding().height(54.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                AlphaIcons.Outlined.Close, stringResource(R.string.browser_close), tint = cs.textMuted,
                modifier = Modifier.padding(start = 4.dp).size(42.dp).clip(CircleShape).clickable { BrowserSession.close() }.padding(11.dp),
            )
            f.icon?.let { Image(it.asImageBitmap(), null, Modifier.size(18.dp).clip(RoundedCornerShape(4.dp))) }
                ?: Icon(AlphaIcons.Outlined.Public, null, tint = cs.textMuted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(f.title.ifBlank { f.host }, color = cs.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(f.host.takeIf { f.title.isNotBlank() && it.isNotBlank() }, if (f.tabs > 1) stringResource(R.string.browser_tabs_title, f.tabs) else null)
                if (sub.isNotEmpty()) Text(sub.joinToString(" · "), color = cs.textMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(
                AlphaIcons.Outlined.KeyboardArrowUp, stringResource(R.string.browser_expand), tint = cs.textPrimary,
                modifier = Modifier.padding(end = 4.dp).size(42.dp).padding(9.dp),
            )
        }
    }
}
