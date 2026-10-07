package dev.wearlink.shared

import dev.wearlink.shared.model.AnyTlsConfig
import dev.wearlink.shared.model.AppRouting
import dev.wearlink.shared.model.Hysteria1Config
import dev.wearlink.shared.model.Security
import dev.wearlink.shared.model.ShadowsocksConfig
import dev.wearlink.shared.model.Transport
import dev.wearlink.shared.model.TrojanConfig
import dev.wearlink.shared.model.TuicConfig
import dev.wearlink.shared.model.VmessConfig
import dev.wearlink.shared.parse.ImportClassifier
import dev.wearlink.shared.parse.ImportInput
import dev.wearlink.shared.parse.LinkParser
import dev.wearlink.shared.singbox.SingBoxConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

class ProtocolsTest {

    @Test
    fun trojanGrpc() {
        val server = LinkParser.parse(Samples.TROJAN)
        val c = server.config as TrojanConfig
        assertEquals("tr-pass", c.password)
        assertEquals(Transport.Grpc("tr-grpc"), c.transport)
        assertEquals("tr.example.com", (c.security as Security.Tls).sni)
        assertEquals("Trojan gRPC", server.name)
    }

    @Test
    fun trojanDefaultsToTls() {
        val c = LinkParser.parse("trojan://pw@t.example.com:443#t").config as TrojanConfig
        assertTrue(c.security is Security.Tls)
    }

    @Test
    fun vmessBase64Json() {
        val server = LinkParser.parse(Samples.VMESS)
        val c = server.config as VmessConfig
        assertEquals("vm.example.com", c.host)
        assertEquals(443, c.port)
        assertEquals("b831381d-6324-4d53-ad4f-8cda48b30811", c.uuid)
        assertEquals("/vm", (c.transport as Transport.WebSocket).path)
        assertEquals("chrome", (c.security as Security.Tls).fingerprint)
        assertEquals("VMess WS", server.name)
    }

    @Test
    fun vmessJsonWithUnsupportedTransportKeepsName() {
        val json = """{"ps":"KCP","add":"k.example.com","port":"443","id":"u","net":"kcp"}"""
        val server = LinkParser.parse("vmess://" + Base64.getEncoder().encodeToString(json.toByteArray()))
        assertNull(server.config)
        assertEquals("KCP", server.name)
        assertTrue(server.error!!.contains("kcp"))
    }

    @Test
    fun shadowsocksVariants() {
        val sip = LinkParser.parse(Samples.SS_SIP002).config as ShadowsocksConfig
        assertEquals("chacha20-ietf-poly1305", sip.method)
        assertEquals("ss-pass", sip.password)
        assertEquals(8388, sip.port)

        val ss2022 = LinkParser.parse(Samples.SS_2022).config as ShadowsocksConfig
        assertEquals("2022-blake3-aes-128-gcm", ss2022.method)
        assertEquals("AAECAwQFBgcICQoLDA0ODw==", ss2022.password)
        assertEquals("obfs-local", ss2022.plugin)
        assertEquals("obfs=http;obfs-host=bing.com", ss2022.pluginOpts)

        val legacy = LinkParser.parse(Samples.SS_LEGACY)
        val c = legacy.config as ShadowsocksConfig
        assertEquals("5.6.7.8", c.host)
        assertEquals("legacy-pass", c.password)
        assertEquals("Legacy", legacy.name)
    }

    @Test
    fun tuic() {
        val c = LinkParser.parse(Samples.TUIC).config as TuicConfig
        assertEquals("2DD61D93-75D8-4DA4-AC0E-6AECE7EAC365", c.uuid)
        assertEquals("tuic-pass", c.password)
        assertEquals("quic", c.udpRelayMode)
        assertTrue(c.tls.insecure)
        assertEquals(listOf("h3"), c.tls.alpn)
    }

    @Test
    fun anyTls() {
        val c = LinkParser.parse(Samples.ANYTLS).config as AnyTlsConfig
        assertEquals("any-pass", c.password)
        assertEquals("any.example.com", c.tls.sni)
    }

    @Test
    fun hysteriaV1() {
        val c = LinkParser.parse(Samples.HYSTERIA1).config as Hysteria1Config
        assertEquals("hy1-auth", c.auth)
        assertEquals(20, c.upMbps)
        assertEquals(100, c.downMbps)
        assertEquals("hy1-obfs", c.obfs)
        assertEquals("hy1.example.com", c.tls.sni)
    }

    @Test
    fun classifierPicksUpNewSchemesButNotHttps() {
        val links = ImportClassifier.classify("${Samples.TROJAN}\n${Samples.TUIC}") as ImportInput.Links
        assertEquals(2, links.links.size)
        assertEquals(
            ImportInput.SubscriptionUrl("https://sub.example.com/x"),
            ImportClassifier.classify("https://sub.example.com/x"),
        )
    }

    @Test
    fun everySampleBuildsAnOutbound() {
        listOf(Samples.TROJAN, Samples.VMESS, Samples.SS_SIP002, Samples.SS_2022, Samples.SS_LEGACY, Samples.TUIC, Samples.ANYTLS, Samples.HYSTERIA1)
            .forEach { assertNotNull(it, LinkParser.parse(it).config) }
    }

    /** Written for `sing-box check`, like [SingBoxConfigTest.writeSampleConfigs]. */
    @Test
    fun writeSampleConfigs() {
        val dir = File("build/sample-configs").apply { mkdirs() }
        mapOf(
            "trojan" to Samples.TROJAN,
            "vmess" to Samples.VMESS,
            "ss-sip002" to Samples.SS_SIP002,
            "ss-2022-obfs" to Samples.SS_2022,
            "ss-legacy" to Samples.SS_LEGACY,
            "tuic" to Samples.TUIC,
            "anytls" to Samples.ANYTLS,
            "hysteria1" to Samples.HYSTERIA1,
        ).forEach { (name, link) ->
            File(dir, "$name.json").writeText(SingBoxConfig.build(LinkParser.parse(link).config!!, AppRouting(), "dev.wearlink"))
        }
    }
}
