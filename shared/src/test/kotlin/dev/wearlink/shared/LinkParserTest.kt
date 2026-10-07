package dev.wearlink.shared

import dev.wearlink.shared.model.Hysteria2Config
import dev.wearlink.shared.model.Security
import dev.wearlink.shared.model.Transport
import dev.wearlink.shared.model.VlessConfig
import dev.wearlink.shared.parse.LinkParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkParserTest {

    @Test
    fun vlessRealityVision() {
        val server = LinkParser.parse(Samples.VLESS_REALITY)
        val c = server.config as VlessConfig
        assertEquals("nl1.example.com", c.host)
        assertEquals(443, c.port)
        assertEquals("b831381d-6324-4d53-ad4f-8cda48b30811", c.uuid)
        assertEquals("xtls-rprx-vision", c.flow)
        assertEquals(Transport.Tcp, c.transport)
        val reality = c.security as Security.Reality
        assertEquals("www.microsoft.com", reality.sni)
        assertEquals("chrome", reality.fingerprint)
        assertEquals("SbVKOEMjK0sIlbwg4akyBg5mL5KZwwB-ed4eEE7YnRc", reality.publicKey)
        assertEquals("6ba85179e30d4fc2", reality.shortId)
        assertEquals("🇳🇱 Нидерланды", server.name)
    }

    @Test
    fun vlessWebSocketTlsWithEarlyData() {
        val c = LinkParser.parse(Samples.VLESS_WS).config as VlessConfig
        val ws = c.transport as Transport.WebSocket
        assertEquals("/ws", ws.path)
        assertEquals("cdn.example.com", ws.host)
        assertEquals(2048, ws.maxEarlyData)
        val tls = c.security as Security.Tls
        assertEquals(listOf("h2", "http/1.1"), tls.alpn)
        assertEquals("firefox", tls.fingerprint)
    }

    @Test
    fun vlessGrpc() {
        val c = LinkParser.parse(Samples.VLESS_GRPC).config as VlessConfig
        assertEquals(Transport.Grpc("grpc-svc"), c.transport)
    }

    @Test
    fun vlessXhttpIsMarkedUnsupported() {
        val server = LinkParser.parse("vless://uuid@h.example:443?type=xhttp&security=tls#X")
        assertNull(server.config)
        assertFalse(server.isUsable)
        assertTrue(server.error!!.contains("xhttp"))
        assertEquals("X", server.name)
    }

    @Test
    fun hysteria2WithObfsAndPortHopping() {
        val c = LinkParser.parse(Samples.HY2).config as Hysteria2Config
        assertEquals("hy.example.com", c.host)
        assertEquals(443, c.port)
        assertEquals("p@ss/word", c.password)
        assertEquals(listOf("20000:30000"), c.serverPorts)
        assertEquals("salamander-secret", c.obfsPassword)
        assertTrue(c.insecure)
        assertEquals("hy.example.com", c.sni)
    }

    @Test
    fun hy2SchemeAliasAndMultiPortAuthority() {
        val c = LinkParser.parse("hy2://secret@1.2.3.4:443,5000-6000/?sni=a.b#n").config as Hysteria2Config
        assertEquals("1.2.3.4", c.host)
        assertEquals(443, c.port)
        assertEquals(listOf("5000:6000"), c.serverPorts)
    }

    @Test
    fun ipv6Host() {
        val c = LinkParser.parse("vless://u@[2001:db8::1]:8443?security=none#v6").config as VlessConfig
        assertEquals("2001:db8::1", c.host)
        assertEquals(8443, c.port)
    }

    @Test
    fun unknownProtocolKeepsNameAndError() {
        val server = LinkParser.parse("wireguard://key@w.example:51820#WG%20NL")
        assertEquals("WG NL", server.name)
        assertNotNull(server.error)
    }

    @Test
    fun idIsStablePerSubscription() {
        assertEquals(LinkParser.parse(Samples.HY2, "s1").id, LinkParser.parse(Samples.HY2, "s1").id)
        assertFalse(LinkParser.parse(Samples.HY2, "s1").id == LinkParser.parse(Samples.HY2, "s2").id)
    }
}
