package dev.wearlink.core.data

import android.net.Uri
import dev.wearlink.shared.model.Subscription
import dev.wearlink.shared.parse.ImportClassifier
import dev.wearlink.shared.parse.ImportInput
import dev.wearlink.shared.parse.LinkParser
import dev.wearlink.shared.parse.SubscriptionDecoder
import java.util.UUID

data class ImportOutcome(
    val added: Int,
    val title: String?,
    /** Set when the input was a subscription; lets the phone forward the body to the watch. */
    val subscriptionUrl: String? = null,
    val fetched: FetchedSubscription? = null,
)

/** Single entry point for pasted text, shares, QR codes, deep links and Data Layer imports. */
class Importer(private val store: Store) {

    suspend fun import(text: String, prefetched: FetchedSubscription? = null): ImportOutcome =
        when (val input = ImportClassifier.classify(text)) {
            is ImportInput.Invalid -> throw ImportException(input.reason)
            is ImportInput.Links -> addLinks(input.links)
            is ImportInput.SubscriptionUrl -> addSubscription(input.url, prefetched)
        }

    private suspend fun addLinks(links: List<String>): ImportOutcome {
        val parsed = links.map { LinkParser.parse(it) }.distinctBy { it.link }
        var added = 0
        store.update { data ->
            val known = data.servers.filter { it.subscriptionId == null }.map { it.link }.toSet()
            val fresh = parsed.filter { it.link !in known }
            added = fresh.size
            val updated = data.copy(servers = data.servers + fresh)
            // A freshly added single link becomes the default, which is what the user expects.
            val newDefault = fresh.singleOrNull()?.takeIf { it.isUsable }
            if (newDefault != null) updated.copy(selectedServerId = newDefault.id) else updated.withValidSelection()
        }
        if (parsed.none { it.isUsable }) {
            throw ImportException(parsed.firstNotNullOfOrNull { it.error } ?: "Нет поддерживаемых серверов")
        }
        return ImportOutcome(added, parsed.singleOrNull()?.name)
    }

    /**
     * Downloads [url] and replaces the subscription's servers. When the download fails and the
     * phone already sent the body ([prefetched]), that body is used instead.
     */
    suspend fun addSubscription(url: String, prefetched: FetchedSubscription? = null): ImportOutcome {
        val fetched = try {
            SubscriptionFetcher.fetch(url)
        } catch (e: ImportException) {
            prefetched ?: run {
                store.update { data ->
                    data.copy(subscriptions = data.subscriptions.map { if (it.url == url) it.copy(lastError = e.message) else it })
                }
                throw e
            }
        }
        val decoded = SubscriptionDecoder.decode(fetched.body, fetched.headers)
        if (decoded.links.isEmpty()) throw ImportException("В подписке нет серверов")

        var title = ""
        var added = 0
        store.update { data ->
            val existing = data.subscriptions.firstOrNull { it.url == url }
            val id = existing?.id ?: UUID.randomUUID().toString().take(8)
            val info = decoded.info
            title = info.title ?: existing?.name ?: Uri.parse(url).host ?: url
            val subscription = Subscription(
                id = id,
                url = url,
                name = title,
                updatedAt = System.currentTimeMillis(),
                upload = info.upload,
                download = info.download,
                total = info.total,
                expire = info.expire,
                updateIntervalHours = info.updateIntervalHours,
            )
            val servers = decoded.links.map { LinkParser.parse(it, id) }.distinctBy { it.id }
            added = servers.size
            val subscriptions = if (existing != null) {
                data.subscriptions.map { if (it.id == id) subscription else it }
            } else {
                data.subscriptions + subscription
            }
            data.copy(
                subscriptions = subscriptions,
                servers = data.servers.filterNot { it.subscriptionId == id } + servers,
            ).withValidSelection(previous = data.servers.firstOrNull { it.id == data.selectedServerId })
        }
        return ImportOutcome(added, title, url, fetched)
    }

    suspend fun refresh(subscriptionId: String): ImportOutcome {
        val url = store.current.subscriptions.firstOrNull { it.id == subscriptionId }?.url
            ?: throw ImportException("Подписка не найдена")
        return addSubscription(url)
    }

    /** Returns the number of subscriptions that failed to refresh. */
    suspend fun refreshAll(): Int = store.current.subscriptions.count { subscription ->
        runCatching { refresh(subscription.id) }.isFailure
    }
}
