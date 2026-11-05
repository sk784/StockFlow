package dev.stockflow.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.stockflow.domain.ConnectionMonitor
import dev.stockflow.domain.ConnectionState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject

class AndroidConnectionMonitor @Inject constructor(@param:ApplicationContext private val context: Context) : ConnectionMonitor {
    override fun observeConnection() = callbackFlow {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        fun publish() {
            val connected = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            trySend(if (connected) ConnectionState.CONNECTED else ConnectionState.OFFLINE)
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = publish()
            override fun onLost(network: Network) = publish()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = publish()
        }
        manager.registerDefaultNetworkCallback(callback)
        publish()
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
