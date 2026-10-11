package dev.wearlink.core.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dev.wearlink.shared.model.Hysteria1Config
import dev.wearlink.shared.model.Hysteria2Config
import dev.wearlink.shared.model.Server
import dev.wearlink.shared.model.SingBoxOutbound
import dev.wearlink.shared.model.TuicConfig
import dev.wearlink.shared.model.UrlTestGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

sealed interface PingResult {
    data object Testing : PingResult
    data class Ok(val ms: Int) : PingResult
    data object Failed : PingResult

    /** Hysteria/TUIC run over UDP; a TCP connect says nothing about them. */
    data object Udp : PingResult
}

/**
 * TCP ping like v2rayNG's "TCPing": time to open a TCP connection to the server's host:port.
 * Sockets are bound to the physical network so the result is not measured through our own tunnel.
 */
object ServerPing {

    private const val TIMEOUT_MS = 3000
    private const val PARALLEL = 8

    private val _results = MutableStateFlow<Map<String, PingResult>>(emptyMap())
    val results: StateFlow<Map<String, PingResult>> = _results.asStateFlow()

    suspend fun ping(context: Context, servers: List<Server>) = coroutineScope {
        val targets = servers.filter { it.isUsable && !it.isAutoSelect }
        val network = physicalNetwork(context)
        _results.update { it + targets.associate { server -> server.id to PingResult.Testing } }
        val limit = Semaphore(PARALLEL)
        targets.forEach { server ->
            launch {
                val result = limit.withPermit { measure(server, network) }
                _results.update { it + (server.id to result) }
            }
        }
    }

    private suspend fun measure(server: Server, network: Network?): PingResult {
        val config = server.config ?: return PingResult.Failed
        if (isUdp(config)) return PingResult.Udp
        return withContext(Dispatchers.IO) {
            runCatching {
                val address = network?.getAllByName(config.host)?.first() ?: InetAddress.getByName(config.host)
                Socket().use { socket ->
                    network?.bindSocket(socket)
                    val start = System.nanoTime()
                    socket.connect(InetSocketAddress(address, config.port), TIMEOUT_MS)
                    PingResult.Ok(((System.nanoTime() - start) / 1_000_000).toInt().coerceAtLeast(1))
                }
            }.getOrDefault(PingResult.Failed)
        }
    }

    private fun isUdp(config: Any): Boolean = when (config) {
        is Hysteria2Config, is Hysteria1Config, is TuicConfig -> true
        is SingBoxOutbound -> config.outboundType in setOf("hysteria2", "hysteria", "tuic")
        is UrlTestGroup -> true
        else -> false
    }

    /** The validated non-VPN network; null lets the system choose (no VPN running, e.g. proxy mode). */
    private fun physicalNetwork(context: Context): Network? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        return connectivity.allNetworks.firstOrNull { network ->
            connectivity.getNetworkCapabilities(network)?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            } == true
        }
    }
}
