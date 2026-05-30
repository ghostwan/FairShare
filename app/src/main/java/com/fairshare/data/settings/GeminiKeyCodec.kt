package com.fairshare.data.settings

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Encodes / decodes a Gemini API key + model into a QR-friendly URL.
 *
 * Wire format (byte-compatible with the webapp's
 * `webapp/src/core/settings/geminiKeyCodec.ts`):
 *
 *   fairshare://gemini?key=<urlencoded-key>&model=<urlencoded-model>
 *
 * The model is optional — when absent the decoder returns `null` and
 * the receiver keeps its current model. Plaintext on purpose: the key
 * is a secret the user already trusts on the source device, the QR is
 * displayed momentarily, and no Cloud Worker ever sees it.
 *
 * Use only with the in-app scanners. The URL deliberately does NOT
 * have an `https://` variant — random people pointing iOS Camera at
 * the QR shouldn't trigger a browser redirect that could be logged.
 */
object GeminiKeyCodec {
    private const val SCHEME = "fairshare://gemini?"

    data class GeminiKeyExport(val key: String, val model: String?)

    fun encode(key: String, model: String?): String {
        require(key.isNotBlank()) { "key must not be blank" }
        val q = StringBuilder("key=").append(urlEncode(key.trim()))
        if (!model.isNullOrBlank()) {
            q.append("&model=").append(urlEncode(model.trim()))
        }
        return SCHEME + q
    }

    fun decode(url: String): GeminiKeyExport {
        require(url.startsWith(SCHEME)) { "not a fairshare gemini URL" }
        val query = url.substring(SCHEME.length)
        var key: String? = null
        var model: String? = null
        for (pair in query.split("&")) {
            val eq = pair.indexOf('=')
            if (eq <= 0 || eq == pair.length - 1) continue
            val name = pair.substring(0, eq)
            val value = urlDecode(pair.substring(eq + 1))
            when (name) {
                "key" -> key = value
                "model" -> model = value
            }
        }
        val resolved = key?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("missing key field")
        return GeminiKeyExport(key = resolved, model = model?.takeIf { it.isNotBlank() })
    }

    fun isGeminiKeyUrl(url: String): Boolean = url.startsWith(SCHEME)

    private fun urlEncode(s: String): String =
        URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

    private fun urlDecode(s: String): String =
        URLDecoder.decode(s, Charsets.UTF_8)
}
