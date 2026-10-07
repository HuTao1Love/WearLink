package dev.wearlink.shared

import dev.wearlink.shared.parse.ImportClassifier
import dev.wearlink.shared.parse.ImportInput
import dev.wearlink.shared.parse.SubscriptionDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SubscriptionTest {

    private val plain = listOf(Samples.VLESS_REALITY, Samples.HY2, "vmess://eyJ2IjoiMiJ9").joinToString("\n")

    @Test
    fun decodesStandardBase64() {
        val body = Base64.getEncoder().encodeToString(plain.toByteArray())
        val decoded = SubscriptionDecoder.decode(body)
        assertEquals(3, decoded.links.size)
        assertEquals(Samples.VLESS_REALITY, decoded.links[0])
    }

    @Test
    fun decodesUrlSafeBase64WithoutPaddingAndLineBreaks() {
        val body = Base64.getUrlEncoder().withoutPadding().encodeToString(plain.toByteArray()).chunked(76).joinToString("\r\n")
        assertEquals(3, SubscriptionDecoder.decode(body).links.size)
    }

    @Test
    fun decodesPlainListWithBodyHeaders() {
        val body = "#profile-title: base64:${Base64.getEncoder().encodeToString("Мой VPN".toByteArray())}\n" +
            "#subscription-userinfo: upload=10; download=20; total=1000; expire=1767225600\n" +
            plain
        val decoded = SubscriptionDecoder.decode(body)
        assertEquals(3, decoded.links.size)
        assertEquals("Мой VPN", decoded.info.title)
        assertEquals(10L, decoded.info.upload)
        assertEquals(20L, decoded.info.download)
        assertEquals(1000L, decoded.info.total)
        assertEquals(1767225600L, decoded.info.expire)
    }

    @Test
    fun httpHeadersOverrideBodyHeaders() {
        val decoded = SubscriptionDecoder.decode(
            "#profile-title: body\n$plain",
            mapOf("Profile-Title" to "header", "Profile-Update-Interval" to "6"),
        )
        assertEquals("header", decoded.info.title)
        assertEquals(6, decoded.info.updateIntervalHours)
    }

    @Test
    fun classifierRecognisesInputs() {
        assertEquals(
            ImportInput.SubscriptionUrl("https://sub.example.com/api/sub/abc"),
            ImportClassifier.classify("Ваша подписка: https://sub.example.com/api/sub/abc"),
        )
        assertEquals(
            ImportInput.SubscriptionUrl("https://sub.example.com/s/xyz"),
            ImportClassifier.classify("happ://add/https://sub.example.com/s/xyz"),
        )
        assertEquals(
            ImportInput.SubscriptionUrl("https://sub.example.com/s/xyz"),
            ImportClassifier.classify("v2rayng://install-config?url=https%3A%2F%2Fsub.example.com%2Fs%2Fxyz#name"),
        )
        val links = ImportClassifier.classify("Держи:\n${Samples.VLESS_REALITY}\n${Samples.HY2}") as ImportInput.Links
        assertEquals(listOf(Samples.VLESS_REALITY, Samples.HY2), links.links)
        assertTrue(ImportClassifier.classify("happ://crypt3/abc") is ImportInput.Invalid)
        assertTrue(ImportClassifier.classify("hello") is ImportInput.Invalid)
        val b64 = Base64.getEncoder().encodeToString(plain.toByteArray())
        assertEquals(3, (ImportClassifier.classify(b64) as ImportInput.Links).links.size)
    }
}
