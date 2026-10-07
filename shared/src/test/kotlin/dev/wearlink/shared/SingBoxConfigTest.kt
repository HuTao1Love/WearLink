package dev.wearlink.shared

import dev.wearlink.shared.model.AppRouting
import dev.wearlink.shared.model.RoutingMode
import dev.wearlink.shared.parse.LinkParser
import dev.wearlink.shared.singbox.Inbound
import dev.wearlink.shared.singbox.SingBoxConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class SingBoxConfigTest {

    private val own = "dev.wearlink"

    private fun tun(config: JsonObject) = config["inbounds"]!!.jsonArray[0].jsonObject
    private fun proxy(config: JsonObject) = config["outbounds"]!!.jsonArray[0].jsonObject
    private fun strings(array: Any?) = (array as JsonArray).map { it.jsonPrimitive.content }

    @Test
    fun realityOutbound() {
        val config = SingBoxConfig.buildObject(LinkParser.parse(Samples.VLESS_REALITY).config!!, AppRouting(), own)
        val out = proxy(config)
        assertEquals("vless", out["type"]!!.jsonPrimitive.content)
        assertEquals("xtls-rprx-vision", out["flow"]!!.jsonPrimitive.content)
        val tls = out["tls"]!!.jsonObject
        assertEquals("www.microsoft.com", tls["server_name"]!!.jsonPrimitive.content)
        assertEquals("6ba85179e30d4fc2", tls["reality"]!!.jsonObject["short_id"]!!.jsonPrimitive.content)
        assertFalse(tun(config).containsKey("include_package"))
    }

    @Test
    fun onlySelectedAlwaysIncludesOwnPackage() {
        val config = SingBoxConfig.buildObject(
            LinkParser.parse(Samples.HY2).config!!,
            AppRouting(RoutingMode.ONLY_SELECTED, setOf("com.android.chrome")),
            own,
        )
        assertEquals(listOf("com.android.chrome", own), strings(tun(config)["include_package"]))
    }

    @Test
    fun allExceptNeverExcludesOwnPackage() {
        val config = SingBoxConfig.buildObject(
            LinkParser.parse(Samples.HY2).config!!,
            AppRouting(RoutingMode.ALL_EXCEPT, setOf("com.bank", own)),
            own,
        )
        assertEquals(listOf("com.bank"), strings(tun(config)["exclude_package"]))
    }

    @Test
    fun localProxyInboundForWatches() {
        val config = SingBoxConfig.buildObject(
            LinkParser.parse(Samples.VLESS_REALITY).config!!,
            AppRouting(RoutingMode.ONLY_SELECTED, setOf("com.android.chrome")),
            own,
            Inbound.LocalProxy(10808),
        )
        val inbound = tun(config)
        assertEquals("mixed", inbound["type"]!!.jsonPrimitive.content)
        assertEquals("127.0.0.1", inbound["listen"]!!.jsonPrimitive.content)
        assertEquals(10808, inbound["listen_port"]!!.jsonPrimitive.content.toInt())
        assertFalse(inbound.containsKey("include_package"))
        File("build/sample-configs").mkdirs()
        File("build/sample-configs/local-proxy.json").writeText(
            SingBoxConfig.build(LinkParser.parse(Samples.HY2).config!!, AppRouting(), own, Inbound.LocalProxy(10808)),
        )
    }

    /** Writes sample configs so they can be validated with `sing-box check` (see README). */
    @Test
    fun writeSampleConfigs() {
        val dir = File("build/sample-configs").apply { mkdirs() }
        mapOf(
            "vless-reality" to Samples.VLESS_REALITY,
            "vless-ws" to Samples.VLESS_WS,
            "vless-grpc" to Samples.VLESS_GRPC,
            "hysteria2" to Samples.HY2,
        ).forEach { (name, link) ->
            val routing = AppRouting(RoutingMode.ALL_EXCEPT, setOf("com.bank"))
            File(dir, "$name.json").writeText(SingBoxConfig.build(LinkParser.parse(link).config!!, routing, own))
        }
    }
}
