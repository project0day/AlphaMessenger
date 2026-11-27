package com.alphamessenger.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether there is network — ONE source of truth for the whole project.
 *
 * Kept separate because three consumers ask this question: the send queue
 * (continue or wait), the UI (whether to show the "no connection" strip),
 * and sync. When each queried the system itself, they could disagree at
 * the same moment, and there was no way to explain to the user why the
 * icon says one thing while sending behaves differently.
 *
 * It answers "is there a CONFIRMED path to the internet", not "is Wi-Fi
 * on": an access point without internet is common, and for sending that
 * is as good as no network.
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val _online = MutableStateFlow(currentlyOnline())
    val online: StateFlow<Boolean> = _online.asStateFlow()

    val isOnline: Boolean get() = _online.value

    init {
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    set(currentlyOnline(), "network connected")
                }

                override fun onLost(network: Network) {
                    // Losing the network is a FACT taken from the event itself, not the result
                    // of re-polling the system: at the moment of `onLost` the system still
                    // reports the dying network as active, with all its flags — including
                    // validated internet. A poll here would keep the sensor at "online", the
                    // outage would go unnoticed, and the send queue would keep retrying
                    // uploads over a dead network. The loss event is unambiguous: the
                    // network we were using is gone, and until a new one arrives there is no
                    // connection.
                    set(false, "network lost")
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    // This is where "Wi-Fi on, no internet" is caught: the system marks
                    // internet access with a separate flag and clears it when validation
                    // fails. The flags are taken from the EVENT itself — they belong to the
                    // network in question, with no re-poll.
                    val ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    set(ok, if (ok) "internet validated" else "internet not validated")
                }
            })
        }.onFailure { android.util.Log.w(TAG, "could not watch the network: ${it.message}") }
    }

    /** State change with a reason: without it, outages are undebuggable from logs. */
    private fun set(value: Boolean, why: String) {
        if (_online.value == value) return
        _online.value = value
        android.util.Log.i(TAG, "online: ${if (value) "yes" else "no"} ($why)")
    }

    private fun currentlyOnline(): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(true)

    private companion object {
        const val TAG = "AlphaNetwork"
    }
}
