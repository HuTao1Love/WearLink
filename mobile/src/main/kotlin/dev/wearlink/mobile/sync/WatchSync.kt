package dev.wearlink.mobile.sync

import android.content.Context
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import dev.wearlink.core.data.FetchedSubscription
import dev.wearlink.shared.wire.ImportRequest
import dev.wearlink.shared.wire.ImportResult
import dev.wearlink.shared.wire.WireProtocol
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.util.UUID

sealed interface WatchStatus {
    data object Checking : WatchStatus
    data object NoWearOs : WatchStatus
    data object NotConnected : WatchStatus
    data class NoApp(val watchName: String) : WatchStatus
    data class Ready(val watchName: String) : WatchStatus
}

/** Sends links and subscriptions to the watch over the Wearable Data Layer. */
object WatchSync {

    /** Data Layer items are capped at 100 KB; leave room for the rest of the request. */
    private const val MAX_BODY_BYTES = 80_000

    private val _status = MutableStateFlow<WatchStatus>(WatchStatus.Checking)
    val status: StateFlow<WatchStatus> = _status.asStateFlow()

    private val _results = MutableSharedFlow<ImportResult>(extraBufferCapacity = 8)
    val results: SharedFlow<ImportResult> = _results.asSharedFlow()

    fun init(context: Context) {
        runCatching {
            Wearable.getMessageClient(context).addListener { event ->
                if (event.path == WireProtocol.ACK_PATH) {
                    runCatching {
                        WireProtocol.json.decodeFromString(ImportResult.serializer(), event.data.decodeToString())
                    }.onSuccess { _results.tryEmit(it) }
                }
            }
        }
    }

    suspend fun refreshStatus(context: Context) {
        _status.value = try {
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            if (nodes.isEmpty()) {
                WatchStatus.NotConnected
            } else {
                val capable = Wearable.getCapabilityClient(context)
                    .getCapability(WireProtocol.CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE)
                    .await()
                    .nodes
                val withApp = capable.firstOrNull()
                if (withApp != null) WatchStatus.Ready(withApp.displayName) else WatchStatus.NoApp(nodes.first().displayName)
            }
        } catch (_: ApiException) {
            WatchStatus.NoWearOs
        } catch (_: Exception) {
            WatchStatus.NotConnected
        }
    }

    /**
     * Queues [text] for the watch. The Data Layer keeps the item until the watch connects,
     * so this succeeds even when the watch is momentarily out of range.
     */
    suspend fun send(context: Context, text: String, fetched: FetchedSubscription? = null): String {
        val body = fetched?.body?.takeIf { it.toByteArray().size <= MAX_BODY_BYTES }
        val request = ImportRequest(
            id = UUID.randomUUID().toString(),
            text = text.trim(),
            subscriptionBody = body,
            subscriptionHeaders = if (body != null) fetched.headers.filterKeys { it.lowercase() in FORWARDED_HEADERS } else emptyMap(),
        )
        val dataRequest = PutDataMapRequest.create(WireProtocol.IMPORT_PATH_PREFIX + request.id).apply {
            dataMap.putString(WireProtocol.KEY_REQUEST, WireProtocol.json.encodeToString(ImportRequest.serializer(), request))
        }.asPutDataRequest().setUrgent()
        try {
            Wearable.getDataClient(context).putDataItem(dataRequest).await()
        } catch (e: ApiException) {
            throw IllegalStateException("Wear OS недоступен на телефоне (${e.statusCode})")
        }
        return request.id
    }

    /** Tells every connected watch with WearLink to update itself. Returns how many were asked. */
    suspend fun requestWatchUpdate(context: Context): Int = try {
        val nodes = Wearable.getCapabilityClient(context)
            .getCapability(WireProtocol.CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE)
            .await()
            .nodes
        nodes.forEach { node ->
            Wearable.getMessageClient(context).sendMessage(node.id, WireProtocol.UPDATE_PATH, ByteArray(0)).await()
        }
        nodes.size
    } catch (_: Exception) {
        0
    }

    private val FORWARDED_HEADERS = setOf(
        "profile-title",
        "subscription-userinfo",
        "profile-update-interval",
        "content-disposition",
    )
}
