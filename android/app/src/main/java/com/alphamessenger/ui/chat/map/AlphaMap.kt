package com.alphamessenger.ui.chat.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import java.io.File
import kotlin.coroutines.resume

/**
 * Maps: MapLibre (open-source vector renderer) + OpenFreeMap styles — free, no
 * API key, no account, no usage limits. Tiles are requested only when a map is
 * actually shown (incoming locations wait for a tap), and go through Tor when
 * private mode is on.
 */
object AlphaMapStyle {
    private const val LIGHT = "https://tiles.openfreemap.org/styles/liberty"
    private const val DARK = "https://tiles.openfreemap.org/styles/dark"
    fun url(dark: Boolean) = if (dark) DARK else LIGHT
}

object AlphaMapInit {
    @Volatile private var done = false

    fun ensure(context: Context) {
        if (done) return
        synchronized(this) {
            if (done) return
            val app = context.applicationContext
            MapLibre.getInstance(app)
            // Private mode can change while the app runs: the route is chosen per
            // request. Through Tor the host name stays unresolved (Tor resolves it).
            org.maplibre.android.module.http.HttpRequestUtil.setOkHttpClient(
                okhttp3.OkHttpClient.Builder().proxySelector(PrivateModeProxySelector).build()
            )
            done = true
        }
    }
}

/** Tor while private mode is on, direct otherwise — asked for every connection. */
private object PrivateModeProxySelector : java.net.ProxySelector() {
    override fun select(uri: java.net.URI?): List<java.net.Proxy> =
        listOf(if (com.alphamessenger.data.EmbeddedTor.enabled.value) com.alphamessenger.data.EmbeddedTor.proxy() else java.net.Proxy.NO_PROXY)

    override fun connectFailed(uri: java.net.URI?, sa: java.net.SocketAddress?, ioe: java.io.IOException?) = Unit
}

/** Imperative handle for camera moves from buttons. */
class AlphaMapController {
    internal var map: MapLibreMap? = null
    fun moveTo(lat: Double, lng: Double, zoom: Double = 15.5) {
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), zoom), 450)
    }
    fun center(): Pair<Double, Double>? = map?.cameraPosition?.target?.let { it.latitude to it.longitude }
}

/** Interactive map with an optional marker at [lat]/[lng]. */
@Composable
fun AlphaMapView(
    lat: Double,
    lng: Double,
    dark: Boolean,
    modifier: Modifier = Modifier,
    zoom: Double = 15.5,
    showMarker: Boolean = true,
    controller: AlphaMapController? = null,
    onCameraIdle: ((Double, Double) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val mapView = remember {
        AlphaMapInit.ensure(ctx)
        val options = MapLibreMapOptions.createFromAttributes(ctx)
            .textureMode(true) // a TextureView composes cleanly inside Compose/Dialogs
            .camera(CameraPosition.Builder().target(LatLng(lat, lng)).zoom(zoom).build())
            .compassEnabled(false)
            .logoEnabled(false)
            .attributionEnabled(true) // OSM/OpenMapTiles attribution is required
            .rotateGesturesEnabled(false)
            .tiltGesturesEnabled(false)
        MapView(ctx, options).also { it.onCreate(null) }
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            controller?.map = null
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }
    LaunchedEffect(mapView, dark) {
        mapView.getMapAsync { map ->
            controller?.map = map
            map.setStyle(Style.Builder().fromUri(AlphaMapStyle.url(dark))) {
                map.clear()
                if (showMarker) map.addMarker(MarkerOptions().position(LatLng(lat, lng)))
            }
            if (onCameraIdle != null) {
                map.addOnCameraIdleListener {
                    map.cameraPosition.target?.let { onCameraIdle(it.latitude, it.longitude) }
                }
            }
        }
    }
    AndroidView(factory = { mapView }, modifier = modifier)
}

/** Rendered map thumbnails (no UI map instance), cached in memory and on disk. */
object MapSnapshots {
    private val memory = android.util.LruCache<String, Bitmap>(24)

    suspend fun get(ctx: Context, lat: Double, lng: Double, widthDp: Int, heightDp: Int, density: Float, dark: Boolean): Bitmap? {
        if (widthDp <= 0 || heightDp <= 0) return null
        val key = "%.5f_%.5f_%d_%d_%.1f_%s".format(java.util.Locale.US, lat, lng, widthDp, heightDp, density, if (dark) "d" else "l")
        memory.get(key)?.let { return it }
        val file = File(ctx.cacheDir, "mapsnap/${key.hashCode().toUInt()}_${key.length}.png")
        withContext(Dispatchers.IO) { if (file.exists()) com.alphamessenger.data.MediaVault.decodeFile(file.path) else null }
            ?.let { memory.put(key, it); return it }

        val bmp = withContext(Dispatchers.Main) {
            AlphaMapInit.ensure(ctx)
            suspendCancellableCoroutine<Bitmap?> { cont ->
                val options = MapSnapshotter.Options(widthDp, heightDp)
                    .withStyleBuilder(Style.Builder().fromUri(AlphaMapStyle.url(dark)))
                    .withCameraPosition(CameraPosition.Builder().target(LatLng(lat, lng)).zoom(15.0).build())
                    .withPixelRatio(density)
                    .withLogo(false)
                val snapshotter = MapSnapshotter(ctx.applicationContext, options)
                snapshotter.start(
                    { snap -> if (cont.isActive) cont.resume(snap.bitmap) },
                    { _ -> if (cont.isActive) cont.resume(null) },
                )
                cont.invokeOnCancellation { snapshotter.cancel() }
            }
        } ?: return null
        memory.put(key, bmp)
        withContext(Dispatchers.IO) {
            runCatching {
                file.parentFile?.mkdirs()
                file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        return bmp
    }
}

/**
 * Make the hosting Compose Dialog a true edge-to-edge window (see the viewer's
 * WindowController: all three settings are needed together).
 */
@Composable
fun FullscreenDialogWindow(darkIcons: Boolean) {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window ?: return
    // A DisposableEffect runs when the composition is applied — before the
    // window's first frame — so it never shows once as a small window first.
    androidx.compose.runtime.DisposableEffect(window, darkIcons) {
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.attributes = window.attributes.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = darkIcons
            isAppearanceLightNavigationBars = darkIcons
        }
        onDispose { }
    }
}
