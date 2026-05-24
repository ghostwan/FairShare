package com.fairshare.data.sync

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal HKDF-SHA256 (RFC 5869), HMAC-SHA256 and AES-256-GCM helpers
 * used by the sync engine. Pure JVM (`javax.crypto`), no Android
 * dependency, so it lives in the standard JVM source set and is
 * unit-tested directly.
 *
 * Reference: DESIGN.md §7. The event encryption key is 32 random bytes
 * stored locally in [com.fairshare.data.local.entity.EventEntity] and
 * shared via the invitation URL. From that single secret, derived sub-
 * keys are produced for:
 *
 *   - Invitation bundle integrity (`HKDF(key, "fairshare-invitation-mac")`)
 *   - Worker bearer auth          (`HKDF(key, "fairshare-worker-auth")`)
 *   - Cloud ciphertext            (`HKDF(key, "fairshare-cloud-cipher")`)
 *
 * Domain-separating sub-keys this way means a leak of an invitation
 * HMAC token doesn't reveal anything usable against the Worker, and
 * vice-versa.
 */
internal object SyncCrypto {

    private const val HMAC_ALG = "HmacSHA256"
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val HASH_LEN = 32 // SHA-256 output size
    const val GCM_NONCE_LEN = 12
    const val GCM_TAG_BITS = 128

    /** HMAC-SHA256(key, data). */
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALG)
        mac.init(SecretKeySpec(key, HMAC_ALG))
        return mac.doFinal(data)
    }

    /**
     * HKDF-SHA256 with empty salt. Returns [outputLength] bytes.
     *
     * For our two use cases we always derive exactly 32 bytes, but the
     * length parameter is kept so the helper stays generic.
     */
    fun hkdfSha256(
        inputKey: ByteArray,
        info: ByteArray,
        outputLength: Int = HASH_LEN,
        salt: ByteArray = ByteArray(HASH_LEN),
    ): ByteArray {
        require(outputLength in 1..(255 * HASH_LEN)) {
            "HKDF outputLength out of range: $outputLength"
        }

        // Extract
        val prk = hmacSha256(salt, inputKey)

        // Expand
        val out = ByteArray(outputLength)
        var previous = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < outputLength) {
            val data = previous + info + byteArrayOf(counter.toByte())
            previous = hmacSha256(prk, data)
            val take = minOf(HASH_LEN, outputLength - written)
            System.arraycopy(previous, 0, out, written, take)
            written += take
            counter++
        }
        return out
    }

    /** Constant-time byte-array equality, to avoid HMAC timing attacks. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    /**
     * AES-256-GCM encrypt. [nonce] must be 12 bytes (a cryptographically
     * fresh random per call — reusing a nonce with the same key
     * catastrophically breaks GCM). Returns ciphertext || tag.
     */
    fun aesGcmEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == 32) { "AES-256 key must be 32 bytes" }
        require(nonce.size == GCM_NONCE_LEN) { "GCM nonce must be $GCM_NONCE_LEN bytes" }
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        return cipher.doFinal(plaintext)
    }

    /**
     * AES-256-GCM decrypt. Throws [javax.crypto.AEADBadTagException]
     * when the ciphertext was tampered with or the wrong key is used.
     */
    fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        require(key.size == 32) { "AES-256 key must be 32 bytes" }
        require(nonce.size == GCM_NONCE_LEN) { "GCM nonce must be $GCM_NONCE_LEN bytes" }
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        return cipher.doFinal(ciphertext)
    }

    // ---------- Sub-key derivation ----------

    private const val MAC_INFO = "fairshare-invitation-mac"
    private const val WORKER_AUTH_INFO = "fairshare-worker-auth"
    private const val CLOUD_CIPHER_INFO = "fairshare-cloud-cipher"

    /** 32-byte HMAC key for invitation bundle integrity. */
    fun deriveInvitationMacKey(eventKey: ByteArray): ByteArray =
        hkdfSha256(eventKey, MAC_INFO.toByteArray(Charsets.US_ASCII))

    /** 32-byte HMAC key for Worker bearer auth. */
    fun deriveWorkerAuthKey(eventKey: ByteArray): ByteArray =
        hkdfSha256(eventKey, WORKER_AUTH_INFO.toByteArray(Charsets.US_ASCII))

    /** 32-byte AES key for cloud op encryption. */
    fun deriveCloudCipherKey(eventKey: ByteArray): ByteArray =
        hkdfSha256(eventKey, CLOUD_CIPHER_INFO.toByteArray(Charsets.US_ASCII))

    /**
     * Lowercase hex of `HMAC-SHA256(workerAuthKey, eventId)` — 64 chars.
     * This is the static per-event bearer accepted by the Worker
     * (DESIGN.md §7). The bearer is bound to a single eventId, so leaking
     * it grants access to that event's encrypted log only.
     */
    fun computeWorkerBearer(eventKey: ByteArray, eventId: String): String {
        val authKey = deriveWorkerAuthKey(eventKey)
        val mac = hmacSha256(authKey, eventId.toByteArray(Charsets.UTF_8))
        return mac.toHex()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
