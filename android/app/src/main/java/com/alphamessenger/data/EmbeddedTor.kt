package com.alphamessenger.data

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import net.freehaven.tor.control.TorControlConnection
import org.torproject.jni.TorService

/**
 * Tor running beside the app, in its own process (`:tor`). Everything that goes
 * out in private mode (relays, media, wake-ups, link previews, maps) uses its
 * SOCKS port.
 *
 * Its own process because Tor ends the process it runs in on a fatal error (a
 * network that requires a protocol it lacks, for one): that must cost us the
 * Tor connection, never the app.
 *
 * The port is fixed so the core can be told before Tor has started; it differs
 * from the usual standalone Tor port so both can run side by side.
 */
object EmbeddedTor {
    const val SOCKS_HOST = "127.0.0.1"
    const val SOCKS_PORT = 39050
    const val PROXY_ADDR = "$SOCKS_HOST:$SOCKS_PORT"

    enum class State { OFF, STARTING, ON }

    private const val TAG = "EmbeddedTor"
    /** Tor dying this often within [DEATH_WINDOW_MS] is a fault, not a hiccup. */
    private const val MAX_DEATHS = 3
    private const val DEATH_WINDOW_MS = 120_000L
    private const val RETRY_AFTER_MS = 10 * 60_000L

    /** Outlives any screen: switching private mode must finish once started. */
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State.OFF)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var app: Context? = null
    @Volatile private var bound = false
    private val deaths = ArrayDeque<Long>()
    @Volatile private var retryAt = 0L

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) = Unit

        // The Tor process died; the system starts it again while we stay bound.
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "tor process died")
            _state.value = State.STARTING
            val now = SystemClock.elapsedRealtime()
            val giveUp = synchronized(this@EmbeddedTor) {
                deaths.addLast(now)
                while (deaths.isNotEmpty() && now - deaths.first() > DEATH_WINDOW_MS) deaths.removeFirst()
                deaths.size >= MAX_DEATHS
            }
            if (giveUp) {
                Log.w(TAG, "tor keeps dying, paused")
                retryAt = now + RETRY_AFTER_MS
                app?.let { unbind(it) }
            }
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getStringExtra(TorService.EXTRA_STATUS)
            if (bound && status == TorService.STATUS_OFF) Log.w(TAG, "tor stopped while needed")
            _state.value = when (status) {
                TorService.STATUS_ON -> State.ON
                TorService.STATUS_STARTING -> State.STARTING
                else -> if (bound) State.STARTING else State.OFF
            }
        }
    }

    /** A proxy for Kotlin-side clients. */
    fun proxy(): java.net.Proxy =
        java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress.createUnresolved(SOCKS_HOST, SOCKS_PORT))

    private val _enabled = MutableStateFlow(false)
    /** Private mode is on: Tor runs and the core routes through it. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /**
     * Start Tor if private mode is on or secret chats exist. Called once from
     * the Application, before unlock: both are mirrored outside the core, so
     * Tor bootstraps while the user types the PIN.
     */
    fun startIfEnabled(context: Context) {
        val prefs = NetworkPrivacyPrefs(context.applicationContext)
        if (prefs.torEnabled) start(context)
        if (prefs.secretChats) needForSecretChats(context, true)
    }

    /** Secret chats exist: Tor runs for them even with private mode off. */
    @Volatile private var secretChats = false
    val runsForSecretChats: Boolean get() = secretChats

    /** Idempotent: secret chat screens call it while they wait for their link,
     *  so a Tor that never started is started rather than waited on. */
    @Synchronized
    fun needForSecretChats(context: Context, needed: Boolean) {
        if (secretChats != needed) {
            secretChats = needed
            NetworkPrivacyPrefs(context.applicationContext).secretChats = needed
        }
        if (needed) bind(context) else if (!_enabled.value) unbind(context)
    }

    @Synchronized
    fun start(context: Context) {
        _enabled.value = true
        bind(context)
    }

    @Synchronized
    private fun bind(context: Context) {
        if (bound || SystemClock.elapsedRealtime() < retryAt) return
        val app = context.applicationContext.also { this.app = it }
        runCatching {
            writeTorrc(app)
            ContextCompat.registerReceiver(
                app, statusReceiver, IntentFilter(TorService.ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            bound = app.bindService(Intent(app, TorService::class.java), connection, Context.BIND_AUTO_CREATE)
            if (bound) _state.value = State.STARTING else Log.w(TAG, "tor service refused to bind")
        }.onFailure { Log.w(TAG, "start failed: ${it.message}") }
    }

    @Synchronized
    fun stop(context: Context) {
        _enabled.value = false
        if (!secretChats) unbind(context)
    }

    @Synchronized
    private fun unbind(context: Context) {
        if (!bound) return
        val app = context.applicationContext
        runCatching { app.unbindService(connection) }
        runCatching { app.stopService(Intent(app, TorService::class.java)) }
        runCatching { app.unregisterReceiver(statusReceiver) }
        bound = false
        _state.value = State.OFF
    }

    /** Bootstrap progress 0..100, or null when unknown. Asked over Tor's
     *  control socket: the service lives in another process. */
    suspend fun bootstrapPercent(): Int? = withContext(Dispatchers.IO) {
        val ctx = app ?: return@withContext null
        val info = runCatching { controlInfo(ctx, "status/bootstrap-phase") }.getOrNull() ?: return@withContext null
        Regex("PROGRESS=(\\d+)").find(info)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun controlInfo(context: Context, key: String): String? {
        val path = java.io.File(TorService.getTorrc(context).parentFile, "data/ControlSocket")
        if (!path.exists()) return null
        return LocalSocket().use { socket ->
            socket.connect(LocalSocketAddress(path.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            socket.soTimeout = 2_000
            val control = TorControlConnection(socket.inputStream, socket.outputStream)
            control.launchThread(true)
            control.authenticate(ByteArray(0))
            control.getInfo(key)
        }
    }

    /** Our port, no HTTP tunnel; the library's defaults fill in the rest. */
    private fun writeTorrc(context: Context) {
        TorService.getTorrc(context).writeText(
            "SOCKSPort $PROXY_ADDR\n" +
                "HTTPTunnelPort 0\n" +
                "ClientOnly 1\n" +
                "AvoidDiskWrites 1\n"
        )
    }
}
