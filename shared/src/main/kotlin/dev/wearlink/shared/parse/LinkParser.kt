package dev.wearlink.shared.parse

import dev.wearlink.shared.model.AnyTlsConfig
import dev.wearlink.shared.model.Hysteria1Config
import dev.wearlink.shared.model.Hysteria2Config
import dev.wearlink.shared.model.ProxyConfig
import dev.wearlink.shared.model.Security
import dev.wearlink.shared.model.Server
import dev.wearlink.shared.model.ShadowsocksConfig
import dev.wearlink.shared.model.Transport
import dev.wearlink.shared.model.TrojanConfig
import dev.wearlink.shared.model.TuicConfig
import dev.wearlink.shared.model.VlessConfig
import dev.wearlink.shared.model.VmessConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** Parses proxy share links (vless, vmess, trojan, ss, hysteria/hysteria2, tuic, anytls) into [Server] entries. */
object LinkParser {

    private val SUPPORTED = setOf("vless", "vmess", "trojan", "ss", "hysteria2", "hy2", "hysteria", "tuic", "anytls")
    private val KNOWN = SUPPORTED + setOf("ssr", "wireguard", "socks", "naive+https")

    /** True when [line] looks like a proxy share link (supported or not). */
    fun isProxyLink(line: String): Boolean = line.substringBefore("://", "").lowercase() in KNOWN

    fun parse(link: String, subscriptionId: String? = null): Server {
        val trimmed = link.trim()
        val id = stableId(trimmed, subscriptionId)
        val scheme = trimmed.substringBefore("://", "").lowercase()

        // Classic vmess is base64 JSON rather than a URI.
        if (scheme == "vmess") {
            decodeVmessJson(trimmed)?.let { (name, config) ->
                val label = name.ifBlank { "vmess" }
                return config.fold(
                    { Server(id, name.ifBlank { "${it.host}:${it.port}" }, trimmed, config = it, subscriptionId = subscriptionId) },
                    { Server(id, label, trimmed, error = it.message, subscriptionId = subscriptionId) },
                )
            }
        }

        val raw = try {
            RawUri.parse(trimmed)
        } catch (e: IllegalArgumentException) {
            return Server(id, trimmed.take(40), trimmed, error = e.message ?: "Некорректная ссылка", subscriptionId = subscriptionId)
        }
        val fallbackName = if (raw.host.isNotEmpty()) "${raw.host}:${raw.portSpec}" else raw.scheme
        if (raw.scheme !in SUPPORTED) {
            return Server(id, raw.fragment.ifBlank { fallbackName }, trimmed, error = "Протокол ${raw.scheme} не поддерживается", subscriptionId = subscriptionId)
        }
        return try {
            val config: ProxyConfig = when (raw.scheme) {
                "vless" -> parseVless(raw)
                "vmess" -> parseVmessUri(raw)
                "trojan" -> parseTrojan(raw)
                "ss" -> parseShadowsocks(raw, trimmed)
                "hysteria" -> parseHysteria1(raw)
                "tuic" -> parseTuic(raw)
                "anytls" -> parseAnyTls(raw)
                else -> parseHysteria2(raw)
            }
            val name = raw.fragment.ifBlank { "${config.host}:${config.port}" }
            Server(id, name, trimmed, config = config, subscriptionId = subscriptionId)
        } catch (e: UnsupportedLinkException) {
            Server(id, raw.fragment.ifBlank { fallbackName }, trimmed, error = e.message, subscriptionId = subscriptionId)
        }
    }

    private fun RawUri.port(): Int = portSpec.toIntOrNull() ?: throw UnsupportedLinkException("Некорректный порт")

    private fun parseVless(raw: RawUri): VlessConfig {
        val q = raw.query
        val uuid = raw.userInfo.ifBlank { throw UnsupportedLinkException("Нет UUID") }
        val encryption = q["encryption"].orEmpty()
        if (encryption.isNotEmpty() && encryption != "none") {
            throw UnsupportedLinkException("VLESS encryption=$encryption не поддерживается")
        }
        // "xtls-rprx-vision-udp443" is an Xray-only variant; sing-box speaks plain vision.
        val flow = q["flow"].orEmpty().let { if (it.startsWith("xtls-rprx-vision")) "xtls-rprx-vision" else it }
        return VlessConfig(
            host = raw.host,
            port = raw.port(),
            uuid = uuid,
            flow = flow,
            transport = transport(q),
            security = security(q, default = "none"),
        )
    }

    private fun parseTrojan(raw: RawUri): TrojanConfig {
        val q = raw.query
        return TrojanConfig(
            host = raw.host,
            port = raw.port(),
            password = raw.userInfo.ifBlank { throw UnsupportedLinkException("Нет пароля") },
            transport = transport(q),
            // Trojan is TLS unless the link says otherwise.
            security = security(q, default = "tls"),
        )
    }

    /** vmess:// in the URI form some panels emit (same parameters as vless). */
    private fun parseVmessUri(raw: RawUri): VmessConfig {
        val q = raw.query
        return VmessConfig(
            host = raw.host,
            port = raw.port(),
            uuid = raw.userInfo.ifBlank { throw UnsupportedLinkException("Нет UUID") },
            alterId = q["aid"]?.toIntOrNull() ?: 0,
            cipher = q["encryption"] ?: q["scy"] ?: "auto",
            transport = transport(q),
            security = security(q, default = "none"),
        )
    }

    /** Classic vmess://base64({"add":..., "port":..., "id":..., "net":..., "ps":...}). */
    private fun decodeVmessJson(link: String): Pair<String, Result<VmessConfig>>? {
        val body = link.substringAfter("://").substringBefore('#')
        val text = SubscriptionDecoder.decodeBase64(body) ?: return null
        val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        fun field(key: String) = (obj[key] as? JsonPrimitive)?.content?.trim().orEmpty()
        // Map JSON fields onto the shared query vocabulary so transport/TLS parsing is reused.
        val q = mapOf(
            "type" to field("net"),
            "headertype" to field("type"),
            "host" to field("host"),
            "path" to field("path"),
            "servicename" to field("path"),
            "security" to field("tls"),
            "sni" to field("sni"),
            "alpn" to field("alpn"),
            "fp" to field("fp"),
            "allowinsecure" to field("allowInsecure"),
        ).filterValues { it.isNotEmpty() }
        val config = runCatching {
            VmessConfig(
                host = field("add").ifEmpty { throw UnsupportedLinkException("Нет адреса сервера") },
                port = field("port").toIntOrNull() ?: throw UnsupportedLinkException("Некорректный порт"),
                uuid = field("id"),
                alterId = field("aid").toIntOrNull() ?: 0,
                cipher = field("scy").ifEmpty { "auto" },
                transport = transport(q),
                security = security(q, default = "none"),
            )
        }
        return field("ps") to config
    }

    private fun parseShadowsocks(raw: RawUri, link: String): ShadowsocksConfig {
        val q = raw.query
        val credentials: String
        val host: String
        val port: Int
        if (raw.userInfo.isEmpty()) {
            // Legacy: ss://base64(method:password@host:port)#name
            val decoded = SubscriptionDecoder.decodeBase64(link.substringAfter("://").substringBefore('#').substringBefore('?'))
                ?: throw UnsupportedLinkException("Некорректная ссылка Shadowsocks")
            credentials = decoded.substringBeforeLast('@')
            val address = decoded.substringAfterLast('@')
            host = address.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
            port = address.substringAfterLast(':').toIntOrNull() ?: throw UnsupportedLinkException("Некорректный порт")
        } else {
            // SIP002: userinfo is base64url(method:password), or plain for 2022 ciphers.
            credentials = if (':' in raw.userInfo) {
                raw.userInfo
            } else {
                SubscriptionDecoder.decodeBase64(raw.userInfo) ?: throw UnsupportedLinkException("Некорректные данные Shadowsocks")
            }
            host = raw.host
            port = raw.port()
        }
        val pluginSpec = q["plugin"].orEmpty()
        val plugin = when (val name = pluginSpec.substringBefore(';')) {
            "", "none" -> ""
            "obfs-local", "simple-obfs" -> "obfs-local"
            "v2ray-plugin" -> "v2ray-plugin"
            else -> throw UnsupportedLinkException("Плагин $name не поддерживается")
        }
        return ShadowsocksConfig(
            host = host,
            port = port,
            method = credentials.substringBefore(':').lowercase(),
            password = credentials.substringAfter(':'),
            plugin = plugin,
            pluginOpts = if (plugin.isEmpty()) "" else pluginSpec.substringAfter(';', ""),
        )
    }

    private fun parseTuic(raw: RawUri): TuicConfig {
        val q = raw.query
        return TuicConfig(
            host = raw.host,
            port = raw.port(),
            uuid = raw.userInfo.substringBefore(':').ifBlank { throw UnsupportedLinkException("Нет UUID") },
            password = raw.userInfo.substringAfter(':', "").ifEmpty { q["password"].orEmpty() },
            congestionControl = q["congestion_control"] ?: q["congestion-control"] ?: "bbr",
            udpRelayMode = q["udp_relay_mode"] ?: q["udp-relay-mode"] ?: "native",
            tls = tls(q, defaultAlpn = listOf("h3")).copy(
                disableSni = q["disable_sni"].isTrue() || q["disable-sni"].isTrue(),
            ),
        )
    }

    private fun parseAnyTls(raw: RawUri): AnyTlsConfig = AnyTlsConfig(
        host = raw.host,
        port = raw.port(),
        password = raw.userInfo.ifBlank { throw UnsupportedLinkException("Нет пароля") },
        tls = tls(raw.query),
    )

    private fun parseHysteria1(raw: RawUri): Hysteria1Config {
        val q = raw.query
        val protocol = q["protocol"].orEmpty()
        if (protocol.isNotEmpty() && protocol != "udp") {
            throw UnsupportedLinkException("Hysteria protocol=$protocol не поддерживается")
        }
        val (port, ranges) = portSpec(raw.portSpec, q["mport"])
        return Hysteria1Config(
            host = raw.host,
            port = port,
            auth = q["auth"] ?: q["auth_str"] ?: raw.userInfo,
            serverPorts = ranges,
            upMbps = q["upmbps"]?.toIntOrNull() ?: 10,
            downMbps = q["downmbps"]?.toIntOrNull() ?: 50,
            obfs = q["obfsparam"] ?: q["obfs-password"] ?: "",
            tls = tls(q),
        )
    }

    private fun parseHysteria2(raw: RawUri): Hysteria2Config {
        val q = raw.query
        val obfs = q["obfs"].orEmpty()
        if (obfs.isNotEmpty() && obfs.lowercase() != "salamander") {
            throw UnsupportedLinkException("obfs=$obfs не поддерживается")
        }
        val (port, ranges) = portSpec(raw.portSpec, q["mport"])
        return Hysteria2Config(
            host = raw.host,
            port = port,
            password = raw.userInfo,
            serverPorts = ranges,
            sni = q["sni"].orEmpty(),
            insecure = q["insecure"].isTrue() || q["allowinsecure"].isTrue(),
            obfsPassword = if (obfs.isNotEmpty()) q["obfs-password"].orEmpty() else "",
        )
    }

    // region Shared parameter parsing

    private fun transport(q: Map<String, String>): Transport =
        when (val type = q["type"].orEmpty().lowercase().ifEmpty { "tcp" }) {
            "tcp", "raw", "none" -> {
                if (q["headertype"].orEmpty().lowercase() == "http") {
                    throw UnsupportedLinkException("TCP с HTTP-обфускацией не поддерживается")
                }
                Transport.Tcp
            }
            "ws", "websocket" -> websocket(q["path"].orEmpty(), q["host"].orEmpty(), q["ed"])
            "grpc", "gun" -> Transport.Grpc(serviceName = q["servicename"] ?: q["path"].orEmpty())
            "httpupgrade" -> Transport.HttpUpgrade(path = q["path"].orEmpty().ifEmpty { "/" }, host = q["host"].orEmpty())
            "h2", "http" -> Transport.Http(
                path = q["path"].orEmpty().ifEmpty { "/" },
                host = q["host"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
            )
            "xhttp", "splithttp" -> throw UnsupportedLinkException("Транспорт xhttp не поддерживается")
            else -> throw UnsupportedLinkException("Транспорт $type не поддерживается")
        }

    private fun security(q: Map<String, String>, default: String): Security =
        when (val s = q["security"].orEmpty().lowercase().ifEmpty { default }) {
            "none" -> Security.None
            "tls", "xtls" -> tls(q)
            "reality" -> Security.Reality(
                sni = q["sni"] ?: q["peer"] ?: "",
                fingerprint = q["fp"].orEmpty().ifEmpty { "chrome" },
                publicKey = q["pbk"].orEmpty().ifEmpty { throw UnsupportedLinkException("Reality без публичного ключа (pbk)") },
                shortId = q["sid"].orEmpty(),
            )
            else -> throw UnsupportedLinkException("security=$s не поддерживается")
        }

    private fun tls(q: Map<String, String>, defaultAlpn: List<String> = emptyList()): Security.Tls {
        val alpn = q["alpn"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return Security.Tls(
            sni = q["sni"] ?: q["peer"] ?: "",
            fingerprint = q["fp"].orEmpty(),
            alpn = alpn.ifEmpty { defaultAlpn },
            insecure = q["allowinsecure"].isTrue() || q["insecure"].isTrue() || q["allow_insecure"].isTrue(),
        )
    }

    /** "443", "443,20000-30000" or "20000-30000" plus optional mport → main port and hopping ranges. */
    private fun portSpec(spec: String, mport: String?): Pair<Int, List<String>> {
        val parts = (spec.split(',') + mport.orEmpty().split(',')).map { it.trim() }.filter { it.isNotEmpty() }
        val ranges = parts.filter { it.contains('-') }.map { it.replace('-', ':') }
        val port = parts.firstOrNull { it.toIntOrNull() != null }?.toInt()
            ?: ranges.firstOrNull()?.substringBefore(':')?.toIntOrNull()
            ?: 443
        return port to ranges
    }

    // endregion

    private fun websocket(rawPath: String, host: String, edParam: String?): Transport.WebSocket {
        var path = rawPath.ifEmpty { "/" }
        var earlyData = edParam?.toIntOrNull() ?: 0
        // Xray encodes early data as "/path?ed=2048"; sing-box wants it as separate fields.
        val queryStart = path.indexOf('?')
        if (queryStart >= 0) {
            val params = path.substring(queryStart + 1).split('&')
            val ed = params.firstOrNull { it.startsWith("ed=") }?.removePrefix("ed=")?.toIntOrNull()
            if (ed != null) {
                earlyData = ed
                val rest = params.filterNot { it.startsWith("ed=") }
                path = path.substring(0, queryStart) + if (rest.isEmpty()) "" else "?" + rest.joinToString("&")
            }
        }
        return Transport.WebSocket(
            path = path,
            host = host,
            maxEarlyData = earlyData,
            earlyDataHeader = if (earlyData > 0) "Sec-WebSocket-Protocol" else "",
        )
    }

    private fun String?.isTrue() = this == "1" || this.equals("true", ignoreCase = true)

    private fun stableId(link: String, subscriptionId: String?): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("${subscriptionId.orEmpty()}|$link".toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}

class UnsupportedLinkException(message: String) : Exception(message)

/**
 * Lenient URI splitter. java.net.URI rejects the unescaped spaces, emoji and cyrillic
 * that providers routinely put into fragments, so the pieces are cut by hand.
 */
internal data class RawUri(
    val scheme: String,
    val userInfo: String,
    val host: String,
    val portSpec: String,
    val path: String,
    val query: Map<String, String>,
    val fragment: String,
) {
    companion object {
        fun parse(link: String): RawUri {
            val schemeEnd = link.indexOf("://")
            require(schemeEnd > 0) { "Нет схемы ссылки" }
            val scheme = link.substring(0, schemeEnd).lowercase()
            var rest = link.substring(schemeEnd + 3)

            var fragment = ""
            rest.indexOf('#').takeIf { it >= 0 }?.let {
                fragment = percentDecode(rest.substring(it + 1)).trim()
                rest = rest.substring(0, it)
            }
            var queryString = ""
            rest.indexOf('?').takeIf { it >= 0 }?.let {
                queryString = rest.substring(it + 1)
                rest = rest.substring(0, it)
            }

            val at = rest.lastIndexOf('@')
            val userInfo = if (at >= 0) percentDecode(rest.substring(0, at)) else ""
            var hostPart = rest.substring(at + 1)
            var path = ""
            hostPart.indexOf('/').takeIf { it >= 0 }?.let {
                path = hostPart.substring(it)
                hostPart = hostPart.substring(0, it)
            }

            val host: String
            val portSpec: String
            if (hostPart.startsWith("[")) {
                val close = hostPart.indexOf(']')
                require(close > 0) { "Некорректный IPv6-адрес" }
                host = hostPart.substring(1, close)
                portSpec = hostPart.substring(close + 1).removePrefix(":")
            } else {
                host = hostPart.substringBefore(':')
                portSpec = hostPart.substringAfter(':', "")
            }
            require(host.isNotEmpty()) { "Нет адреса сервера" }

            val query = queryString.split('&')
                .filter { it.isNotEmpty() }
                .associate { pair ->
                    val key = percentDecode(pair.substringBefore('=')).lowercase()
                    key to percentDecode(pair.substringAfter('=', ""))
                }
            return RawUri(scheme, userInfo, host, portSpec, path, query, fragment)
        }

        /** Decodes %XX sequences as UTF-8. Unlike URLDecoder, keeps '+' literal. */
        fun percentDecode(value: String): String {
            if ('%' !in value) return value
            val out = ByteArrayOutputStream(value.length)
            var i = 0
            while (i < value.length) {
                if (value[i] == '%' && i + 2 <= value.lastIndex) {
                    val byte = value.substring(i + 1, i + 3).toIntOrNull(16)
                    if (byte != null) {
                        out.write(byte)
                        i += 3
                        continue
                    }
                }
                // Copy the literal run up to the next '%' in one go so surrogate pairs stay intact.
                val next = value.indexOf('%', i + 1).let { if (it < 0) value.length else it }
                out.write(value.substring(i, next).toByteArray(Charsets.UTF_8))
                i = next
            }
            return out.toString(Charsets.UTF_8)
        }
    }
}
