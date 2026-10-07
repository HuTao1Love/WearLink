package dev.wearlink.core.vpn

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.nekohasekai.libbox.InterfaceUpdateListener
import java.net.NetworkInterface

/**
 * Tracks the best physical (non-VPN) network and reports it to sing-box, which binds its
 * outbound sockets to it. Requests carry NOT_VPN by default, so our own tunnel is never picked.
 */
class DefaultNetworkMonitor(
    private val connectivity: ConnectivityManager,
    private val onNetworkChanged: (Network?) -> Unit,
) {
    @Volatile
    var network: Network? = null
        private set

    private var listener: InterfaceUpdateListener? = null
    private val handler = Handler(Looper.getMainLooper())

    private val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        .build()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = update(network)
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update(network)
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = update(network)
        override fun onLost(network: Network) {
            if (this@DefaultNetworkMonitor.network == network) update(null)
        }
    }

    private var registered = false

    fun start(listener: InterfaceUpdateListener) {
        this.listener = listener
        if (!registered) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                connectivity.registerBestMatchingNetworkCallback(request, callback, handler)
            } else {
                connectivity.requestNetwork(request, callback, handler)
            }
            registered = true
        }
        // Report the current state right away; sing-box waits for the first update.
        report(network ?: connectivity.activeNetwork?.takeUnless { isVpn(it) })
    }

    fun stop() {
        listener = null
        if (registered) {
            runCatching { connectivity.unregisterNetworkCallback(callback) }
            registered = false
        }
    }

    private fun update(newNetwork: Network?) {
        network = newNetwork
        onNetworkChanged(newNetwork)
        report(newNetwork)
    }

    private fun report(target: Network?) {
        val listener = listener ?: return
        if (target == null) {
            listener.updateDefaultInterface("", -1, false, false)
            return
        }
        val name = connectivity.getLinkProperties(target)?.interfaceName ?: return
        val index = runCatching { NetworkInterface.getByName(name)?.index }.getOrNull() ?: return
        val caps = connectivity.getNetworkCapabilities(target)
        val expensive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
        network = target
        listener.updateDefaultInterface(name, index, expensive, false)
    }

    private fun isVpn(network: Network) =
        connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
}
