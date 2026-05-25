package com.fairshare.data.invitation

import java.util.Base64

/**
 * Invitation link codec.
 *
 * Encodes just the bare minimum needed for another device to join an
 * event: the event id and the 32-byte symmetric key. The joining
 * device pulls the full op log from the Cloudflare Worker after
 * registering its bearer (see [com.fairshare.data.invitation.InvitationImporter]).
 *
 * Wire format:
 *
 *   fairshare://join?event=<eventId>
 *                    &key=<base64url(32-byte eventKey)>
 *
 *   https://fairshare-web-bdg.pages.dev/join?event=<eventId>&key=…
 *
 * Both forms are interchangeable on decode. The https form is the
 * default for QR codes so iOS Safari / Camera open it natively; the
 * custom-scheme form is kept for in-app Android-only deep links.
 *
 * Anyone with the URL can read and write the event — treat it as a
 * shared secret. The codec does not embed an HMAC anymore because the
 * URL no longer carries an attacker-controllable JSON blob; tampering
 * with the key produces a value that fails AES-GCM authentication on
 * the very first pull, which the sync layer surfaces.
 *
 * Pure: no Android, no Room. Unit-tested with round-trip + tamper
 * tests in the JVM source set.
 */
internal object InvitationCodec {

    const val SCHEME = "fairshare"
    const val HOST = "join"
    const val HTTPS_HOST = "fairshare-web-bdg.pages.dev"
    const val HTTPS_PATH = "/join"

    /** Returned by [decode] on a valid invitation. */
    data class Decoded(
        val eventId: String,
        val eventKey: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Decoded) return false
            return eventId == other.eventId &&
                eventKey.contentEquals(other.eventKey)
        }
        override fun hashCode(): Int {
            var h = eventId.hashCode()
            h = 31 * h + eventKey.contentHashCode()
            return h
        }
    }

    /** Reasons [decode] can fail. */
    sealed interface DecodeError {
        data object MalformedUrl : DecodeError
        data object MissingFields : DecodeError
    }

    /**
     * Selects which URL flavour [encode] emits.
     *
     *   - [Https] (default): produces
     *     `https://fairshare-web-bdg.pages.dev/join?…`, so iOS Safari and
     *     iPadOS Camera open the link natively — that's the form the
     *     webapp uses to bootstrap its IndexedDB.
     *   - [Custom]: keeps the legacy `fairshare://join?…` form, useful
     *     for in-app deep linking on Android-only flows and for any
     *     printed QR predating the webapp.
     *
     * [decode] accepts both forms regardless of this setting.
     */
    enum class Host { Https, Custom }

    /** Builds an invitation URL. */
    fun encode(
        eventId: String,
        eventKey: ByteArray,
        host: Host = Host.Https,
    ): String {
        require(eventKey.size == 32) { "eventKey must be 32 bytes, got ${eventKey.size}" }
        val key = base64UrlEncode(eventKey)
        val query = "event=$eventId&key=$key"
        return when (host) {
            Host.Https -> "https://$HTTPS_HOST$HTTPS_PATH?$query"
            Host.Custom -> "$SCHEME://$HOST?$query"
        }
    }

    /** Parses an invitation URL, returns the bundle. */
    fun decode(url: String): Result<Decoded> {
        val params = parseUrl(url) ?: return Result.failure(
            DecodeException(DecodeError.MalformedUrl),
        )
        val eventId = params["event"] ?: return Result.failure(
            DecodeException(DecodeError.MissingFields),
        )
        val keyParam = params["key"] ?: return Result.failure(
            DecodeException(DecodeError.MissingFields),
        )

        val eventKey = runCatching { base64UrlDecode(keyParam) }
            .getOrElse { return Result.failure(DecodeException(DecodeError.MalformedUrl)) }
        if (eventKey.size != 32) {
            return Result.failure(DecodeException(DecodeError.MalformedUrl))
        }

        return Result.success(Decoded(eventId = eventId, eventKey = eventKey))
    }

    private fun parseUrl(url: String): Map<String, String>? {
        val customPrefix = "$SCHEME://$HOST?"
        val query = when {
            url.startsWith(customPrefix) -> url.substring(customPrefix.length)
            // Accept any https host with a `/join?` path so staging
            // deployments (e.g. preview channels of the webapp) or a
            // self-hosted mirror work without a code change. The
            // canonical host emitted by `encode()` stays
            // fairshare-web-bdg.pages.dev.
            else -> {
                val match = Regex("^https?://[^/?#]+/join\\?(.*)$").find(url)
                    ?: return null
                match.groupValues[1]
            }
        }
        if (query.isEmpty()) return null
        return query.split('&').mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq <= 0 || eq == pair.lastIndex) null
            else pair.substring(0, eq) to pair.substring(eq + 1)
        }.toMap()
    }

    private fun base64UrlEncode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun base64UrlDecode(s: String): ByteArray =
        Base64.getUrlDecoder().decode(s)
}

/** Wraps a [InvitationCodec.DecodeError] inside a `Throwable` for `Result.failure`. */
internal class DecodeException(val error: InvitationCodec.DecodeError) :
    Exception(error.toString())
