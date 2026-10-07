package dev.wearlink.shared

import dev.wearlink.shared.geo.GeoDat
import dev.wearlink.shared.model.AppRouting
import dev.wearlink.shared.parse.LinkParser
import dev.wearlink.shared.singbox.Inbound
import dev.wearlink.shared.singbox.RuleSets
import dev.wearlink.shared.singbox.SingBoxConfig
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class GeoDatTest {

    // Minimal protobuf writer to build fixtures.
    private fun varint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v >= 0x80) {
            out.write(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    private fun bytesField(field: Int, data: ByteArray) = ByteArrayOutputStream().apply {
        varint(this, (field shl 3 or 2).toLong())
        varint(this, data.size.toLong())
        write(data)
    }.toByteArray()

    private fun varintField(field: Int, value: Long) = ByteArrayOutputStream().apply {
        varint(this, (field shl 3).toLong())
        varint(this, value)
    }.toByteArray()

    private fun domain(type: Long, value: String) = varintField(1, type) + bytesField(2, value.toByteArray())

    @Test
    fun parsesGeoSite() {
        val site = bytesField(1, "youtube".toByteArray()) +
            bytesField(2, domain(2, "youtube.com")) +
            bytesField(2, domain(3, "youtu.be")) +
            bytesField(2, domain(0, "googlevideo")) +
            bytesField(2, domain(1, "^yt[0-9]+\\.example$"))
        val parsed = GeoDat.parseSites(bytesField(1, site))
        val rules = parsed.getValue("YOUTUBE")
        assertEquals(listOf("youtube.com"), rules.suffix)
        assertEquals(listOf("youtu.be"), rules.full)
        assertEquals(listOf("googlevideo"), rules.keyword)
        assertEquals(1, rules.regex.size)
    }

    @Test
    fun parsesGeoIp() {
        val v4 = bytesField(1, byteArrayOf(149.toByte(), 154.toByte(), 160.toByte(), 0)) + varintField(2, 20)
        val v6 = bytesField(1, ByteArray(16).also { it[0] = 0x20; it[1] = 0x01; it[2] = 0x0d; it[3] = 0xb8.toByte() }) + varintField(2, 32)
        val geo = bytesField(1, "telegram".toByteArray()) + bytesField(2, v4) + bytesField(2, v6)
        val parsed = GeoDat.parseIps(bytesField(1, geo))
        assertEquals(listOf("149.154.160.0/20", "2001:db8:0:0:0:0:0:0/32"), parsed.getValue("TELEGRAM"))
        val json = kotlinx.serialization.json.Json.parseToJsonElement(GeoDat.ipRuleSet(parsed.getValue("TELEGRAM"))).jsonObject
        assertEquals(3, json["version"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, json["rules"]!!.jsonArray[0].jsonObject["ip_cidr"]!!.jsonArray.size)
    }

    /**
     * Converts the real zkeen lists (scripts put them in third_party/geo) and writes a config
     * that uses them, for `sing-box check`. Skipped when the files are not downloaded.
     */
    @Test
    fun convertsRealZkeenLists() {
        val sitesFile = File("../third_party/geo/zkeen.dat")
        val ipsFile = File("../third_party/geo/zkeenip.dat")
        assumeTrue(sitesFile.exists() && ipsFile.exists())
        val sites = GeoDat.parseSites(sitesFile.readBytes())
        val ips = GeoDat.parseIps(ipsFile.readBytes())
        assertEquals(true, sites.getValue("DOMAINS").size > 100)
        assertEquals(true, ips.getValue("TELEGRAM").isNotEmpty())

        val dir = File("build/sample-configs/rule-sets").apply { mkdirs() }
        fun write(name: String, content: String) = File(dir, name).apply { writeText(content) }.absolutePath
        val ruleSets = RuleSets(
            sites = mapOf("site-youtube" to write("site-youtube.json", GeoDat.siteRuleSet(sites.getValue("YOUTUBE"))),
                "site-domains" to write("site-domains.json", GeoDat.siteRuleSet(sites.getValue("DOMAINS")))),
            ips = mapOf("ip-telegram" to write("ip-telegram.json", GeoDat.ipRuleSet(ips.getValue("TELEGRAM"))),
                "ip-amazon" to write("ip-amazon.json", GeoDat.ipRuleSet(ips.getValue("AMAZON")))),
        )
        val proxy = LinkParser.parse(Samples.VLESS_REALITY).config!!
        File("build/sample-configs/lists-tun.json").writeText(SingBoxConfig.build(proxy, AppRouting(), "dev.wearlink", ruleSets = ruleSets))
        File("build/sample-configs/lists-proxy.json").writeText(
            SingBoxConfig.build(proxy, AppRouting(), "dev.wearlink", Inbound.LocalProxy(10808), ruleSets),
        )
    }
}
