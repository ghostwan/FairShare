package com.fairshare.data.sync

import com.fairshare.domain.model.sync.Operation
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Sneakernet bundle codec (DESIGN.md §6.1).
 *
 * Encodes a list of [Operation]s scoped to one event into a self-
 * describing `fairshare://sync` URL that the user can share via any
 * messenger, copy to the clipboard, or render as a QR code.
 *
 * Wire format:
 *
 *   fairshare://sync?event=<eventId>
 *                    &data=<base64url(gzip(json(ops)))>
 *                    &sig=<base64url(HMAC-SHA256(macKey, data))>
 *
 * `macKey` is derived from the event key via
 * `HKDF(eventKey, "fairshare-sneakernet-mac")` so it cannot be reused
 * for the Worker bearer auth.
 *
 * The codec is pure: no Android dependency, no Room, no DAOs. It is
 * unit-tested with a round-trip test in the JVM source set.
 *
 * Tampering with `data` after sign-off is rejected by [decode] (the
 * HMAC check is constant-time). A wrong key likewise fails the check
 * before any deserialization happens, so we never feed attacker-
 * controlled JSON to kotlinx-serialization.
 */
internal object SneakernetCodec {

    const val SCHEME = "fairshare"
    const val SYNC_HOST = "sync"
    const val JOIN_HOST = "join"
    private const val MAC_INFO = "fairshare-sneakernet-mac"

    /** Returned by [decode] on a valid bundle. */
    data class Decoded(val eventId: String, val ops: List<Operation>)

    /** Returned by [decodeJoin] on a valid invitation. */
    data class DecodedJoin(
        val eventId: String,
        val eventKey: ByteArray,
        val ops: List<Operation>,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DecodedJoin) return false
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
     * Builds a fairshare://sync URL carrying [ops] (which must all
     * share [eventId]). [eventKey] is the 32-byte event secret stored
     * locally in the EventEntity.
     */
    fun encode(eventId: String, ops: List<Operation>, eventKey: ByteArray): String {
        require(ops.all { it.eventId == eventId }) {
            "SneakernetCodec.encode: all ops must share eventId=$eventId"
        }
        val jsonBytes = json.encodeToString(ListSerializer(Operation.serializer()), ops)
            .toByteArray(Charsets.UTF_8)
        val gzipped = gzip(jsonBytes)
        val data = base64UrlEncode(gzipped)
        val mac = base64UrlEncode(
            SyncCrypto.hmacSha256(macKey(eventKey), data.toByteArray(Charsets.US_ASCII)),
        )
        // Manual URL composition: eventId is a UUID, data/sig are
        // base64url (URL-safe alphabet) so no further escaping needed.
        return "$SCHEME://$SYNC_HOST?event=$eventId&data=$data&sig=$mac"
    }

    /**
     * Parses, verifies, and decodes a fairshare://sync URL. Returns a
     * [DecodeError] (left) or the decoded [Decoded] (right) via the
     * `Result` type to keep the surface small.
     */
    fun decode(url: String, eventKey: ByteArray): Result<Decoded> {
        val params = parseSyncUrl(url) ?: return Result.failure(
            DecodeException(DecodeError.MalformedUrl),
        )
        val eventId = params["event"] ?: return Result.failure(
            DecodeException(DecodeError.MissingFields),
        )
        val data = params["data"] ?: return Result.failure(
            DecodeException(DecodeError.MissingFields),
        )
        val sig = params["sig"] ?: return Result.failure(
            DecodeException(DecodeError.MissingFields),
        )

        val expected = SyncCrypto.hmacSha256(
            macKey(eventKey),
            data.toByteArray(Charsets.US_ASCII),
        )
        val provided = runCatching { base64UrlDecode(sig) }
            .getOrElse {
                return Result.failure(DecodeException(DecodeError.SignatureMismatch))
            }
        if (!SyncCrypto.constantTimeEquals(expected, provided)) {
            return Result.failure(DecodeException(DecodeError.SignatureMismatch))
        }

        return runCatching {
            val gzipped = base64UrlDecode(data)
            val payload = gunzip(gzipped).toString(Charsets.UTF_8)
            val ops = json.decodeFromString(
                ListSerializer(Operation.serializer()),
                payload,
            )
            require(ops.all { it.eventId == eventId }) {
                "decoded ops carry an eventId different from the URL"
            }
            Decoded(eventId, ops)
        }.recoverCatching {
            throw DecodeException(DecodeError.PayloadInvalid(it))
        }
    }

    private fun macKey(eventKey: ByteArray): ByteArray =
        SyncCrypto.deriveSneakernetMacKey(eventKey)

    /**
     * Builds a `fairshare://join` invitation URL. Unlike [encode],
     * the URL carries the 32-byte [eventKey] itself so a fresh device
     * can bootstrap without out-of-band key exchange. The seed is
     * typically the whole op log, so the joining device materializes
     * the event to parity in a single import.
     */
    fun encodeJoin(eventId: String, ops: List<Operation>, eventKey: ByteArray): String {
        require(eventKey.size == 32) { "eventKey must be 32 bytes, got ${eventKey.size}" }
        require(ops.all { it.eventId == eventId }) {
            "SneakernetCodec.encodeJoin: all ops must share eventId=$eventId"
        }
        val seedBytes = json.encodeToString(ListSerializer(Operation.serializer()), ops)
            .toByteArray(Charsets.UTF_8)
        val seed = base64UrlEncode(gzip(seedBytes))
        val key = base64UrlEncode(eventKey)
        val mac = base64UrlEncode(
            SyncCrypto.hmacSha256(macKey(eventKey), seed.toByteArray(Charsets.US_ASCII)),
        )
        return "$SCHEME://$JOIN_HOST?event=$eventId&key=$key&seed=$seed&sig=$mac"
    }

    /** Parses an invitation URL, verifies its HMAC, returns the bundle. */
    fun decodeJoin(url: String): Result<DecodedJoin> {
        val params = parseJoinUrl(url) ?: return Result.failure(
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
            macKey(eventKey),
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
            DecodedJoin(eventId = eventId, eventKey = eventKey, ops = ops)
        }.recoverCatching {
            throw DecodeException(DecodeError.PayloadInvalid(it))
        }
    }

    private fun parseSyncUrl(url: String): Map<String, String>? =
        parseQuery(url, "$SCHEME://$SYNC_HOST?")

    private fun parseJoinUrl(url: String): Map<String, String>? =
        parseQuery(url, "$SCHEME://$JOIN_HOST?")

    private fun parseQuery(url: String, prefix: String): Map<String, String>? {
        if (!url.startsWith(prefix)) return null
        val query = url.substring(prefix.length)
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

/** Wraps a [SneakernetCodec.DecodeError] inside a `Throwable` for `Result.failure`. */
internal class DecodeException(val error: SneakernetCodec.DecodeError) : Exception(error.toString())
