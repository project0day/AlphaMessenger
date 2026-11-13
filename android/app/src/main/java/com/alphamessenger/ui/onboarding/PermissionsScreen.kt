package com.alphamessenger.ui.onboarding

import com.alphamessenger.ui.icons.AlphaIcons
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.alphamessenger.R
import com.alphamessenger.ui.theme.AlphaTheme
import com.alphamessenger.util.BatteryOptimization

/**
 * Step 5 of 5: what delivery without Google push needs — notifications and
 * running in the background. Microphone and camera are asked at the first
 * call, media access when attaching: never up front.
 */
@Composable
fun PermissionsScreen(
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val cs = AlphaTheme.colorScheme

    fun notificationsGranted() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    var notifications by remember { mutableStateOf(notificationsGranted()) }
    var background by remember { mutableStateOf(BatteryOptimization.isExempt(ctx)) }

    // Both can change in system screens: re-read on every return.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                notifications = notificationsGranted()
                background = BatteryOptimization.isExempt(ctx)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notifications = granted
    }

    OnbScreen(
        title = stringResource(R.string.onb_perm_title),
        subtitle = stringResource(R.string.onb_perm_sub),
        step = 5,
        onBack = onBack,
        bottom = { OnbPrimaryButton(stringResource(R.string.common_continue), onContinue) },
    ) {
        PermRow(
            icon = AlphaIcons.Outlined.NotificationsNone,
            title = stringResource(R.string.onb_perm_notif),
            granted = notifications,
            note = null,
            onAllow = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
        )
        PermRow(
            icon = AlphaIcons.Outlined.BatteryChargingFull,
            title = stringResource(R.string.onb_perm_bg),
            granted = background,
            note = stringResource(R.string.onb_perm_bg_desc),
            onAllow = { BatteryOptimization.request(ctx) },
        )
        OnbCardRow(
            icon = AlphaIcons.Outlined.MicNone,
            title = stringResource(R.string.onb_perm_media),
            note = stringResource(R.string.onb_perm_media_desc),
            iconTint = cs.textSecondary,
        )
        Text(stringResource(R.string.onb_perm_note), color = cs.textMuted, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun PermRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    granted: Boolean,
    note: String?,
    onAllow: () -> Unit,
) {
    val cs = AlphaTheme.colorScheme
    OnbCardRow(
        icon = if (granted) AlphaIcons.Outlined.Check else icon,
        title = title,
        note = if (granted) stringResource(R.string.onb_perm_granted) else note,
        iconTint = if (granted) cs.success else cs.accent,
        iconBackground = if (granted) cs.success.copy(alpha = 0.14f) else cs.accent.copy(alpha = 0.12f),
        trailing = if (granted) null else ({ OnbPillButton(stringResource(R.string.onb_perm_allow), onAllow) }),
    )
}
