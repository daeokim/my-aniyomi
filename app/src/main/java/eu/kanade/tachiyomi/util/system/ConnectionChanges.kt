package eu.kanade.tachiyomi.util.system

import android.content.Context
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

/** A connectivity hint only: validated Internet never means a WG tunnel has completed setup. */
@OptIn(kotlinx.coroutines.FlowPreview::class)
fun Context.stableConnectionChanges() = callbackFlow {
    val manager = connectivityManager
    fun snapshot(network: Network?) {
        val caps = network?.let(manager::getNetworkCapabilities)
        trySend(ConnectionSignal(network?.toString(), caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true))
    }
    snapshot(manager.activeNetwork)
    val callback = object : NetworkCallback() {
        override fun onAvailable(network: Network) = snapshot(manager.activeNetwork)
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = snapshot(manager.activeNetwork)
        override fun onLost(network: Network) = snapshot(manager.activeNetwork)
    }
    manager.registerDefaultNetworkCallback(callback)
    awaitClose { manager.unregisterNetworkCallback(callback) }
}.afterConnectionSettles()
