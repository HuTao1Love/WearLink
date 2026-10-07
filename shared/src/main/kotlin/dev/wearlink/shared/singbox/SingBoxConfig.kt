package dev.wearlink.shared.singbox

import dev.wearlink.shared.model.AnyTlsConfig
import dev.wearlink.shared.model.AppRouting
import dev.wearlink.shared.model.Hysteria1Config
import dev.wearlink.shared.model.Hysteria2Config
import dev.wearlink.shared.model.ProxyConfig
import dev.wearlink.shared.model.RoutingMode
import dev.wearlink.shared.model.Security
import dev.wearlink.shared.model.ShadowsocksConfig
import dev.wearlink.shared.model.Transport
import dev.wearlink.shared.model.TrojanConfig
import dev.wearlink.shared.model.TuicConfig
import dev.wearlink.shared.model.VlessConfig
import dev.wearlink.shared.model.VmessConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** How traffic enters sing-box. */
sealed interface Inbound {
    /** VpnService TUN: every (or every selected) app, all protocols. */
    data object Tun : Inbound

    /**
     * Local HTTP/SOCKS proxy on 127.0.0.1, used as the system proxy on watches whose
     * firmware has no VPN service. Only apps that honour the system proxy are covered.
     */
    data class LocalProxy(val port: Int) : Inbound
}

/** Builds a sing-box 1.14 configuration: one inbound, one proxy out, DNS over the proxy. */
object SingBoxConfig {

    const val PROXY_TAG = "proxy"
    private const val DIRECT_TAG = "direct"
    private const val DNS_REMOTE = "dns-remote"
    private const val DNS_LOCAL = "dns-local"

    private val UTLS_FINGERPRINTS = setOf(
        "chrome", "firefox", "edge", "safari", "360", "qq", "ios", "android", "random", "randomized",
    )

    private val json = Json { prettyPrint = true }

    fun build(
        proxy: ProxyConfig,
        routing: AppRouting,
        ownPackage: String,
        inbound: Inbound = Inbound.Tun,
        logLevel: String = "warn",
    ): String = json.encodeToString(JsonObject.serializer(), buildObject(proxy, routing, ownPackage, inbound, logLevel))

    fun buildObject(
        proxy: ProxyConfig,
        routing: AppRouting,
        ownPackage: String,
        inbound: Inbound = Inbound.Tun,
        logLevel: String = "warn",
    ) =
        buildJsonObject {
            putJsonObject("log") {
                put("level", logLevel)
                put("timestamp", false)
            }
            putJsonObject("dns") {
                putJsonArray("servers") {
                    addJsonObject {
                        put("type", "https")
                        put("tag", DNS_REMOTE)
                        put("server", "1.1.1.1")
                        put("detour", PROXY_TAG)
                    }
                    addJsonObject {
                        put("type", "local")
                        put("tag", DNS_LOCAL)
                    }
                }
                put("final", DNS_REMOTE)
                // Most proxies and watch networks have no working IPv6; avoid slow fallbacks.
                put("strategy", "ipv4_only")
            }
            putJsonArray("inbounds") {
                when (inbound) {
                    Inbound.Tun -> addJsonObject {
                        put("type", "tun")
                        put("tag", "tun-in")
                        putJsonArray("address") {
                            add("172.19.0.1/30")
                            add("fdfe:dead:beef::1/126")
                        }
                        put("auto_route", true)
                        put("strict_route", true)
                        put("stack", "mixed")
                        putAppRouting(routing, ownPackage)
                    }
                    is Inbound.LocalProxy -> addJsonObject {
                        put("type", "mixed")
                        put("tag", "mixed-in")
                        put("listen", "127.0.0.1")
                        put("listen_port", inbound.port)
                    }
                }
            }
            putJsonArray("outbounds") {
                add(outbound(proxy))
                addJsonObject {
                    put("type", "direct")
                    put("tag", DIRECT_TAG)
                }
            }
            putJsonObject("route") {
                putJsonArray("rules") {
                    addJsonObject { put("action", "sniff") }
                    addJsonObject {
                        put("protocol", "dns")
                        put("action", "hijack-dns")
                    }
                    addJsonObject {
                        put("ip_is_private", true)
                        put("outbound", DIRECT_TAG)
                    }
                }
                put("final", PROXY_TAG)
                put("auto_detect_interface", true)
                put("default_domain_resolver", DNS_LOCAL)
            }
        }

    /**
     * Our own package always rides the tunnel: libbox protects its own sockets, so there is
     * no loop, and the "my IP" check and subscription refreshes then go through the proxy.
     */
    private fun JsonObjectBuilder.putAppRouting(routing: AppRouting, ownPackage: String) {
        when (routing.mode) {
            RoutingMode.ALL -> Unit
            RoutingMode.ONLY_SELECTED -> putJsonArray("include_package") {
                (routing.packages + ownPackage).sorted().forEach { add(it) }
            }
            RoutingMode.ALL_EXCEPT -> {
                val excluded = (routing.packages - ownPackage).sorted()
                if (excluded.isNotEmpty()) putJsonArray("exclude_package") { excluded.forEach { add(it) } }
            }
        }
    }

    fun outbound(proxy: ProxyConfig): JsonObject = when (proxy) {
        is VlessConfig -> vless(proxy)
        is VmessConfig -> vmess(proxy)
        is TrojanConfig -> trojan(proxy)
        is ShadowsocksConfig -> shadowsocks(proxy)
        is Hysteria2Config -> hysteria2(proxy)
        is Hysteria1Config -> hysteria1(proxy)
        is TuicConfig -> tuic(proxy)
        is AnyTlsConfig -> anyTls(proxy)
    }

    private fun JsonObjectBuilder.putServer(type: String, proxy: ProxyConfig) {
        put("type", type)
        put("tag", PROXY_TAG)
        put("server", proxy.host)
        put("server_port", proxy.port)
    }

    private fun vless(c: VlessConfig) = buildJsonObject {
        putServer("vless", c)
        put("uuid", c.uuid)
        if (c.flow.isNotEmpty()) put("flow", c.flow)
        put("packet_encoding", "xudp")
        putSecurity(c.security, c.host)
        putTransport(c.transport)
    }

    private fun vmess(c: VmessConfig) = buildJsonObject {
        putServer("vmess", c)
        put("uuid", c.uuid)
        put("security", c.cipher)
        if (c.alterId > 0) put("alter_id", c.alterId)
        put("packet_encoding", "xudp")
        putSecurity(c.security, c.host)
        putTransport(c.transport)
    }

    private fun trojan(c: TrojanConfig) = buildJsonObject {
        putServer("trojan", c)
        put("password", c.password)
        putSecurity(c.security, c.host)
        putTransport(c.transport)
    }

    private fun shadowsocks(c: ShadowsocksConfig) = buildJsonObject {
        putServer("shadowsocks", c)
        put("method", c.method)
        put("password", c.password)
        if (c.plugin.isNotEmpty()) {
            put("plugin", c.plugin)
            put("plugin_opts", c.pluginOpts)
        }
    }

    private fun hysteria2(c: Hysteria2Config) = buildJsonObject {
        putServer("hysteria2", c)
        if (c.serverPorts.isNotEmpty()) putJsonArray("server_ports") { c.serverPorts.forEach { add(it) } }
        put("password", c.password)
        if (c.obfsPassword.isNotEmpty()) putJsonObject("obfs") {
            put("type", "salamander")
            put("password", c.obfsPassword)
        }
        putSecurity(Security.Tls(sni = c.sni, insecure = c.insecure, alpn = listOf("h3")), c.host)
    }

    private fun hysteria1(c: Hysteria1Config) = buildJsonObject {
        putServer("hysteria", c)
        if (c.serverPorts.isNotEmpty()) putJsonArray("server_ports") { c.serverPorts.forEach { add(it) } }
        put("up_mbps", c.upMbps)
        put("down_mbps", c.downMbps)
        if (c.auth.isNotEmpty()) put("auth_str", c.auth)
        if (c.obfs.isNotEmpty()) put("obfs", c.obfs)
        putSecurity(c.tls, c.host)
    }

    private fun tuic(c: TuicConfig) = buildJsonObject {
        putServer("tuic", c)
        put("uuid", c.uuid)
        put("password", c.password)
        put("congestion_control", c.congestionControl)
        put("udp_relay_mode", c.udpRelayMode)
        putSecurity(c.tls, c.host)
    }

    private fun anyTls(c: AnyTlsConfig) = buildJsonObject {
        putServer("anytls", c)
        put("password", c.password)
        putSecurity(c.tls, c.host)
    }

    private fun JsonObjectBuilder.putSecurity(security: Security, host: String) {
        when (security) {
            Security.None -> Unit
            is Security.Tls -> putJsonObject("tls") {
                put("enabled", true)
                put("server_name", security.sni.ifEmpty { host })
                if (security.disableSni) put("disable_sni", true)
                if (security.insecure) put("insecure", true)
                if (security.alpn.isNotEmpty()) putJsonArray("alpn") { security.alpn.forEach { add(it) } }
                if (security.fingerprint.isNotEmpty()) putUtls(security.fingerprint)
            }
            is Security.Reality -> putJsonObject("tls") {
                put("enabled", true)
                put("server_name", security.sni.ifEmpty { host })
                putUtls(security.fingerprint)
                putJsonObject("reality") {
                    put("enabled", true)
                    put("public_key", security.publicKey)
                    if (security.shortId.isNotEmpty()) put("short_id", security.shortId)
                }
            }
        }
    }

    private fun JsonObjectBuilder.putTransport(transport: Transport) {
        when (transport) {
            Transport.Tcp -> Unit
            is Transport.WebSocket -> putJsonObject("transport") {
                put("type", "ws")
                put("path", transport.path)
                if (transport.host.isNotEmpty()) putJsonObject("headers") { put("Host", transport.host) }
                if (transport.maxEarlyData > 0) {
                    put("max_early_data", transport.maxEarlyData)
                    put("early_data_header_name", transport.earlyDataHeader)
                }
            }
            is Transport.Grpc -> putJsonObject("transport") {
                put("type", "grpc")
                put("service_name", transport.serviceName)
            }
            is Transport.HttpUpgrade -> putJsonObject("transport") {
                put("type", "httpupgrade")
                put("path", transport.path)
                if (transport.host.isNotEmpty()) put("host", transport.host)
            }
            is Transport.Http -> putJsonObject("transport") {
                put("type", "http")
                put("path", transport.path)
                if (transport.host.isNotEmpty()) putJsonArray("host") { transport.host.forEach { add(it) } }
            }
        }
    }

    private fun JsonObjectBuilder.putUtls(fingerprint: String) {
        putJsonObject("utls") {
            put("enabled", true)
            put("fingerprint", fingerprint.lowercase().takeIf { it in UTLS_FINGERPRINTS } ?: "chrome")
        }
    }
}
