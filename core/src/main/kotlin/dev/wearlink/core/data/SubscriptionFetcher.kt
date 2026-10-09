package dev.wearlink.core.data

import dev.wearlink.shared.parse.JsonSubscription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

data class FetchedSubscription(val body: String, val headers: Map<String, String>)

object SubscriptionFetcher {

    /** Panels such as Marzban and Remnawave return a plain base64 link list for v2rayN. */
    private const val USER_AGENT = "v2rayN/7.10.0"

    /** Asked for when a panel answers with an Xray JSON config: its sing-box config runs as is. */
    const val SING_BOX_USER_AGENT = "sing-box 1.14.2"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Downloads [url]. Some panels (e.g. proxen, Remnawave templates) send a full Xray JSON config
     * to v2rayN; then we ask again as sing-box and keep that answer if it is a sing-box config.
     */
    suspend fun fetch(url: String): FetchedSubscription {
        val first = fetch(url, USER_AGENT)
        if (!JsonSubscription.isJson(first.body) || JsonSubscription.isSingBox(first.body)) return first
        val singBox = runCatching { fetch(url, SING_BOX_USER_AGENT) }.getOrNull()
        return if (singBox != null && JsonSubscription.isSingBox(singBox.body)) singBox else first
    }

    private suspend fun fetch(url: String, userAgent: String): FetchedSubscription = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "*/*")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw ImportException("Сервер подписки ответил ${response.code}")
                val headers = response.headers.names().associateWith { response.header(it).orEmpty() }
                FetchedSubscription(response.body.string(), headers)
            }
        } catch (e: IOException) {
            throw ImportException("Не удалось скачать подписку: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: IllegalArgumentException) {
            throw ImportException("Некорректный адрес подписки")
        }
    }
}

class ImportException(message: String) : Exception(message)
