package dev.wearlink.core.vpn

import dev.wearlink.core.data.SubscriptionFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

/** Asks a public service which IP our traffic comes from. Our own package always uses the tunnel. */
object IpCheck {

    suspend fun check(): String = withContext(Dispatchers.IO) {
        runCatching {
            val body = get("https://ipinfo.io/json")
            val json = Json.parseToJsonElement(body).jsonObject
            val ip = json["ip"]!!.jsonPrimitive.content
            val country = json["country"]?.jsonPrimitive?.content
            if (country != null) "$ip · $country" else ip
        }.recoverCatching {
            get("https://api.ipify.org").trim()
        }.getOrElse { "Ошибка: ${it.message ?: it.javaClass.simpleName}" }
    }

    private fun get(url: String): String =
        SubscriptionFetcher.client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            response.body.string()
        }
}
