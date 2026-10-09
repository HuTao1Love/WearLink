package dev.wearlink.shared.parse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.URLEncoder
import java.util.Base64

/**
 * Subscriptions that come as full client configs instead of share links (Remnawave/Marzban
 * templates and the like): a sing-box config, or an Xray config / array of configs.
 * Both are turned into links so the rest of the app keeps working with one format.
 */
object JsonSubscription {

    /** Outbound types sing-box can run that we pass through untouched. */
    private val SING_BOX_PROXY_TYPES = setOf("vless", "vmess", "trojan", "shadowsocks", "hysteria2", "hysteria", "tuic", "anytls")

    /** Scheme of our own "link" that carries a ready sing-box outbound (see [LinkParser]). */
    const val SING_BOX_SCHEME = "sbox"

    private val json = Json { ignoreUnknownKeys = true }

    fun isJson(body: String): Boolean = body.trimStart().let { it.startsWith("{") || it.startsWith("[") }

    /** True for a sing-box config, i.e. outbounds with "type" fields. */
    fun isSingBox(body: String): Boolean = parse(body)?.let { root ->
        (root as? JsonObject)?.get("outbounds")?.let { outbounds ->
            (outbounds as? JsonArray)?.any { (it as? JsonObject)?.get("type") != null }
        }
    } == true

    /** Links for every proxy in a sing-box or Xray JSON body; empty when it is neither. */
    fun links(body: String): List<String> {
        val root = parse(body) ?: return emptyList()
        return if (isSingBox(body)) singBoxLinks(root as JsonObject) else xrayLinks(root)
    }

    private fun parse(body: String): JsonElement? = runCatching { json.parseToJsonElement(body.trim()) }.getOrNull()

    // region sing-box

    private fun singBoxLinks(root: JsonObject): List<String> =
        (root["outbounds"] as? JsonArray).orEmpty().mapNotNull { element ->
            val outbound = element as? JsonObject ?: return@mapNotNull null
            if (outbound.str("type") !in SING_BOX_PROXY_TYPES) return@mapNotNull null
            val name = outbound.str("tag") ?: outbound.str("server") ?: return@mapNotNull null
            // References to the provider's own outbounds and DNS servers do not exist in our config.
            val cleaned = JsonObject(outbound - setOf("tag", "detour", "domain_resolver"))
            val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(cleaned.toString().toByteArray())
            "$SING_BOX_SCHEME://$payload#${encode(name)}"
        }

    // endregion

    // region Xray

    private fun xrayLinks(root: JsonElement): List<String> {
        val configs = when (root) {
            is JsonArray -> root.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(root)
            else -> emptyList()
        }
        return configs.flatMap { config ->
            val remarks = config.str("remarks") ?: "Xray"
            val proxies = (config["outbounds"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .filter { it.str("protocol") in setOf("vless", "vmess", "trojan", "shadowsocks", "hysteria") }
            proxies.mapIndexedNotNull { index, outbound ->
                // Several outbounds in one config are variants of the same location; tell them apart.
                val network = (outbound["streamSettings"] as? JsonObject)?.str("network")
                val name = if (proxies.size == 1) remarks else "$remarks · ${network ?: outbound.str("protocol")} ${index + 1}"
                runCatching { xrayToLink(outbound, name) }.getOrNull()
            }
        }
    }

    private fun xrayToLink(outbound: JsonObject, name: String): String? {
        val protocol = outbound.str("protocol") ?: return null
        val settings = outbound["settings"] as? JsonObject ?: JsonObject(emptyMap())
        val stream = outbound["streamSettings"] as? JsonObject ?: JsonObject(emptyMap())
        // Classic Xray nests the server in vnext/servers; newer configs put it straight in settings.
        val server = ((settings["vnext"] ?: settings["servers"]) as? JsonArray)?.firstOrNull() as? JsonObject ?: settings
        val user = (server["users"] as? JsonArray)?.firstOrNull() as? JsonObject ?: server
        val host = server.str("address") ?: return null
        val port = server.str("port") ?: return null
        val authority = if (':' in host) "[$host]:$port" else "$host:$port"
        val fragment = "#" + encode(name)

        return when (protocol) {
            "vless" -> "vless://${encode(user.str("id") ?: return null)}@$authority?" +
                query(streamParams(stream) + mapOf("flow" to user.str("flow"), "encryption" to (user.str("encryption") ?: "none"))) + fragment
            "vmess" -> "vmess://${encode(user.str("id") ?: return null)}@$authority?" +
                query(streamParams(stream) + mapOf("encryption" to (user.str("security") ?: "auto"))) + fragment
            "trojan" -> "trojan://${encode(server.str("password") ?: return null)}@$authority?" + query(streamParams(stream)) + fragment
            "shadowsocks" -> {
                val credentials = "${server.str("method") ?: return null}:${server.str("password") ?: return null}"
                "ss://${Base64.getUrlEncoder().withoutPadding().encodeToString(credentials.toByteArray())}@$authority$fragment"
            }
            "hysteria" -> {
                val hy = stream["hysteriaSettings"] as? JsonObject
                val tls = stream["tlsSettings"] as? JsonObject
                val auth = hy?.str("auth") ?: settings.str("auth") ?: return null
                "hysteria2://${encode(auth)}@$authority/?" + query(
                    mapOf(
                        "sni" to tls?.str("serverName"),
                        "insecure" to if (tls?.str("allowInsecure") == "true") "1" else null,
                    ),
                ) + fragment
            }
            else -> null
        }
    }

    /** Xray streamSettings → share-link query parameters understood by [LinkParser]. */
    private fun streamParams(stream: JsonObject): Map<String, String?> {
        val network = stream.str("network") ?: "tcp"
        val security = stream.str("security") ?: "none"
        val params = mutableMapOf<String, String?>("type" to network, "security" to security)
        when (security) {
            "reality" -> (stream["realitySettings"] as? JsonObject)?.let {
                params["sni"] = it.str("serverName")
                params["fp"] = it.str("fingerprint")
                params["pbk"] = it.str("publicKey") ?: it.str("password")
                params["sid"] = it.str("shortId")
            }
            "tls" -> (stream["tlsSettings"] as? JsonObject)?.let {
                params["sni"] = it.str("serverName")
                params["fp"] = it.str("fingerprint")
                params["alpn"] = (it["alpn"] as? JsonArray)?.mapNotNull { a -> (a as? JsonPrimitive)?.contentOrNull }?.joinToString(",")
                if (it.str("allowInsecure") == "true") params["allowInsecure"] = "1"
            }
        }
        when (network) {
            "ws" -> (stream["wsSettings"] as? JsonObject)?.let {
                params["path"] = it.str("path")
                params["host"] = it.str("host") ?: (it["headers"] as? JsonObject)?.str("Host")
            }
            "grpc" -> (stream["grpcSettings"] as? JsonObject)?.let { params["serviceName"] = it.str("serviceName") }
            "httpupgrade" -> (stream["httpupgradeSettings"] as? JsonObject)?.let {
                params["path"] = it.str("path")
                params["host"] = it.str("host")
            }
            "xhttp", "splithttp" -> (stream["xhttpSettings"] as? JsonObject)?.let { params["path"] = it.str("path") }
            "tcp", "raw" -> ((stream["tcpSettings"] ?: stream["rawSettings"]) as? JsonObject)
                ?.let { (it["header"] as? JsonObject)?.str("type") }
                ?.let { params["headerType"] = it }
        }
        return params
    }

    // endregion

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    private fun query(params: Map<String, String?>): String =
        params.filterValues { !it.isNullOrEmpty() }.entries.joinToString("&") { (k, v) -> "$k=${encode(v!!)}" }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
}
