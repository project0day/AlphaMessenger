package com.alphamessenger.ui.background

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.alphamessenger.R
import com.alphamessenger.ui.components.AlphaSheet
import com.alphamessenger.ui.icons.AlphaIcons
import com.alphamessenger.ui.onboarding.OnbCardRow
import com.alphamessenger.ui.onboarding.OnbPillButton
import com.alphamessenger.ui.theme.AlphaTheme
import com.alphamessenger.util.BatteryOptimization

/**
 * What keeps messages arriving while the app is in the background. There are no
 * Google push messages: the app keeps its own connection, and some phones stop it.
 * On those (Xiaomi, Redmi, POCO) the sheet shows once, step by step, each step
 * opening the very setting it names; it stays reachable from Settings.
 */
object BackgroundDelivery {
    private const val PREFS = "background_delivery"
    private const val KEY_SHOWN = "guide_shown_v"
    /** Raised when the guide changes enough to be worth showing again. */
    private const val GUIDE_VERSION = 1

    private const val VENDOR_SECURITY = "com.miui.securitycenter"

    /**
     * A phone whose system stops background apps unless the user exempts them: the
     * vendor's own system, told by its security app, not by the maker (the same
     * hardware may run another system without these settings).
     */
    fun stopsBackgroundApps(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(VENDOR_SECURITY, 0); true }.getOrDefault(false)

    fun shouldShowOnce(context: Context): Boolean =
        stopsBackgroundApps(context) &&
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SHOWN, 0) < GUIDE_VERSION

    fun markShown(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_SHOWN, GUIDE_VERSION).apply()
    }

    fun notificationsAllowed(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun openNotificationSettings(context: Context) {
        open(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }

    /** The system's autostart list; this app's page when the system has no such list. */
    fun openAutostart(context: Context) {
        val autostart = Intent().setComponent(
            ComponentName(VENDOR_SECURITY, "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        )
        if (!open(context, autostart)) openAppDetails(context)
    }

    /** The per-app battery saver ("No restrictions"); this app's page when it cannot be opened. */
    fun openBatterySaver(context: Context) {
        val saver = Intent()
            .setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
            .putExtra("package_name", context.packageName)
            .putExtra("package_label", context.getString(R.string.app_name))
        if (!open(context, saver)) openAppDetails(context)
    }

    private fun openAppDetails(context: Context) {
        open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }

    private fun open(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}

/** Shows the guide once on a phone that stops background apps. */
@Composable
fun BackgroundDeliveryOnce() {
    val ctx = LocalContext.current
    var show by remember { mutableStateOf(BackgroundDelivery.shouldShowOnce(ctx)) }
    if (show) {
        BackgroundDeliverySheet(onDismiss = {
            BackgroundDelivery.markShown(ctx)
            show = false
        })
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun BackgroundDeliverySheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val cs = AlphaTheme.colorScheme
    var notifications by remember { mutableStateOf(BackgroundDelivery.notificationsAllowed(ctx)) }
    var battery by remember { mutableStateOf(BatteryOptimization.isExempt(ctx)) }
    // Each step is taken in a system screen: what can be read is read again on return.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                notifications = BackgroundDelivery.notificationsAllowed(ctx)
                battery = BatteryOptimization.isExempt(ctx)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) notifications = BackgroundDelivery.notificationsAllowed(ctx) else BackgroundDelivery.openNotificationSettings(ctx)
    }
    val stopsApps = remember { BackgroundDelivery.stopsBackgroundApps(ctx) }

    AlphaSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.bg_delivery_title), color = cs.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(if (stopsApps) R.string.bg_delivery_intro_device else R.string.bg_delivery_intro),
                color = cs.textSecondary, fontSize = 14.sp, lineHeight = 20.sp,
            )
            Step(
                icon = AlphaIcons.Outlined.NotificationsNone,
                title = stringResource(R.string.bg_delivery_notifications),
                note = null,
                done = notifications,
                action = stringResource(R.string.onb_perm_allow),
                onAction = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else BackgroundDelivery.openNotificationSettings(ctx)
                },
            )
            Step(
                icon = AlphaIcons.Outlined.BatteryChargingFull,
                title = stringResource(R.string.bg_delivery_battery),
                note = stringResource(R.string.bg_delivery_battery_note),
                done = battery,
                action = stringResource(R.string.onb_perm_allow),
                onAction = { BatteryOptimization.request(ctx) },
            )
            if (stopsApps) {
                Step(
                    icon = AlphaIcons.Outlined.PlayCircle,
                    title = stringResource(R.string.bg_delivery_autostart),
                    note = stringResource(R.string.bg_delivery_autostart_note),
                    done = false,
                    action = stringResource(R.string.bg_delivery_open),
                    onAction = { BackgroundDelivery.openAutostart(ctx) },
                )
                Step(
                    icon = AlphaIcons.Outlined.Shield,
                    title = stringResource(R.string.bg_delivery_saver),
                    note = stringResource(R.string.bg_delivery_saver_note),
                    done = false,
                    action = stringResource(R.string.bg_delivery_open),
                    onAction = { BackgroundDelivery.openBatterySaver(ctx) },
                )
                Step(
                    icon = AlphaIcons.Outlined.Lock,
                    title = stringResource(R.string.bg_delivery_lock),
                    note = stringResource(R.string.bg_delivery_lock_note),
                    done = false,
                    action = null,
                    onAction = {},
                )
            }
            OnbPillButton(stringResource(R.string.bg_delivery_done), onDismiss)
        }
    }
}

@Composable
private fun Step(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    note: String?,
    done: Boolean,
    action: String?,
    onAction: () -> Unit,
) {
    val cs = AlphaTheme.colorScheme
    OnbCardRow(
        icon = if (done) AlphaIcons.Outlined.Check else icon,
        title = title,
        note = if (done) stringResource(R.string.onb_perm_granted) else note,
        iconTint = if (done) cs.success else cs.accent,
        iconBackground = if (done) cs.success.copy(alpha = 0.14f) else cs.accent.copy(alpha = 0.12f),
        trailing = if (done || action == null) null else ({ OnbPillButton(action, onAction) }),
    )
}
