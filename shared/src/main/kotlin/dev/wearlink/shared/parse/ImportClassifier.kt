package dev.wearlink.shared.parse

sealed interface ImportInput {
    data class SubscriptionUrl(val url: String) : ImportInput
    data class Links(val links: List<String>) : ImportInput
    data class Invalid(val reason: String) : ImportInput
}

/** Figures out what the user pasted, shared or scanned. */
object ImportClassifier {

    private val httpUrl = Regex("https?://[^\\s\"'<>]+", RegexOption.IGNORE_CASE)
    private val linkStart = Regex("\\b(vless|vmess|trojan|ss|hysteria2|hy2|hysteria|tuic|anytls|sbox)://", RegexOption.IGNORE_CASE)

    fun classify(input: String): ImportInput {
        val text = input.trim()
        if (text.isEmpty()) return ImportInput.Invalid("Пусто")

        if (text.startsWith("happ://crypt", ignoreCase = true)) {
            return ImportInput.Invalid("Зашифрованные ссылки Happ не поддерживаются. Скопируйте исходную ссылку подписки.")
        }
        unwrapClientDeepLink(text)?.let { return ImportInput.SubscriptionUrl(it) }

        val links = extractLinks(text)
        if (links.isNotEmpty()) return ImportInput.Links(links)

        httpUrl.find(text)?.let { return ImportInput.SubscriptionUrl(it.value.trimEnd('.', ',', ')')) }

        // A raw base64 subscription body pasted directly.
        val decoded = SubscriptionDecoder.decodeBase64(text)
        if (decoded != null) {
            val decodedLinks = extractLinks(decoded)
            if (decodedLinks.isNotEmpty()) return ImportInput.Links(decodedLinks)
        }
        return ImportInput.Invalid("Не найдено ни ссылки на сервер, ни адреса подписки")
    }

    /** Links may contain unescaped spaces in the name, so each line from the scheme onwards is one link. */
    private fun extractLinks(text: String): List<String> =
        text.lines().mapNotNull { line ->
            val match = linkStart.find(line) ?: return@mapNotNull null
            line.substring(match.range.first).trim()
        }

    /** happ://add/<url>, v2rayng://install-config?url=<url>, sing-box://import-remote-profile?url=<url>, ... */
    private fun unwrapClientDeepLink(text: String): String? {
        val lower = text.lowercase()
        if (lower.startsWith("http") || linkStart.find(text)?.range?.first == 0) return null
        val scheme = lower.substringBefore("://", "")
        if (scheme.isEmpty()) return null
        val afterScheme = text.substringAfter("://")
        val urlParam = afterScheme.substringAfter("?", "").split('&')
            .firstOrNull { it.startsWith("url=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.let(RawUri::percentDecode)
        if (urlParam != null && urlParam.startsWith("http", ignoreCase = true)) {
            return urlParam.substringBefore('#')
        }
        return httpUrl.find(afterScheme)?.value
    }
}
