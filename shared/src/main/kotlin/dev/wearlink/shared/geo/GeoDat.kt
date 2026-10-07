package dev.wearlink.shared.geo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.InetAddress

/** Domains of one geosite category, split by how sing-box matches them. */
data class SiteRules(
    val full: List<String> = emptyList(),
    val suffix: List<String> = emptyList(),
    val keyword: List<String> = emptyList(),
    val regex: List<String> = emptyList(),
) {
    val size: Int get() = full.size + suffix.size + keyword.size + regex.size
}

/**
 * Reads V2Ray/Xray geosite.dat and geoip.dat (protobuf GeoSiteList / GeoIPList), the format
 * of zkeen.dat and zkeenip.dat, and turns categories into sing-box rule-set sources.
 */
object GeoDat {

    /** Category name (upper case, as stored) → domains. */
    fun parseSites(bytes: ByteArray): Map<String, SiteRules> {
        val result = linkedMapOf<String, SiteRules>()
        ProtoReader(bytes).forEachField { field, entry ->
            if (field != 1) return@forEachField
            var code = ""
            val full = mutableListOf<String>()
            val suffix = mutableListOf<String>()
            val keyword = mutableListOf<String>()
            val regex = mutableListOf<String>()
            ProtoReader(entry.bytes()).forEachField { f, value ->
                when (f) {
                    1 -> code = value.string()
                    2 -> {
                        var type = 0L
                        var domain = ""
                        ProtoReader(value.bytes()).forEachField { df, dv ->
                            when (df) {
                                1 -> type = dv.varint()
                                2 -> domain = dv.string()
                            }
                        }
                        // Domain.Type: Plain(keyword)=0, Regex=1, RootDomain=2, Full=3.
                        when (type) {
                            0L -> keyword += domain
                            1L -> regex += domain
                            2L -> suffix += domain
                            3L -> full += domain
                        }
                    }
                }
            }
            if (code.isNotEmpty()) result[code.uppercase()] = SiteRules(full, suffix, keyword, regex)
        }
        return result
    }

    /** Category name (upper case) → CIDRs like "1.2.3.0/24" or "2001:db8::/32". */
    fun parseIps(bytes: ByteArray): Map<String, List<String>> {
        val result = linkedMapOf<String, List<String>>()
        ProtoReader(bytes).forEachField { field, entry ->
            if (field != 1) return@forEachField
            var code = ""
            val cidrs = mutableListOf<String>()
            ProtoReader(entry.bytes()).forEachField { f, value ->
                when (f) {
                    1 -> code = value.string()
                    2 -> {
                        var ip = ByteArray(0)
                        var prefix = 0L
                        ProtoReader(value.bytes()).forEachField { cf, cv ->
                            when (cf) {
                                1 -> ip = cv.bytes()
                                2 -> prefix = cv.varint()
                            }
                        }
                        if (ip.size == 4 || ip.size == 16) {
                            cidrs += "${InetAddress.getByAddress(ip).hostAddress}/$prefix"
                        }
                    }
                }
            }
            if (code.isNotEmpty()) result[code.uppercase()] = cidrs
        }
        return result
    }

    private val json = Json { prettyPrint = false }

    fun siteRuleSet(rules: SiteRules): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("version", 3)
            putJsonArray("rules") {
                addJsonObject {
                    if (rules.full.isNotEmpty()) putJsonArray("domain") { rules.full.forEach { add(it) } }
                    if (rules.suffix.isNotEmpty()) putJsonArray("domain_suffix") { rules.suffix.forEach { add(it) } }
                    if (rules.keyword.isNotEmpty()) putJsonArray("domain_keyword") { rules.keyword.forEach { add(it) } }
                    if (rules.regex.isNotEmpty()) putJsonArray("domain_regex") { rules.regex.forEach { add(it) } }
                }
            }
        },
    )

    fun ipRuleSet(cidrs: List<String>): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("version", 3)
            putJsonArray("rules") {
                addJsonObject { putJsonArray("ip_cidr") { cidrs.forEach { add(it) } } }
            }
        },
    )
}

/** Just enough protobuf to walk varint and length-delimited fields. */
internal class ProtoReader(private val data: ByteArray) {

    class Value(private val raw: ByteArray?, private val number: Long) {
        fun bytes(): ByteArray = raw ?: ByteArray(0)
        fun string(): String = String(bytes(), Charsets.UTF_8)
        fun varint(): Long = number
    }

    private var pos = 0

    fun forEachField(action: (field: Int, value: Value) -> Unit) {
        while (pos < data.size) {
            val key = readVarint()
            val field = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> action(field, Value(null, readVarint()))
                1 -> pos += 8
                2 -> {
                    val length = readVarint().toInt()
                    require(length >= 0 && pos + length <= data.size) { "Повреждённый файл списка" }
                    val slice = data.copyOfRange(pos, pos + length)
                    pos += length
                    action(field, Value(slice, 0))
                }
                5 -> pos += 4
                else -> throw IllegalArgumentException("Повреждённый файл списка")
            }
        }
    }

    private fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            require(pos < data.size) { "Повреждённый файл списка" }
            val b = data[pos++].toInt()
            result = result or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }
}
