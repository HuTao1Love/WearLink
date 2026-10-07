package dev.wearlink.core.data

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

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .build()
    }

    suspend fun fetch(url: String): FetchedSubscription = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
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
