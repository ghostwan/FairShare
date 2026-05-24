package com.fairshare.data.sync

import com.fairshare.domain.model.sync.OpPayload
import com.fairshare.domain.model.sync.Operation
import com.fairshare.domain.repository.CloudTransport.EncryptedOp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom

/**
 * Encrypts an [Operation]'s sensitive parts before it leaves the device
 * and reverses the operation on inbound ops (DESIGN.md §7).
 *
 * Wire shape:
 *
 * - **Cleartext metadata** (visible to the Worker): `opId`, `lamport`,
 *   `deviceId`. These are needed server-side for dedup, ordering, and
 *   the LWW tiebreaker — withholding them would force the Worker to
 *   decrypt or would break convergence.
 * - **Encrypted envelope** (`nonce` + `ciphertext`): everything else,
 *   i.e. `wallClockMs` plus the full [OpPayload] (entity snapshot or
 *   tombstone). The Worker stores both as opaque BLOBs.
 *
 * Cryptography: AES-256-GCM with a fresh 12-byte random nonce per op
 * and a 128-bit auth tag. The 32-byte key is `HKDF(eventKey,
 * "fairshare-cloud-cipher")`, derived via
 * [SyncCrypto.deriveCloudCipherKey]. Sub-key derivation isolates this
 * key from the sneakernet HMAC and Worker bearer keys, so leaking one
 * sub-key never compromises the others.
 *
 * Authenticated data: none. We deliberately do **not** authenticate
 * `opId`, `lamport`, `deviceId`, or `eventId` as GCM AAD because the
 * Worker may legitimately re-order or batch ops between push and pull,
 * and AAD checks add no real protection (a man-in-the-middle who
 * tampers with metadata can already drop ops or replay them; the CRDT
 * pipeline tolerates that). Replay or substitution attacks are blunted
 * by the Worker's `INSERT OR IGNORE` on `(event_id, op_id)` and by the
 * materializer's idempotency on `opId`.
 */
internal object CloudOpCodec {

    private val random = SecureRandom()

    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    /**
     * Inner envelope serialized inside the ciphertext. Bumping
     * [version] lets us add future fields (e.g. signature, schema id)
     * without breaking older clients reading old ops from the Worker.
     */
    @Serializable
    internal data class Envelope(
        val version: Int = CURRENT_VERSION,
        val wallClockMs: Long,
        val payload: OpPayload,
    ) {
        companion object {
            const val CURRENT_VERSION: Int = 1
        }
    }

    /** Encrypts [op] for the wire. The result is safe to POST as-is. */
    fun encrypt(op: Operation, cloudCipherKey: ByteArray): EncryptedOp {
        val envelopeJson = json.encodeToString(
            Envelope.serializer(),
            Envelope(wallClockMs = op.wallClockMs, payload = op.payload),
        )
        val nonce = ByteArray(SyncCrypto.GCM_NONCE_LEN).also(random::nextBytes)
        val ciphertext = SyncCrypto.aesGcmEncrypt(
            key = cloudCipherKey,
            nonce = nonce,
            plaintext = envelopeJson.toByteArray(Charsets.UTF_8),
        )
        return EncryptedOp(
            opId = op.opId,
            lamport = op.lamport,
            deviceId = op.deviceId,
            nonce = nonce,
            ciphertext = ciphertext,
        )
    }

    /**
     * Decrypts an [EncryptedOp] pulled from the Worker back into an
     * [Operation]. [eventId] is supplied separately because the
     * Worker pull response is implicitly scoped to one event (the URL
     * path) and we keep it out of the ciphertext to save bytes.
     *
     * Throws [javax.crypto.AEADBadTagException] on tampering or a
     * wrong key; the caller (sync coordinator) treats that as "skip
     * this op + log a warning", never as a hard failure for the whole
     * pull batch.
     */
    fun decrypt(
        enc: EncryptedOp,
        eventId: String,
        cloudCipherKey: ByteArray,
    ): Operation {
        val plaintext = SyncCrypto.aesGcmDecrypt(
            key = cloudCipherKey,
            nonce = enc.nonce,
            ciphertext = enc.ciphertext,
        )
        val envelope = json.decodeFromString(
            Envelope.serializer(),
            plaintext.toString(Charsets.UTF_8),
        )
        return Operation(
            opId = enc.opId,
            eventId = eventId,
            deviceId = enc.deviceId,
            lamport = enc.lamport,
            wallClockMs = envelope.wallClockMs,
            payload = envelope.payload,
        )
    }
}
