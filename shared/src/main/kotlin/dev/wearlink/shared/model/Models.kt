package dev.wearlink.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Proxy settings decoded from a share link. Only protocols the core can run are modelled. */
@Serializable
sealed interface ProxyConfig {
    val host: String
    val port: Int
}

@Serializable
@SerialName("vless")
data class VlessConfig(
    override val host: String,
    override val port: Int,
    val uuid: String,
    val flow: String = "",
    val transport: Transport = Transport.Tcp,
    val security: Security = Security.None,
) : ProxyConfig

@Serializable
@SerialName("hysteria2")
data class Hysteria2Config(
    override val host: String,
    override val port: Int,
    val password: String,
    /** Port hopping ranges in sing-box syntax, e.g. "20000:30000". */
    val serverPorts: List<String> = emptyList(),
    val sni: String = "",
    val insecure: Boolean = false,
    val obfsPassword: String = "",
) : ProxyConfig

@Serializable
@SerialName("trojan")
data class TrojanConfig(
    override val host: String,
    override val port: Int,
    val password: String,
    val transport: Transport = Transport.Tcp,
    val security: Security = Security.Tls(),
) : ProxyConfig

@Serializable
@SerialName("shadowsocks")
data class ShadowsocksConfig(
    override val host: String,
    override val port: Int,
    val method: String,
    val password: String,
    /** sing-box supports "obfs-local" and "v2ray-plugin". */
    val plugin: String = "",
    val pluginOpts: String = "",
) : ProxyConfig

@Serializable
@SerialName("vmess")
data class VmessConfig(
    override val host: String,
    override val port: Int,
    val uuid: String,
    val alterId: Int = 0,
    val cipher: String = "auto",
    val transport: Transport = Transport.Tcp,
    val security: Security = Security.None,
) : ProxyConfig

@Serializable
@SerialName("tuic")
data class TuicConfig(
    override val host: String,
    override val port: Int,
    val uuid: String,
    val password: String,
    val congestionControl: String = "bbr",
    val udpRelayMode: String = "native",
    val tls: Security.Tls = Security.Tls(alpn = listOf("h3")),
) : ProxyConfig

@Serializable
@SerialName("anytls")
data class AnyTlsConfig(
    override val host: String,
    override val port: Int,
    val password: String,
    val tls: Security.Tls = Security.Tls(),
) : ProxyConfig

/** Hysteria v1; [Hysteria2Config] is the current protocol. */
@Serializable
@SerialName("hysteria")
data class Hysteria1Config(
    override val host: String,
    override val port: Int,
    val auth: String = "",
    val serverPorts: List<String> = emptyList(),
    val upMbps: Int = 10,
    val downMbps: Int = 50,
    val obfs: String = "",
    val tls: Security.Tls = Security.Tls(),
) : ProxyConfig

/**
 * An outbound copied verbatim from a sing-box JSON subscription. Keeping the provider's JSON
 * avoids losing options we do not model (uTLS, multiplex, padding...).
 */
@Serializable
@SerialName("singbox")
data class SingBoxOutbound(
    override val host: String,
    override val port: Int,
    /** sing-box outbound type ("vless", "hysteria2", ...); not `type`, which is the serial discriminator. */
    val outboundType: String,
    val json: String,
    val label: String,
) : ProxyConfig

@Serializable
sealed interface Transport {
    @Serializable
    @SerialName("tcp")
    data object Tcp : Transport

    @Serializable
    @SerialName("ws")
    data class WebSocket(
        val path: String = "/",
        val host: String = "",
        val maxEarlyData: Int = 0,
        val earlyDataHeader: String = "",
    ) : Transport

    @Serializable
    @SerialName("grpc")
    data class Grpc(val serviceName: String = "") : Transport

    @Serializable
    @SerialName("httpupgrade")
    data class HttpUpgrade(val path: String = "/", val host: String = "") : Transport

    @Serializable
    @SerialName("http")
    data class Http(val path: String = "/", val host: List<String> = emptyList()) : Transport
}

@Serializable
sealed interface Security {
    @Serializable
    @SerialName("none")
    data object None : Security

    @Serializable
    @SerialName("tls")
    data class Tls(
        val sni: String = "",
        val fingerprint: String = "",
        val alpn: List<String> = emptyList(),
        val insecure: Boolean = false,
        val disableSni: Boolean = false,
    ) : Security

    @Serializable
    @SerialName("reality")
    data class Reality(
        val sni: String = "",
        val fingerprint: String = "chrome",
        val publicKey: String,
        val shortId: String = "",
    ) : Security
}

/**
 * One entry in the server list. [config] is null when the link could not be used
 * (unknown protocol or unsupported transport); [error] then explains why.
 */
@Serializable
data class Server(
    val id: String,
    val name: String,
    val link: String,
    val config: ProxyConfig? = null,
    val error: String? = null,
    val subscriptionId: String? = null,
) {
    val isUsable: Boolean get() = config != null

    val protocolLabel: String
        get() = when (config) {
            is VlessConfig -> when (config.security) {
                is Security.Reality -> "VLESS Reality"
                is Security.Tls -> "VLESS TLS"
                Security.None -> "VLESS"
            }
            is Hysteria2Config -> "Hysteria2"
            is Hysteria1Config -> "Hysteria"
            is TrojanConfig -> "Trojan"
            is ShadowsocksConfig -> "Shadowsocks"
            is VmessConfig -> if (config.security is Security.None) "VMess" else "VMess TLS"
            is TuicConfig -> "TUIC"
            is AnyTlsConfig -> "AnyTLS"
            is SingBoxOutbound -> config.label
            null -> link.substringBefore("://").uppercase()
        }
}

@Serializable
data class Subscription(
    val id: String,
    val url: String,
    val name: String,
    val updatedAt: Long = 0,
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    /** Unix seconds; 0 when the provider does not report it. */
    val expire: Long = 0,
    val updateIntervalHours: Int = 0,
    val lastError: String? = null,
)

@Serializable
enum class RoutingMode {
    /** Every app goes through the VPN. */
    ALL,

    /** Only the chosen apps go through the VPN. */
    ONLY_SELECTED,

    /** Every app except the chosen ones goes through the VPN. */
    ALL_EXCEPT,
}

@Serializable
data class AppRouting(
    val mode: RoutingMode = RoutingMode.ALL,
    val packages: Set<String> = emptySet(),
)

/**
 * Selective routing by zkeen lists (github.com/jameszeroX/zkeen-domains, zkeen-ip): when
 * [enabled], only traffic matching the chosen categories goes through the proxy.
 */
@Serializable
data class ListRouting(
    val enabled: Boolean = false,
    val sites: Set<String> = DEFAULT_SITES,
    val ips: Set<String> = DEFAULT_IPS,
) {
    companion object {
        /** The categories the zkeen README routes through the proxy. */
        val DEFAULT_SITES = setOf("DOMAINS", "OTHER", "POLITIC", "YOUTUBE")
        val DEFAULT_IPS = setOf(
            "AKAMAI", "AMAZON", "ARELION", "AZURE", "BUNNYCDN", "CDN77", "CLOUDFLARE", "COGENT",
            "COLOCROSSING", "CONTABO", "DATACAMP", "DIGITALOCEAN", "DISCORD", "FASTLY", "FRANTECH",
            "GCORE", "GOOGLE", "HETZNER", "LEASEWEB", "LINODE", "LIQUIDWEB", "MEGA", "MELBICOM",
            "META", "ORACLE", "OVH", "SCALEWAY", "TELEGRAM", "VODAFONE", "VULTR", "YOUTUBE",
        )
    }
}
