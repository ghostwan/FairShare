package com.fairshare.data.sync

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal HKDF-SHA256 (RFC 5869) and HMAC-SHA256 helpers used by the
 * sync engine. Pure JVM (uses `javax.crypto`), no Android dependency,
 * so it lives in the standard JVM source set and is unit-tested
 * directly.
 *
 * Reference: DESIGN.md §7. The event encryption key is 32 random bytes
 * stored locally in [com.fairshare.data.local.entity.EventEntity] and
 * shared via the invitation URL. From that single secret, derived sub-
 * keys are produced for:
 *
 *   - Sneakernet bundle integrity (`HKDF(key, "fairshare-sneakernet-mac")`)
 *   - Worker bearer auth          (`HKDF(key, "fairshare-worker-auth")`)
 *
 * Domain-separating sub-keys this way means a leak of a sneakernet
 * HMAC token doesn't reveal anything usable against the Worker, and
 * vice-versa.
 */
internal object SyncCrypto {

    private const val HMAC_ALG = "HmacSHA256"
    private const val HASH_LEN = 32 // SHA-256 output size

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
}
