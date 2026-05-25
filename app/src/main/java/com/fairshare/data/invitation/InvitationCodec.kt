package com.fairshare.data.invitation

import com.fairshare.data.sync.SyncCrypto
import com.fairshare.domain.model.sync.Operation
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Invitation link codec.
 *
 * Encodes the event encryption key plus a seed op log into a self-
 * describing `fairshare://join` URL that the inviter shares as a QR
 * code or copy-paste link. A joining device decodes the URL, persists
 * an [com.fairshare.data.local.entity.EventEntity] with the key, then
 * materializes the seed ops to reach parity with the inviter in one
 * pass.
 *
 * Wire format:
 *
 *   fairshare://join?event=<eventId>
 *                    &key=<base64url(32-byte eventKey)>
 *                    &seed=<base64url(gzip(json(ops)))>
 *                    &sig=<base64url(HMAC-SHA256(macKey, seed))>
 *
 * `macKey` is derived from the embedded event key via
 * `HKDF(eventKey, "fairshare-invitation-mac")` (see
 * [SyncCrypto.deriveInvitationMacKey]). Tampering with the key
 * invalidates the HMAC, so the codec refuses to deserialize attacker-
 * controlled JSON.
 *
 * Pure: no Android, no Room. Unit-tested with round-trip + tamper
 * tests in the JVM source set.
 */
internal object InvitationCodec {

    const val SCHEME = "fairshare"
    const val HOST = "join"
    const val HTTPS_HOST = "fairshare-web.pages.dev"
    const val HTTPS_PATH = "/join"

    /** Returned by [decode] on a valid invitation. */
    data class Decoded(
        val eventId: String,
        val eventKey: ByteArray,
        val ops: List<Operation>,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Decoded) return false
            return eventId == other.eventId &&
                eventKey.contentEquals(other.eventKey) &&
                ops == other.ops
        }
        override fun hashCode(): Int {
            var h = eventId.hashCode()
            h = 31 * h + eventKey.contentHashCode()
            h = 31 * h + ops.hashCode()
            return h
        }
    }

    /** Reasons [decode] can fail. */
    sealed interface DecodeError {
        data object MalformedUrl : DecodeError
        data object MissingFields : DecodeError
        data object SignatureMismatch : DecodeError
        data class PayloadInvalid(val cause: Throwable) : DecodeError
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    /**
     * Selects which URL flavour [encode] emits.
     *
     *   - [Https] (default): produces
     *     `https://fairshare-web.pages.dev/join?…`, so iOS Safari and
     *     iPadOS Camera open the link natively — that's the form the
     *     webapp uses to bootstrap its IndexedDB.
     *   - [Custom]: keeps the legacy `fairshare://join?…` form, useful
     *     for in-app deep linking on Android-only flows and for any
     *     printed QR predating the webapp.
     *
     * [decode] accepts both forms regardless of this setting.
     */
    enum class Host { Https, Custom }

    /**
     * Builds an invitation URL. Carries the event encryption key plus
     * the whole op log as a seed, so the joining device reaches parity
     * in a single import.
     */
    fun encode(
        eventId: String,
        ops: List<Operation>,
        eventKey: ByteArray,
        host: Host = Host.Https,
    ): String {
        require(eventKey.size == 32) { "eventKey must be 32 bytes, got ${eventKey.size}" }
        require(ops.all { it.eventId == eventId }) {
            "InvitationCodec.encode: all ops must share eventId=$eventId"
        }
        val seedBytes = json.encodeToString(ListSerializer(Operation.serializer()), ops)
            .toByteArray(Charsets.UTF_8)
        val seed = base64UrlEncode(gzip(seedBytes))
        val key = base64UrlEncode(eventKey)
        val mac = base64UrlEncode(
            SyncCrypto.hmacSha256(
                SyncCrypto.deriveInvitationMacKey(eventKey),
                seed.toByteArray(Charsets.US_ASCII),
            ),
        )
        val query = "event=$eventId&key=$key&seed=$seed&sig=$mac"
        return when (host) {
            Host.Https -> "https://$HTTPS_HOST$HTTPS_PATH?$query"
            Host.Custom -> "$SCHEME://$HOST?$query"
        }
    }

    /** Parses an invitation URL, verifies its HMAC, returns the bundle. */
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
        val seed = params["seed"] ?: return Result.failure(
            DecodeException(DecodeError.MissingFields),
        )
        val sig = params["sig"] ?: return Result.failure(
            DecodeException(DecodeError.MissingFields),
        )

        val eventKey = runCatching { base64UrlDecode(keyParam) }
            .getOrElse { return Result.failure(DecodeException(DecodeError.MalformedUrl)) }
        if (eventKey.size != 32) {
            return Result.failure(DecodeException(DecodeError.MalformedUrl))
        }

        val expected = SyncCrypto.hmacSha256(
            SyncCrypto.deriveInvitationMacKey(eventKey),
            seed.toByteArray(Charsets.US_ASCII),
        )
        val provided = runCatching { base64UrlDecode(sig) }
            .getOrElse {
                return Result.failure(DecodeException(DecodeError.SignatureMismatch))
            }
        if (!SyncCrypto.constantTimeEquals(expected, provided)) {
            return Result.failure(DecodeException(DecodeError.SignatureMismatch))
        }

        return runCatching {
            val payload = gunzip(base64UrlDecode(seed)).toString(Charsets.UTF_8)
            val ops = json.decodeFromString(
                ListSerializer(Operation.serializer()),
                payload,
            )
            require(ops.all { it.eventId == eventId }) {
                "decoded seed ops carry an eventId different from the URL"
            }
            Decoded(eventId = eventId, eventKey = eventKey, ops = ops)
        }.recoverCatching {
            throw DecodeException(DecodeError.PayloadInvalid(it))
        }
    }

    private fun parseUrl(url: String): Map<String, String>? {
        val customPrefix = "$SCHEME://$HOST?"
        val query = when {
            url.startsWith(customPrefix) -> url.substring(customPrefix.length)
            // Accept any https host with a `/join?` path so staging
            // deployments (e.g. preview channels of the webapp) or a
            // self-hosted mirror work without a code change. The
            // canonical host emitted by `encode()` stays
            // fairshare-web.pages.dev.
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

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): ByteArray {
        return GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
    }

    private fun base64UrlEncode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun base64UrlDecode(s: String): ByteArray =
        Base64.getUrlDecoder().decode(s)
}

/** Wraps a [InvitationCodec.DecodeError] inside a `Throwable` for `Result.failure`. */
internal class DecodeException(val error: InvitationCodec.DecodeError) :
    Exception(error.toString())
