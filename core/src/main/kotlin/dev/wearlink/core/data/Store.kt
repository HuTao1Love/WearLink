package dev.wearlink.core.data

import android.content.Context
import android.util.Log
import androidx.core.util.AtomicFile
import dev.wearlink.shared.model.AppRouting
import dev.wearlink.shared.model.Server
import dev.wearlink.shared.model.Subscription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class AppData(
    val subscriptions: List<Subscription> = emptyList(),
    val servers: List<Server> = emptyList(),
    val selectedServerId: String? = null,
    val routing: AppRouting = AppRouting(),
) {
    val selectedServer: Server?
        get() = servers.firstOrNull { it.id == selectedServerId && it.isUsable }

    fun serversOf(subscriptionId: String?): List<Server> = servers.filter { it.subscriptionId == subscriptionId }
}

/** Everything the app persists, kept in one JSON file and exposed as a [StateFlow]. */
class Store(context: Context) {

    private val file = AtomicFile(File(context.filesDir, "wearlink.json"))
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private val _data = MutableStateFlow(load())
    val data: StateFlow<AppData> = _data.asStateFlow()

    val current: AppData get() = _data.value

    suspend fun update(transform: (AppData) -> AppData): AppData = mutex.withLock {
        val updated = transform(_data.value)
        if (updated != _data.value) {
            withContext(Dispatchers.IO) { save(updated) }
            _data.value = updated
        }
        updated
    }

    suspend fun select(serverId: String) = update { it.copy(selectedServerId = serverId) }

    suspend fun setRouting(routing: AppRouting) = update { it.copy(routing = routing) }

    suspend fun deleteServer(serverId: String) = update { data ->
        data.copy(servers = data.servers.filterNot { it.id == serverId }).withValidSelection()
    }

    suspend fun deleteSubscription(subscriptionId: String) = update { data ->
        data.copy(
            subscriptions = data.subscriptions.filterNot { it.id == subscriptionId },
            servers = data.servers.filterNot { it.subscriptionId == subscriptionId },
        ).withValidSelection()
    }

    private fun load(): AppData = try {
        if (file.baseFile.exists()) json.decodeFromString(AppData.serializer(), file.readFully().decodeToString()) else AppData()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to read store, starting empty", e)
        AppData()
    }

    private fun save(data: AppData) {
        val stream = file.startWrite()
        try {
            stream.write(json.encodeToString(AppData.serializer(), data).toByteArray())
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    private companion object {
        const val TAG = "WearLinkStore"
    }
}

/** Keeps the current selection if it still exists, otherwise falls back to the first usable server. */
internal fun AppData.withValidSelection(previous: Server? = null): AppData {
    if (selectedServer != null) return this
    val replacement = previous?.let { old ->
        servers.firstOrNull { it.isUsable && it.subscriptionId == old.subscriptionId && it.name == old.name }
    } ?: servers.firstOrNull { it.isUsable }
    return copy(selectedServerId = replacement?.id)
}
