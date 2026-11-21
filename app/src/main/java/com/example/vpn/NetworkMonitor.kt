package com.example.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.example.domain.model.NetworkStatus
import com.example.domain.model.TransportType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Monitors active device network state, Wi-Fi <-> Mobile Data transitions,
 * offline states, and conflicting third-party VPN tunnels.
 */
class NetworkMonitor(context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _networkStatus = MutableStateFlow(readCurrentStatus())
    val networkStatus: StateFlow<NetworkStatus> = _networkStatus.asStateFlow()

    @Volatile
    private var isSelfVpnExpected: Boolean = false

    private var onNetworkTransitionListener: ((TransportType, Boolean) -> Unit)? = null
    private var lastReportedTransport: TransportType = _networkStatus.value.transportType
    private var lastReportedConnected: Boolean = _networkStatus.value.isConnected

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            refreshAndNotify()
        }

        override fun onLost(network: Network) {
            refreshAndNotify()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            refreshAndNotify()
        }
    }

    fun startMonitoring(onTransition: (TransportType, Boolean) -> Unit) {
        onNetworkTransitionListener = onTransition
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(callback)
        }
        refreshAndNotify(notifyListener = false)
    }

    fun stopMonitoring() {
        runCatching {
            connectivityManager.unregisterNetworkCallback(callback)
        }
        onNetworkTransitionListener = null
    }

    fun setSelfVpnActive(active: Boolean) {
        isSelfVpnExpected = active
        refreshAndNotify(notifyListener = false)
    }

    fun refreshNow(): NetworkStatus {
        val current = readCurrentStatus()
        _networkStatus.value = current
        return current
    }

    private fun refreshAndNotify(notifyListener: Boolean = true) {
        val updated = readCurrentStatus()
        _networkStatus.value = updated

        val transportChanged = updated.transportType != lastReportedTransport
        val connectivityRestored = !lastReportedConnected && updated.isConnected
        lastReportedTransport = updated.transportType
        lastReportedConnected = updated.isConnected

        if (notifyListener && (transportChanged || connectivityRestored)) {
            onNetworkTransitionListener?.invoke(updated.transportType, updated.isConnected)
        }
    }

    @Suppress("DEPRECATION")
    private fun readCurrentStatus(): NetworkStatus {
        val activeNet = connectivityManager.activeNetwork
        val caps = activeNet?.let { connectivityManager.getNetworkCapabilities(it) }

        val allNetworksHasOtherVpn = runCatching {
            !isSelfVpnExpected && connectivityManager.allNetworks.any { net ->
                val c = connectivityManager.getNetworkCapabilities(net)
                c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
        }.getOrDefault(false)

        if (caps == null) {
            return NetworkStatus(
                isConnected = false,
                transportType = TransportType.NONE,
                hasOtherVpnActive = allNetworksHasOtherVpn,
                interfaceSummary = "Offline"
            )
        }

        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> TransportType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> TransportType.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> TransportType.ETHERNET
            // If default network is a VPN, look for underlying physical transport
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> detectPhysicalTransportFallback()
            else -> TransportType.OTHER
        }

        val hasConflict = allNetworksHasOtherVpn ||
            (!isSelfVpnExpected && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))

        return NetworkStatus(
            isConnected = hasInternet,
            transportType = if (hasInternet) transport else TransportType.NONE,
            hasOtherVpnActive = hasConflict,
            interfaceSummary = if (hasInternet) transport.label else "Offline"
        )
    }

    @Suppress("DEPRECATION")
    private fun detectPhysicalTransportFallback(): TransportType {
        return runCatching {
            val nets = connectivityManager.allNetworks
            for (n in nets) {
                val c = connectivityManager.getNetworkCapabilities(n) ?: continue
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return TransportType.WIFI
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return TransportType.CELLULAR
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return TransportType.ETHERNET
            }
            TransportType.OTHER
        }.getOrDefault(TransportType.OTHER)
    }
}
