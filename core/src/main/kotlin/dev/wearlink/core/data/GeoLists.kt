package dev.wearlink.core.data

import android.content.Context
import android.util.Log
import dev.wearlink.shared.geo.GeoDat
import dev.wearlink.shared.model.ListRouting
import dev.wearlink.shared.singbox.RuleSets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

@Serializable
data class GeoCategory(val name: String, val count: Int)

@Serializable
data class GeoCatalog(
    val sites: List<GeoCategory>,
    val ips: List<GeoCategory>,
    val updatedAt: Long,
)

/**
 * zkeen domain/IP lists: downloads the .dat files, converts every category into a sing-box
 * rule-set file ("site-<name>.json", "ip-<name>.json") and keeps a catalog for the UI.
 */
object GeoLists {

    const val SITES_URL = "https://github.com/jameszeroX/zkeen-domains/releases/latest/download/zkeen.dat"
    const val IPS_URL = "https://github.com/jameszeroX/zkeen-ip/releases/latest/download/zkeenip.dat"

    /** Placeholder or "direct" categories that make no sense to send through the proxy. */
    private val HIDDEN = setOf("CN", "BYPASS", "RU")
    private const val STALE_MS = 24 * 60 * 60 * 1000L
    private const val TAG = "WearLinkGeo"

    private val json = Json { ignoreUnknownKeys = true }
    private val client by lazy { SubscriptionFetcher.client.newBuilder().callTimeout(3, TimeUnit.MINUTES).build() }

    private val _catalog = MutableStateFlow<GeoCatalog?>(null)
    val catalog: StateFlow<GeoCatalog?> = _catalog.asStateFlow()

    private fun dir(context: Context) = File(context.filesDir, "geo")

    fun load(context: Context) {
        _catalog.value = runCatching {
            json.decodeFromString(GeoCatalog.serializer(), File(dir(context), "catalog.json").readText())
        }.getOrNull()
    }

    fun isStale(): Boolean = catalog.value?.let { System.currentTimeMillis() - it.updatedAt > STALE_MS } ?: true

    /** Downloads both lists and regenerates all rule-set files. Throws [ImportException] on failure. */
    suspend fun update(context: Context): GeoCatalog = withContext(Dispatchers.IO) {
        val sitesBytes = download(SITES_URL)
        val ipsBytes = download(IPS_URL)
        val sites = try {
            GeoDat.parseSites(sitesBytes)
        } catch (e: IllegalArgumentException) {
            throw ImportException("Список доменов повреждён")
        }
        val ips = try {
            GeoDat.parseIps(ipsBytes)
        } catch (e: IllegalArgumentException) {
            throw ImportException("Список IP повреждён")
        }

        // Write into a fresh directory and swap, so a running core never sees half-written files.
        val target = dir(context)
        val staging = File(context.filesDir, "geo.new").apply { deleteRecursively(); mkdirs() }
        sites.forEach { (name, rules) -> File(staging, "site-${name.lowercase()}.json").writeText(GeoDat.siteRuleSet(rules)) }
        ips.forEach { (name, cidrs) -> File(staging, "ip-${name.lowercase()}.json").writeText(GeoDat.ipRuleSet(cidrs)) }
        val catalog = GeoCatalog(
            sites = sites.filterKeys { it !in HIDDEN }.map { (name, rules) -> GeoCategory(name, rules.size) },
            ips = ips.filterKeys { it !in HIDDEN }.map { (name, cidrs) -> GeoCategory(name, cidrs.size) },
            updatedAt = System.currentTimeMillis(),
        )
        File(staging, "catalog.json").writeText(json.encodeToString(GeoCatalog.serializer(), catalog))
        val old = File(context.filesDir, "geo.old").apply { deleteRecursively() }
        target.renameTo(old)
        check(staging.renameTo(target)) { "не удалось сохранить списки" }
        old.deleteRecursively()
        _catalog.value = catalog
        catalog
    }

    /** Rule-set files for the selected categories; empty when the lists are not downloaded yet. */
    fun ruleSets(context: Context, lists: ListRouting): RuleSets {
        val catalog = catalog.value ?: return RuleSets()
        val base = dir(context)
        fun pick(prefix: String, available: List<GeoCategory>, selected: Set<String>) =
            available.filter { it.name in selected }
                .associate { "$prefix-${it.name.lowercase()}" to File(base, "$prefix-${it.name.lowercase()}.json") }
                .filterValues { it.exists() }
                .mapValues { it.value.absolutePath }
        return RuleSets(
            sites = pick("site", catalog.sites, lists.sites),
            ips = pick("ip", catalog.ips, lists.ips),
        )
    }

    private fun download(url: String): ByteArray = try {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw ImportException("Списки: сервер ответил ${response.code}")
            response.body.bytes()
        }
    } catch (e: java.io.IOException) {
        Log.w(TAG, "download failed: $url", e)
        throw ImportException("Не удалось скачать списки: ${e.message ?: e.javaClass.simpleName}")
    }

    /** Human-friendly names for the known categories. */
    fun label(name: String): String = LABELS[name] ?: name.lowercase().replaceFirstChar { it.uppercase() }

    private val LABELS = mapOf(
        "DOMAINS" to "Заблокированные сайты",
        "OTHER" to "Прочие недоступные сайты",
        "POLITIC" to "СМИ и политика",
        "YOUTUBE" to "YouTube",
        "TELEGRAM" to "Telegram",
        "META" to "Meta (Instagram, WhatsApp)",
        "DISCORD" to "Discord",
        "GOOGLE" to "Google",
        "AMAZON" to "Amazon (AWS)",
        "AZURE" to "Microsoft Azure",
        "CLOUDFLARE" to "Cloudflare",
        "DIGITALOCEAN" to "DigitalOcean",
        "OVH" to "OVH",
        "CDN77" to "CDN77",
        "GCORE" to "G-Core",
        "MEGA" to "MEGA",
    )
}
