package com.alphamessenger.ui.chat

import com.alphamessenger.ui.icons.AlphaIcons
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.alphamessenger.R
import com.alphamessenger.ui.theme.AlphaColorScheme
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.tan


// --- Maps: MapLibre + OpenFreeMap (see ui/chat/map/AlphaMap.kt) ---

/** Static map thumbnail (rendered snapshot, no live map). With [load] = false the
 *  tile server is never contacted and a "tap to load" placeholder is shown. */
@Composable
fun StaticMapImage(
    lat: Double,
    lng: Double,
    widthDp: androidx.compose.ui.unit.Dp,
    heightDp: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    pinColor: Color = Color(0xFFEB5757),
    load: Boolean = true,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val dark = com.alphamessenger.ui.theme.AlphaTheme.colorScheme.bgPrimary.luminance() < 0.5f
    var bmp by remember(lat, lng, widthDp, heightDp, dark) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(lat, lng, widthDp, heightDp, dark, load) {
        if (load && bmp == null) {
            bmp = com.alphamessenger.ui.chat.map.MapSnapshots
                .get(ctx, lat, lng, widthDp.value.toInt(), heightDp.value.toInt(), density, dark)
                ?.asImageBitmap()
        }
    }
    Box(
        modifier = modifier.size(widthDp, heightDp).clipToBounds()
            .background(if (dark) Color(0xFF1E2228) else Color(0xFFE8E5DD)),
        contentAlignment = Alignment.Center,
    ) {
        bmp?.let {
            androidx.compose.foundation.Image(
                bitmap = it, contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Icon(
            AlphaIcons.Filled.LocationOn, contentDescription = null, tint = pinColor,
            modifier = Modifier.offset(y = (-14).dp).size(34.dp),
        )
        if (bmp == null && !load) {
            Text(
                stringResource(R.string.location_tap_to_load),
                color = if (dark) Color(0xFFB0B4BA) else Color(0xFF555555), fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
            )
        }
    }
}

/** Live map for the media viewer pager. */
@Composable
fun OsmMap(
    lat: Double,
    lng: Double,
    modifier: Modifier = Modifier,
) {
    val dark = com.alphamessenger.ui.theme.AlphaTheme.colorScheme.bgPrimary.luminance() < 0.5f
    com.alphamessenger.ui.chat.map.AlphaMapView(lat = lat, lng = lng, dark = dark, modifier = modifier)
}

// ══════════════════════════════════════════════════════════════════════
// Location bubble card
// ══════════════════════════════════════════════════════════════════════

/** Parsed location payload from the media_ref JSON. */
data class LocationData(val lat: Double, val lng: Double, val name: String, val address: String)

fun parseLocation(refJson: String?): LocationData? {
    if (refJson == null) return null
    return runCatching {
        val o = org.json.JSONObject(refJson)
        if (!o.optBoolean("location", false)) return null
        LocationData(
            lat = o.getDouble("lat"),
            lng = o.getDouble("lng"),
            name = o.optString("name"),
            address = o.optString("address"),
        )
    }.getOrNull()
}

/** Map + place name content (no bubble chrome — the caller wraps it in a
 *  standard message bubble with time + status ticks). */
@Composable
fun LocationCard(
    data: LocationData,
    colors: AlphaColorScheme,
    textColor: Color,
    onOpen: () -> Unit,
    /** Incoming locations don't contact the map server until the user taps:
     *  the tile requests would reveal our IP and what place we look at. */
    autoLoadMap: Boolean = true,
    mapKey: String = "",
) {
    var mapAllowed by androidx.compose.runtime.saveable.rememberSaveable(mapKey) { mutableStateOf(autoLoadMap) }
    Column(
        modifier = Modifier
            .width(244.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { if (mapAllowed) onOpen() else mapAllowed = true },
    ) {
        StaticMapImage(
            lat = data.lat, lng = data.lng,
            widthDp = 244.dp, heightDp = 150.dp,
            load = mapAllowed,
        )
        Row(
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AlphaIcons.Filled.LocationOn, null, tint = colors.accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Column {
                Text(
                    data.name.ifBlank { stringResource(R.string.location_fallback) },
                    color = textColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                )
                if (data.address.isNotBlank()) {
                    Text(data.address, color = textColor.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 2)
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// Fullscreen location view + navigation chooser
// ══════════════════════════════════════════════════════════════════════

@Composable
private fun LocActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    colors: AlphaColorScheme,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.accent)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Pick any point to send: a full-screen map with a fixed centre pin. The map
 * centre is the chosen place; the address under it updates as you move.
 */
@Composable
fun LocationPickerScreen(
    startLat: Double,
    startLng: Double,
    colors: AlphaColorScheme,
    onDismiss: () -> Unit,
    onPick: (lat: Double, lng: Double, name: String, address: String) -> Unit,
) {
    val ctx = LocalContext.current
    val addressHint = stringResource(R.string.location_move_map)
    var centerLat by remember { mutableStateOf(startLat) }
    var centerLng by remember { mutableStateOf(startLng) }
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf(addressHint) }
    val dark = colors.bgPrimary.luminance() < 0.5f
    val controller = remember { com.alphamessenger.ui.chat.map.AlphaMapController() }

    // Opened at once at the remembered place; the real fix moves the map when it comes,
    // unless the map was moved by hand meanwhile.
    LaunchedEffect(Unit) {
        val fix = runCatching { currentFix(ctx) }.getOrNull() ?: return@LaunchedEffect
        val untouched = kotlin.math.abs(centerLat - startLat) < 1e-4 && kotlin.math.abs(centerLng - startLng) < 1e-4
        if (untouched) {
            controller.moveTo(fix.latitude, fix.longitude, 16.5)
            centerLat = fix.latitude; centerLng = fix.longitude
        }
    }

    // Debounced reverse-geocode of the current centre.
    LaunchedEffect(centerLat, centerLng) {
        kotlinx.coroutines.delay(400)
        val (n, a) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            reverseGeocode(ctx, centerLat, centerLng)
        }
        name = n; address = a.ifBlank { "%.5f, %.5f".format(centerLat, centerLng) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = true, decorFitsSystemWindows = false),
    ) {
        com.alphamessenger.ui.chat.map.FullscreenDialogWindow(darkIcons = !dark)
        Box(Modifier.fillMaxSize().background(colors.bgPrimary)) {
            com.alphamessenger.ui.chat.map.AlphaMapView(
                lat = startLat, lng = startLng, dark = dark, zoom = 16.0, showMarker = false,
                controller = controller,
                onCameraIdle = { la, ln -> centerLat = la; centerLng = ln },
                modifier = Modifier.fillMaxSize(),
            )

            // Fixed centre pin (tip at exact centre).
            Icon(
                AlphaIcons.Filled.LocationOn, contentDescription = null, tint = Color(0xFFEB5757),
                modifier = Modifier.align(Alignment.Center).offset(y = (-17).dp).size(44.dp),
            )

            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp)) {
                com.alphamessenger.ui.chat.map.MapRoundButton(
                    AlphaIcons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), onClick = onDismiss,
                )
            }

            val pickerScope = androidx.compose.runtime.rememberCoroutineScope()
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp),
                horizontalAlignment = Alignment.End,
            ) {
                com.alphamessenger.ui.chat.map.MapRoundButton(AlphaIcons.Filled.MyLocation, stringResource(R.string.location_my)) {
                    pickerScope.launch {
                        val loc = runCatching { fetchDeviceLocation(ctx) }.getOrNull()
                        if (loc != null) {
                            controller.moveTo(loc.lat, loc.lng, 16.5)
                            centerLat = loc.lat; centerLng = loc.lng
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.bgSecondary).padding(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(AlphaIcons.Filled.LocationOn, null, tint = colors.accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                name.ifBlank { stringResource(R.string.location_selected_point) },
                                color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            )
                            Text(address, color = colors.textSecondary, fontSize = 12.sp, maxLines = 2)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp)).background(colors.accent)
                            .clickable { onPick(centerLat, centerLng, name, address) }
                            .padding(vertical = 13.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AlphaIcons.AutoMirrored.Filled.Send, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.location_send_this), color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** Confirmation sheet before sending: mini-map + place name + Send / Cancel. */
@Composable
fun LocationConfirmSheet(
    data: LocationData,
    colors: AlphaColorScheme,
    onDismiss: () -> Unit,
    onSend: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.bgSecondary),
        ) {
            val cfgW = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
            StaticMapImage(lat = data.lat, lng = data.lng, widthDp = cfgW - 40.dp, heightDp = 220.dp)
            Column(Modifier.padding(16.dp)) {
                Text(
                    data.name.ifBlank { stringResource(R.string.location_your) },
                    color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                )
                if (data.address.isNotBlank()) {
                    Text(data.address, color = colors.textSecondary, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 2.dp))
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.bgTertiary)
                            .clickable(onClick = onDismiss)
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { Text(stringResource(R.string.common_cancel), color = colors.textSecondary, fontWeight = FontWeight.SemiBold) }
                    Row(
                        modifier = Modifier.weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.accent)
                            .clickable(onClick = onSend)
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AlphaIcons.Filled.LocationOn, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.common_send), color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** Open the system navigator chooser (Google Maps / Waze / Organic / 2GIS …). */
fun openNavigation(ctx: android.content.Context, lat: Double, lng: Double, name: String) {
    val label = Uri.encode(name.ifBlank { ctx.getString(R.string.location_fallback) })
    val geo = Uri.parse("geo:$lat,$lng?q=$lat,$lng($label)")
    val intent = Intent(Intent.ACTION_VIEW, geo)
    val chooser = Intent.createChooser(intent, ctx.getString(R.string.chat_open_with))
    runCatching { ctx.startActivity(chooser) }.onFailure {
        // Fallback to a universal maps URL if no geo: handler exists.
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/?mlat=$lat&mlon=$lng#map=16/$lat/$lng")))
        }
    }
}

/** Best-effort current location + reverse-geocoded name/address. Caller must
 *  have already checked the location permission. */
suspend fun fetchDeviceLocation(ctx: android.content.Context): LocationData? {
    val l = currentFix(ctx) ?: return null
    val (name, address) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        reverseGeocode(ctx, l.latitude, l.longitude)
    }
    return LocationData(l.latitude, l.longitude, name, address)
}

private fun locationProviders(lm: android.location.LocationManager): List<String> = buildList {
    if (android.os.Build.VERSION.SDK_INT >= 31) add(android.location.LocationManager.FUSED_PROVIDER)
    add(android.location.LocationManager.GPS_PROVIDER)
    add(android.location.LocationManager.NETWORK_PROVIDER)
}.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

private fun ageMs(l: android.location.Location): Long =
    (android.os.SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000

/** The phone's last remembered position, however old: only a place to open the map at. */
@android.annotation.SuppressLint("MissingPermission")
fun lastKnownFix(ctx: android.content.Context, maxAgeMs: Long = Long.MAX_VALUE): android.location.Location? {
    val lm = ctx.getSystemService(android.content.Context.LOCATION_SERVICE)
        as? android.location.LocationManager ?: return null
    return (locationProviders(lm) + android.location.LocationManager.PASSIVE_PROVIDER)
        .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .filter { ageMs(it) <= maxAgeMs }
        .minByOrNull { ageMs(it) }
}

/**
 * Where the phone is now. A remembered fix counts only while fresh — an old one,
 * however accurate, can be from a place far away. Otherwise every provider is asked
 * at once: indoors satellites may never answer while the network does in a second.
 */
@android.annotation.SuppressLint("MissingPermission")
suspend fun currentFix(ctx: android.content.Context): android.location.Location? {
    val lm = ctx.getSystemService(android.content.Context.LOCATION_SERVICE)
        as? android.location.LocationManager ?: return null
    lastKnownFix(ctx, FRESH_FIX_MS)?.let { return it }
    val providers = locationProviders(lm)
    if (providers.isEmpty()) return lastKnownFix(ctx, USABLE_FIX_MS)
    // Each provider answers once; an empty list is "no fix" (null is the timeout's).
    val fixes = kotlinx.coroutines.channels.Channel<List<android.location.Location>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    val best = kotlinx.coroutines.coroutineScope {
        val asks = providers.map { p -> launch { fixes.send(listOfNotNull(oneFix(ctx, lm, p))) } }
        var best: android.location.Location? = null
        var deadline = android.os.SystemClock.elapsedRealtime() + FIX_TIMEOUT_MS
        var answered = 0
        while (answered < providers.size) {
            val left = deadline - android.os.SystemClock.elapsedRealtime()
            if (left <= 0) break
            val answer = kotlinx.coroutines.withTimeoutOrNull(left) { fixes.receive() } ?: break
            answered++
            val f = answer.firstOrNull() ?: continue
            if (best == null || f.accuracy < best!!.accuracy) best = f
            if (f.accuracy <= GOOD_FIX_M) break
            // A rough answer is in: a short while longer for a sharper one.
            deadline = minOf(deadline, android.os.SystemClock.elapsedRealtime() + REFINE_MS)
        }
        asks.forEach { it.cancel() }
        best
    }
    return best ?: lastKnownFix(ctx, USABLE_FIX_MS)
}

@android.annotation.SuppressLint("MissingPermission")
private suspend fun oneFix(
    ctx: android.content.Context,
    lm: android.location.LocationManager,
    provider: String,
): android.location.Location? = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        val signal = android.os.CancellationSignal()
        cont.invokeOnCancellation { runCatching { signal.cancel() } }
        runCatching {
            lm.getCurrentLocation(provider, signal, androidx.core.content.ContextCompat.getMainExecutor(ctx)) { l ->
                if (cont.isActive) cont.resume(l) {}
            }
        }.onFailure { if (cont.isActive) cont.resume(null) {} }
    } else {
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(l: android.location.Location) {
                runCatching { lm.removeUpdates(this) }
                if (cont.isActive) cont.resume(l) {}
            }
            @Deprecated("") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) { if (cont.isActive) cont.resume(null) {} }
        }
        runCatching {
            lm.requestLocationUpdates(provider, 0L, 0f, listener, android.os.Looper.getMainLooper())
        }.onFailure { if (cont.isActive) cont.resume(null) {} }
        cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
    }
}

/** A remembered fix this young is where the phone still is. */
private const val FRESH_FIX_MS = 2 * 60_000L
/** Nothing fresh answered: an older fix still beats none, up to this age. */
private const val USABLE_FIX_MS = 15 * 60_000L
private const val FIX_TIMEOUT_MS = 10_000L
private const val REFINE_MS = 2_000L
private const val GOOD_FIX_M = 50f

private fun reverseGeocode(ctx: android.content.Context, lat: Double, lng: Double): Pair<String, String> {
    // No Geocoder: on most phones it sends the coordinates to Google. The
    // location is described by its coordinates only.
    return ctx.getString(R.string.location_fallback) to "%.5f, %.5f".format(java.util.Locale.US, lat, lng)
}

fun shareLocation(ctx: android.content.Context, lat: Double, lng: Double, name: String) {
    val url = "https://www.openstreetmap.org/?mlat=$lat&mlon=$lng#map=16/$lat/$lng"
    val text = if (name.isBlank()) url else "$name\n$url"
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching { ctx.startActivity(Intent.createChooser(intent, ctx.getString(R.string.common_share))) }
}
