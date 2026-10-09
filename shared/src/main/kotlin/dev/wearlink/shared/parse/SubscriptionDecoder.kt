package dev.wearlink.shared.parse

import java.util.Base64

/** Metadata a provider attaches to a subscription via HTTP headers or `#key: value` body lines. */
data class SubscriptionInfo(
    val title: String? = null,
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    val expire: Long = 0,
    val updateIntervalHours: Int = 0,
)

data class DecodedSubscription(
    val links: List<String>,
    val info: SubscriptionInfo,
)

object SubscriptionDecoder {

    /**
     * Decodes a subscription body (base64 or plain list of links).
     * [headers] keys are matched case-insensitively.
     */
    fun decode(body: String, headers: Map<String, String> = emptyMap()): DecodedSubscription {
        if (JsonSubscription.isJson(body)) {
            return DecodedSubscription(JsonSubscription.links(body), parseInfo(headers.mapKeys { it.key.lowercase() }))
        }
        val text = decodeBodyText(body)
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

        // Some panels (Remnawave, Marzban via Happ templates) put headers into the body as comments.
        val bodyHeaders = lines.filter { it.startsWith("#") && ':' in it }
            .associate { it.removePrefix("#").substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val allHeaders = bodyHeaders + headers.mapKeys { it.key.lowercase() }

        return DecodedSubscription(
            links = lines.filter { LinkParser.isProxyLink(it) },
            info = parseInfo(allHeaders),
        )
    }

    /** Returns the decoded text if [body] is base64, otherwise [body] itself. */
    fun decodeBodyText(body: String): String {
        val trimmed = body.trim()
        if ("://" in trimmed) return trimmed
        return decodeBase64(trimmed)?.takeIf { "://" in it } ?: trimmed
    }

    fun decodeBase64(value: String): String? {
        val compact = value.filterNot { it.isWhitespace() }
            .replace('-', '+')
            .replace('_', '/')
            .trimEnd('=')
        if (compact.isEmpty() || compact.any { !(it.isLetterOrDigit() || it == '+' || it == '/') }) return null
        val padded = compact + "=".repeat((4 - compact.length % 4) % 4)
        return try {
            String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun parseInfo(headers: Map<String, String>): SubscriptionInfo {
        val userInfo = headers["subscription-userinfo"].orEmpty()
            .split(';')
            .mapNotNull { part ->
                val key = part.substringBefore('=', "").trim().lowercase()
                val value = part.substringAfter('=', "").trim().toBigDecimalOrNull()?.toLong()
                if (key.isEmpty() || value == null) null else key to value
            }
            .toMap()
        return SubscriptionInfo(
            title = headers["profile-title"]?.let(::decodeTitle)?.takeIf { it.isNotBlank() }
                ?: headers["content-disposition"]?.let(::filenameFromDisposition),
            upload = userInfo["upload"] ?: 0,
            download = userInfo["download"] ?: 0,
            total = userInfo["total"] ?: 0,
            expire = userInfo["expire"] ?: 0,
            updateIntervalHours = headers["profile-update-interval"]?.trim()?.toIntOrNull() ?: 0,
        )
    }

    private fun decodeTitle(raw: String): String {
        val value = raw.trim()
        return if (value.startsWith("base64:")) {
            decodeBase64(value.removePrefix("base64:")) ?: value
        } else {
            RawUri.percentDecode(value)
        }
    }

    private fun filenameFromDisposition(value: String): String? {
        val star = Regex("filename\\*=(?:UTF-8'')?([^;]+)", RegexOption.IGNORE_CASE).find(value)
        if (star != null) return RawUri.percentDecode(star.groupValues[1].trim('"'))
        return Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(value)?.groupValues?.get(1)
    }
}
