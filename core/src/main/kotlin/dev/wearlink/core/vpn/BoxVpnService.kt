package dev.wearlink.core.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.Network
import android.net.NetworkCapabilities
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.wearlink.core.WearLinkCore
import dev.wearlink.core.data.GeoLists
import dev.wearlink.shared.model.Server
import dev.wearlink.shared.singbox.Inbound
import dev.wearlink.shared.singbox.SingBoxConfig
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface as JavaNetworkInterface
import io.nekohasekai.libbox.NetworkInterface as BoxNetworkInterface

/** VpnService hosting the sing-box core. Lives in the app process; state goes to [VpnController]. */
class BoxVpnService : VpnService(), PlatformInterface, CommandServerHandler {

    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val networkMonitor by lazy {
        DefaultNetworkMonitor(connectivity) { network ->
            // Lets the system attribute metering/bandwidth to the real network.
            if (mode == TunnelMode.VPN) setUnderlyingNetworks(network?.let { arrayOf(it) })
        }
    }

    /** Decided on each start: TUN where VpnService works, the system HTTP proxy elsewhere. */
    @Volatile
    private var mode = TunnelMode.VPN
    private val localResolver by lazy { LocalResolver(networkMonitor) }

    private var commandServer: CommandServer? = null
    private var tunFd: ParcelFileDescriptor? = null
    private var watchJob: Job? = null
    private val lifecycle = Mutex()

    override fun onCreate() {
        super.onCreate()
        instance = this
        WearLinkCore.init(application)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopVpn()
            else -> {
                val server = WearLinkCore.store.current.selectedServer
                startForegroundCompat(server?.name ?: "")
                WearLinkCore.scope.launch { startVpn() }
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() = stopVpn()

    override fun onDestroy() {
        SystemProxy.disable(this)
        if (instance === this) instance = null
        super.onDestroy()
    }

    private suspend fun startVpn() = lifecycle.withLock {
        val data = WearLinkCore.store.current
        val server = data.selectedServer
        if (server == null) {
            fail("Сервер не выбран")
            return@withLock
        }
        VpnController.setState(VpnState.Connecting(server.name))
        try {
            mode = VpnController.mode(this)
            VpnController.missingPermission(this)?.let { throw IllegalStateException(it) }
            if (data.lists.enabled && GeoLists.catalog.value == null) GeoLists.update(this)
            WearLinkCore.ensureLibbox(this)
            val server0 = commandServer ?: Libbox.newCommandServer(this, this).also { commandServer = it }
            server0.startOrReloadService(buildConfig(server), OverrideOptions())
            // Point the device at sing-box only once it is listening.
            if (mode == TunnelMode.PROXY) SystemProxy.enable(this)
            VpnController.setState(VpnState.Connected(server.name))
            updateNotification(server.name)
            watchSelection()
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun buildConfig(server: Server): String {
        val data = WearLinkCore.store.current
        return SingBoxConfig.build(
            server.config!!,
            data.routing,
            packageName,
            inbound = if (mode == TunnelMode.PROXY) Inbound.LocalProxy(SystemProxy.PORT) else Inbound.Tun,
            ruleSets = if (data.lists.enabled) GeoLists.ruleSets(this, data.lists) else null,
        )
    }

    /** Reloads the core when the default server or the app list changes while connected. */
    private fun watchSelection() {
        if (watchJob != null) return
        watchJob = WearLinkCore.scope.launch {
            WearLinkCore.store.data
                .map { Triple(it.selectedServer, it.routing, it.lists) }
                .distinctUntilChanged()
                .drop(1)
                .collect { (server, _, _) ->
                    if (server == null) {
                        stopVpn()
                    } else {
                        startVpn()
                    }
                }
        }
    }

    private suspend fun fail(message: String) {
        teardown()
        VpnController.setState(VpnState.Error(message))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun stopVpn() {
        WearLinkCore.scope.launch {
            lifecycle.withLock { teardown() }
            VpnController.setState(VpnState.Stopped)
            ServiceCompat.stopForeground(this@BoxVpnService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun teardown() {
        // First, so apps never point at a proxy that is going away.
        SystemProxy.disable(this)
        watchJob?.cancel()
        watchJob = null
        commandServer?.let { server ->
            runCatching { server.closeService() }
            runCatching { server.close() }
        }
        commandServer = null
        networkMonitor.stop()
        tunFd?.let { runCatching { it.close() } }
        tunFd = null
    }

    // region Notification

    private fun startForegroundCompat(serverName: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(serverName, connected = false), type)
    }

    private fun updateNotification(serverName: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(serverName, connected = true))
    }

    private fun buildNotification(serverName: String, connected: Boolean): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
            )
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, BoxVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(WearLinkCore.notificationIcon)
            .setContentTitle(
                when {
                    !connected -> "Подключение…"
                    mode == TunnelMode.PROXY -> "Прокси включён"
                    else -> "VPN подключён"
                },
            )
            .setContentText(serverName)
            .setContentIntent(open)
            .addAction(0, "Отключить", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    // endregion

    // region PlatformInterface

    override fun localDNSTransport(): LocalDNSTransport = localResolver

    override fun usePlatformAutoDetectInterfaceControl() = true

    override fun autoDetectInterfaceControl(fd: Int) {
        // Without a TUN there is nothing to escape from.
        if (mode == TunnelMode.VPN && !protect(fd)) throw IllegalStateException("protect($fd) failed")
    }

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) throw IllegalStateException("Нет разрешения на VPN")
        val builder = Builder()
            .setSession("WearLink")
            .setMtu(options.mtu)
            .setMetered(false)

        options.inet4Address.forEach { builder.addAddress(it.address(), it.prefix()) }
        options.inet6Address.forEach { builder.addAddress(it.address(), it.prefix()) }

        if (options.autoRoute) {
            options.dnsServerAddress.forEach { builder.addDnsServer(it) }

            var hasV4 = false
            options.inet4RouteRange.forEach { builder.addRoute(it.address(), it.prefix()); hasV4 = true }
            if (!hasV4) builder.addRoute("0.0.0.0", 0)
            var hasV6 = false
            options.inet6RouteRange.forEach { builder.addRoute(it.address(), it.prefix()); hasV6 = true }
            if (!hasV6) builder.addRoute("::", 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                options.inet4RouteExcludeAddress.forEach {
                    builder.excludeRoute(IpPrefix(InetAddress.getByName(it.address()), it.prefix()))
                }
                options.inet6RouteExcludeAddress.forEach {
                    builder.excludeRoute(IpPrefix(InetAddress.getByName(it.address()), it.prefix()))
                }
            }

            val include = options.includePackage.toList()
            val exclude = options.excludePackage.toList()
            include.forEach { pkg ->
                try {
                    builder.addAllowedApplication(pkg)
                } catch (_: PackageManager.NameNotFoundException) {
                    // App was uninstalled since it was picked; skip it.
                }
            }
            if (include.isEmpty()) {
                exclude.forEach { pkg ->
                    try {
                        builder.addDisallowedApplication(pkg)
                    } catch (_: PackageManager.NameNotFoundException) {
                    }
                }
            }
        }

        if (options.isHTTPProxyEnabled) {
            builder.setHttpProxy(
                ProxyInfo.buildDirectProxy(
                    options.httpProxyServer,
                    options.httpProxyServerPort,
                    options.httpProxyBypassDomain.toList(),
                ),
            )
        }

        val pfd = builder.establish() ?: throw IllegalStateException("VPN не разрешён или отозван системой")
        // On reload sing-box opens a new TUN; the old descriptor is released only after the new one is up.
        tunFd?.let { runCatching { it.close() } }
        tunFd = pfd
        return pfd.fd
    }

    override fun useProcFS() = false

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner {
        val uid = connectivity.getConnectionOwnerUid(
            ipProtocol,
            InetSocketAddress(sourceAddress, sourcePort),
            InetSocketAddress(destinationAddress, destinationPort),
        )
        if (uid == Process.INVALID_UID) throw IllegalStateException("connection owner not found")
        return ConnectionOwner().apply {
            userId = uid
            userName = packageManager.getNameForUid(uid).orEmpty()
            setAndroidPackageNames(StringArray(packageManager.getPackagesForUid(uid)?.toList().orEmpty()))
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = networkMonitor.start(listener)

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = networkMonitor.stop()

    override fun getInterfaces(): NetworkInterfaceIterator {
        val result = mutableListOf<BoxNetworkInterface>()
        for (network in connectivity.allNetworks) {
            val caps = connectivity.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val link = connectivity.getLinkProperties(network) ?: continue
            val name = link.interfaceName ?: continue
            val javaInterface = runCatching { JavaNetworkInterface.getByName(name) }.getOrNull() ?: continue
            result += BoxNetworkInterface().apply {
                this.name = name
                index = javaInterface.index
                mtu = runCatching { javaInterface.mtu }.getOrDefault(1500)
                addresses = StringArray(link.linkAddresses.map { "${it.address.hostAddress?.substringBefore('%')}/${it.prefixLength}" })
                flags = linkFlags(javaInterface)
                type = interfaceType(caps)
                dnsServer = StringArray(link.dnsServers.mapNotNull { it.hostAddress?.substringBefore('%') })
                gateway = StringArray(
                    link.routes.filter { it.isDefaultRoute && it.gateway != null }
                        .mapNotNull { it.gateway?.hostAddress?.substringBefore('%') },
                )
                metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
        }
        return InterfaceArray(result)
    }

    override fun underNetworkExtension() = false
    override fun includeAllNetworks() = false
    override fun readWIFIState(): WIFIState? = null
    override fun clearDNSCache() = Unit
    override fun sendNotification(notification: io.nekohasekai.libbox.Notification) = Unit
    override fun cancelNotification(identifier: String, typeID: Int) = Unit
    override fun startNeighborMonitor(listener: NeighborUpdateListener) = Unit
    override fun closeNeighborMonitor(listener: NeighborUpdateListener) = Unit
    override fun registerMyInterface(name: String) = Unit
    override fun usePlatformShell() = false
    override fun checkPlatformShell() = throw UnsupportedOperationException()
    override fun openShellSession(
        user: PlatformUser,
        command: String,
        environ: StringIterator,
        term: String,
        rows: Int,
        cols: Int,
    ): ShellSession = throw UnsupportedOperationException()
    override fun lookupUser(username: String): PlatformUser = throw UnsupportedOperationException()
    override fun lookupSFTPServer(): String = throw UnsupportedOperationException()
    override fun readSystemSSHHostKey(): String = throw UnsupportedOperationException()
    override fun tailscaleHostname(): String = Build.MODEL
    override fun usePlatformBridge() = false
    override fun createBridge(options: BridgeOptions): BridgeSession = throw UnsupportedOperationException()

    // endregion

    // region CommandServerHandler

    override fun serviceStop() = stopVpn()
    override fun serviceReload() {
        WearLinkCore.scope.launch(Dispatchers.Default) { startVpn() }
    }
    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus()
    override fun setSystemProxyEnabled(enabled: Boolean) = Unit
    override fun triggerNativeCrash() = Unit
    override fun writeDebugMessage(message: String) {
        Log.d(TAG, message)
    }
    override fun connectSSHAgent(): Int = throw UnsupportedOperationException()

    // endregion

    companion object {
        const val ACTION_START = "dev.wearlink.vpn.START"
        const val ACTION_STOP = "dev.wearlink.vpn.STOP"
        private const val TAG = "WearLinkVpn"
        private const val CHANNEL_ID = "vpn"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var instance: BoxVpnService? = null
            private set

        private fun linkFlags(iface: JavaNetworkInterface): Int {
            var flags = 0
            runCatching {
                if (iface.isUp) flags = flags or IFF_UP or IFF_RUNNING
                if (iface.isLoopback) flags = flags or IFF_LOOPBACK
                if (iface.isPointToPoint) flags = flags or IFF_POINTOPOINT
                if (iface.supportsMulticast()) flags = flags or IFF_MULTICAST
                if (iface.inetAddresses.toList().any { it !is Inet6Address }) flags = flags or IFF_BROADCAST
            }
            return flags
        }

        private fun interfaceType(caps: NetworkCapabilities) = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
            else -> Libbox.InterfaceTypeOther
        }

        // Linux if.h flags, decoded by libbox's linkFlags().
        private const val IFF_UP = 0x1
        private const val IFF_BROADCAST = 0x2
        private const val IFF_LOOPBACK = 0x8
        private const val IFF_POINTOPOINT = 0x10
        private const val IFF_RUNNING = 0x40
        private const val IFF_MULTICAST = 0x1000
    }
}

private class StringArray(private val values: List<String>) : StringIterator {
    private var index = 0
    override fun hasNext() = index < values.size
    override fun next() = values[index++]
    override fun len() = values.size
}

private class InterfaceArray(private val values: List<BoxNetworkInterface>) : NetworkInterfaceIterator {
    private var index = 0
    override fun hasNext() = index < values.size
    override fun next() = values[index++]
}

private inline fun io.nekohasekai.libbox.RoutePrefixIterator.forEach(action: (io.nekohasekai.libbox.RoutePrefix) -> Unit) {
    while (hasNext()) action(next())
}

private inline fun StringIterator.forEach(action: (String) -> Unit) {
    while (hasNext()) action(next())
}

private fun StringIterator.toList(): List<String> = buildList { this@toList.forEach { add(it) } }
