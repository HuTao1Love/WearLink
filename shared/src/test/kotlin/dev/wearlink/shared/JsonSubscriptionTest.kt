package dev.wearlink.shared

import dev.wearlink.shared.model.AppRouting
import dev.wearlink.shared.model.Security
import dev.wearlink.shared.model.Server
import dev.wearlink.shared.model.SingBoxOutbound
import dev.wearlink.shared.model.Transport
import dev.wearlink.shared.model.VlessConfig
import dev.wearlink.shared.parse.ImportClassifier
import dev.wearlink.shared.parse.ImportInput
import dev.wearlink.shared.parse.JsonSubscription
import dev.wearlink.shared.parse.LinkParser
import dev.wearlink.shared.parse.SubscriptionDecoder
import dev.wearlink.shared.singbox.SingBoxConfig
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class JsonSubscriptionTest {

    private val singBoxBody = """
        {"dns":{"servers":[{"tag":"dns-local","type":"local"}]},
         "outbounds":[
          {"type":"direct","tag":"direct"},
          {"type":"selector","tag":"default","outbounds":["🇳🇱 Нидерланды"]},
          {"type":"vless","tag":"🇳🇱 Нидерланды","server":"nl.example.com","server_port":443,
           "uuid":"b831381d-6324-4d53-ad4f-8cda48b30811","flow":"xtls-rprx-vision","domain_resolver":"dns-local",
           "tls":{"enabled":true,"server_name":"www.microsoft.com","utls":{"enabled":true,"fingerprint":"chrome"},
                  "reality":{"enabled":true,"public_key":"SbVKOEMjK0sIlbwg4akyBg5mL5KZwwB-ed4eEE7YnRc","short_id":"6ba85179"}}},
          {"type":"hysteria2","tag":"FI Hy2","server":"fi.example.com","server_port":8443,"password":"pw",
           "tls":{"enabled":true,"server_name":"fi.example.com"}}
         ],
         "route":{"final":"default"}}
    """.trimIndent()

    private val xrayBody = """
        [{"remarks":"🇩🇪 Германия","outbounds":[
           {"protocol":"vless","tag":"proxy","settings":{"vnext":[{"address":"de.example.com","port":443,
             "users":[{"id":"b831381d-6324-4d53-ad4f-8cda48b30811","flow":"xtls-rprx-vision","encryption":"none"}]}]},
            "streamSettings":{"network":"tcp","security":"reality","realitySettings":{"serverName":"www.microsoft.com",
             "fingerprint":"chrome","publicKey":"SbVKOEMjK0sIlbwg4akyBg5mL5KZwwB-ed4eEE7YnRc","shortId":"6ba85179"}}},
           {"protocol":"vless","tag":"proxy-2","settings":{"vnext":[{"address":"de.example.com","port":443,
             "users":[{"id":"b831381d-6324-4d53-ad4f-8cda48b30811","encryption":"none"}]}]},
            "streamSettings":{"network":"grpc","security":"reality","grpcSettings":{"serviceName":"svc"},
             "realitySettings":{"serverName":"www.microsoft.com","fingerprint":"chrome","publicKey":"SbVKOEMjK0sIlbwg4akyBg5mL5KZwwB-ed4eEE7YnRc","shortId":""}}},
           {"protocol":"vless","tag":"proxy-3","settings":{"vnext":[{"address":"de.example.com","port":443,
             "users":[{"id":"b831381d-6324-4d53-ad4f-8cda48b30811","encryption":"none"}]}]},
            "streamSettings":{"network":"xhttp","security":"reality","realitySettings":{"publicKey":"k"}}},
           {"protocol":"freedom","tag":"direct"}
        ]}]
    """.trimIndent()

    @Test
    fun singBoxConfigBecomesPassThroughServers() {
        assertTrue(JsonSubscription.isSingBox(singBoxBody))
        val decoded = SubscriptionDecoder.decode(singBoxBody, mapOf("Profile-Title" to "base64:UHJveGVu"))
        assertEquals("Proxen", decoded.info.title)
        assertEquals(2, decoded.links.size)

        val nl = LinkParser.parse(decoded.links[0], "sub")
        assertEquals("🇳🇱 Нидерланды", nl.name)
        assertEquals("VLESS Reality", nl.protocolLabel)
        val outbound = nl.config as SingBoxOutbound
        assertEquals("nl.example.com", outbound.host)

        // The provider's tag and DNS reference are dropped; ours is set by the config builder.
        val built = SingBoxConfig.buildObject(outbound, AppRouting(), "dev.wearlink")
        val proxy = built["outbounds"]!!.jsonArray[0].jsonObject
        assertEquals("proxy", proxy["tag"]!!.jsonPrimitive.content)
        assertNull(proxy["domain_resolver"])
        assertEquals("6ba85179", proxy["tls"]!!.jsonObject["reality"]!!.jsonObject["short_id"]!!.jsonPrimitive.content)

        assertEquals("Hysteria2", LinkParser.parse(decoded.links[1]).protocolLabel)
    }

    @Test
    fun xrayConfigBecomesShareLinks() {
        assertTrue(JsonSubscription.isJson(xrayBody))
        assertFalse(JsonSubscription.isSingBox(xrayBody))
        val servers = SubscriptionDecoder.decode(xrayBody).links.map { LinkParser.parse(it) }
        assertEquals(3, servers.size)

        val tcp = servers[0].config as VlessConfig
        assertEquals("xtls-rprx-vision", tcp.flow)
        assertEquals("SbVKOEMjK0sIlbwg4akyBg5mL5KZwwB-ed4eEE7YnRc", (tcp.security as Security.Reality).publicKey)
        assertEquals("🇩🇪 Германия · tcp 1", servers[0].name)

        assertEquals(Transport.Grpc("svc"), (servers[1].config as VlessConfig).transport)
        // xhttp stays visible but unusable.
        assertFalse(servers[2].isUsable)
    }

    /** The store persists servers as polymorphic JSON; the outbound must survive a round trip. */
    @Test
    fun singBoxServerSurvivesStoreSerialization() {
        val server = LinkParser.parse(SubscriptionDecoder.decode(singBoxBody).links[0], "sub")
        val json = kotlinx.serialization.json.Json
        val restored = json.decodeFromString(Server.serializer(), json.encodeToString(Server.serializer(), server))
        assertEquals(server, restored)
    }

    @Test
    fun internalLinksCanBePastedAndSent() {
        val link = SubscriptionDecoder.decode(singBoxBody).links[0]
        assertEquals(ImportInput.Links(listOf(link)), ImportClassifier.classify(link))
    }

    /**
     * Runs a real subscription saved locally (never committed): set WEARLINK_SUB_FILE to a
     * sing-box or Xray JSON body. Writes a config for `sing-box check`.
     */
    @Test
    fun realSubscriptionFromFile() {
        val path = System.getenv("WEARLINK_SUB_FILE")
        assumeTrue(path != null && File(path).exists())
        val decoded = SubscriptionDecoder.decode(File(path!!).readText())
        val servers = decoded.links.map { LinkParser.parse(it, "real") }
        println("servers=${servers.size} usable=${servers.count { it.isUsable }}")
        servers.forEach { println("  ${it.protocolLabel} | ${it.isUsable} | ${it.error ?: ""}") }
        assertTrue(servers.any { it.isUsable })
        File(System.getenv("WEARLINK_SUB_CONFIG_OUT") ?: "build/real-sub-config.json")
            .writeText(SingBoxConfig.build(servers.first { it.isUsable }.config!!, AppRouting(), "dev.wearlink"))
    }
}
