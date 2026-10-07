package dev.wearlink.wear.sync

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dev.wearlink.core.WearLinkCore
import dev.wearlink.core.data.FetchedSubscription
import dev.wearlink.core.update.UpdateWorker
import dev.wearlink.shared.wire.ImportRequest
import dev.wearlink.shared.wire.ImportResult
import dev.wearlink.shared.wire.WireProtocol
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await

/** Receives links and subscriptions the phone queued on the Data Layer, plus the phone's "update now" request. */
class ImportListenerService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        // The buffer is only valid during this call, so copy what we need first.
        val pending = events
            .filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path.orEmpty().startsWith(WireProtocol.IMPORT_PATH_PREFIX) }
            .mapNotNull { event ->
                val raw = DataMapItem.fromDataItem(event.dataItem).dataMap.getString(WireProtocol.KEY_REQUEST) ?: return@mapNotNull null
                event.dataItem.uri to raw
            }
        if (pending.isEmpty()) return

        WearLinkCore.init(application)
        // Runs on a binder background thread; blocking keeps the service alive until we are done.
        runBlocking {
            for ((uri, raw) in pending) {
                val request = runCatching { WireProtocol.json.decodeFromString(ImportRequest.serializer(), raw) }.getOrNull()
                    ?: continue
                val result = try {
                    val prefetched = request.subscriptionBody?.let { FetchedSubscription(it, request.subscriptionHeaders) }
                    val outcome = WearLinkCore.importer.import(request.text, prefetched)
                    ImportResult(request.id, added = outcome.added, title = outcome.title)
                } catch (e: Exception) {
                    Log.w(TAG, "import failed", e)
                    ImportResult(request.id, error = e.message ?: e.javaClass.simpleName)
                }
                runCatching {
                    uri.host?.let { node ->
                        Wearable.getMessageClient(this@ImportListenerService)
                            .sendMessage(node, WireProtocol.ACK_PATH, WireProtocol.json.encodeToString(ImportResult.serializer(), result).toByteArray())
                            .await()
                    }
                    Wearable.getDataClient(this@ImportListenerService).deleteDataItems(uri).await()
                }.onFailure { Log.w(TAG, "ack failed", it) }
            }
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == WireProtocol.UPDATE_PATH) UpdateWorker.enqueue(this)
    }

    private companion object {
        const val TAG = "WearLinkImport"
    }
}
