package com.fairshare.data.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smoke + known-answer tests for [SyncCrypto].
 *
 * RFC 5869 test vector A.1 is used to pin the HKDF-SHA256 output so a
 * regression in the helper would fail loudly. The HMAC primitive is
 * covered transitively by HKDF (it uses HMAC internally) plus a direct
 * known-answer round-trip.
 */
class SyncCryptoTest {

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "").replace("\n", "")
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /** RFC 5869 §A.1: HKDF-SHA256 basic test case. */
    @Test
    fun `hkdfSha256 matches RFC 5869 test vector A1`() {
        val ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val expected = hex(
            "3cb25f25faacd57a90434f64d0362f2a" +
                "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865",
        )
        val actual = SyncCrypto.hkdfSha256(ikm, info, outputLength = 42, salt = salt)
        assertArrayEquals(expected, actual)
    }

    @Test
    fun `hkdf with empty salt produces deterministic output of the requested length`() {
        val key = ByteArray(32) { it.toByte() }
        val a = SyncCrypto.hkdfSha256(key, "info".toByteArray(), outputLength = 32)
        val b = SyncCrypto.hkdfSha256(key, "info".toByteArray(), outputLength = 32)
        assertArrayEquals(a, b)
        assertEquals(32, a.size)
    }

    @Test
    fun `different info strings produce different derived keys`() {
        val key = ByteArray(32) { 7 }
        val a = SyncCrypto.hkdfSha256(key, "fairshare-sneakernet-mac".toByteArray())
        val b = SyncCrypto.hkdfSha256(key, "fairshare-worker-auth".toByteArray())
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `hmacSha256 round-trips`() {
        val key = "secret-key".toByteArray()
        val data = "hello-fairshare".toByteArray()
        val a = SyncCrypto.hmacSha256(key, data)
        val b = SyncCrypto.hmacSha256(key, data)
        assertArrayEquals(a, b)
        assertEquals(32, a.size)
        val tampered = SyncCrypto.hmacSha256(key, "hello-fairshore".toByteArray())
        assertNotEquals(a.toList(), tampered.toList())
    }

    @Test
    fun `constantTimeEquals returns true only for identical buffers`() {
        val a = ByteArray(32) { it.toByte() }
        val b = a.copyOf()
        assertTrue(SyncCrypto.constantTimeEquals(a, b))
        b[15] = (b[15] + 1).toByte()
        assertFalse(SyncCrypto.constantTimeEquals(a, b))
        assertFalse(SyncCrypto.constantTimeEquals(a, ByteArray(31)))
    }

    // ---------- AES-256-GCM ----------

    @Test
    fun `aesGcm round-trip recovers plaintext`() {
        val key = ByteArray(32) { (it * 7 + 3).toByte() }
        val nonce = ByteArray(SyncCrypto.GCM_NONCE_LEN) { (it * 5).toByte() }
        val plaintext = "fairshare-op-payload".toByteArray()
        val ct = SyncCrypto.aesGcmEncrypt(key, nonce, plaintext)
        // Ciphertext == plaintext.length + 16-byte tag.
        assertEquals(plaintext.size + SyncCrypto.GCM_TAG_BITS / 8, ct.size)
        val pt = SyncCrypto.aesGcmDecrypt(key, nonce, ct)
        assertArrayEquals(plaintext, pt)
    }

    @Test(expected = javax.crypto.AEADBadTagException::class)
    fun `aesGcm rejects tampered ciphertext`() {
        val key = ByteArray(32) { 1 }
        val nonce = ByteArray(SyncCrypto.GCM_NONCE_LEN) { 2 }
        val ct = SyncCrypto.aesGcmEncrypt(key, nonce, "hello".toByteArray())
        // Flip a bit in the ciphertext body (not the tag).
        ct[0] = (ct[0].toInt() xor 0x01).toByte()
        SyncCrypto.aesGcmDecrypt(key, nonce, ct)
    }

    @Test(expected = javax.crypto.AEADBadTagException::class)
    fun `aesGcm rejects wrong key`() {
        val key = ByteArray(32) { 1 }
        val nonce = ByteArray(SyncCrypto.GCM_NONCE_LEN) { 2 }
        val ct = SyncCrypto.aesGcmEncrypt(key, nonce, "hello".toByteArray())
        val wrong = ByteArray(32) { 99 }
        SyncCrypto.aesGcmDecrypt(wrong, nonce, ct)
    }

    @Test
    fun `aesGcm rejects invalid key or nonce size`() {
        try {
            SyncCrypto.aesGcmEncrypt(ByteArray(16), ByteArray(12), ByteArray(0))
            error("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        try {
            SyncCrypto.aesGcmEncrypt(ByteArray(32), ByteArray(8), ByteArray(0))
            error("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    // ---------- Sub-key derivation + Worker bearer ----------

    @Test
    fun `sub-key derivation produces distinct 32-byte keys`() {
        val eventKey = ByteArray(32) { it.toByte() }
        val mac = SyncCrypto.deriveInvitationMacKey(eventKey)
        val auth = SyncCrypto.deriveWorkerAuthKey(eventKey)
        val cipher = SyncCrypto.deriveCloudCipherKey(eventKey)
        assertEquals(32, mac.size)
        assertEquals(32, auth.size)
        assertEquals(32, cipher.size)
        assertFalse(mac.contentEquals(auth))
        assertFalse(mac.contentEquals(cipher))
        assertFalse(auth.contentEquals(cipher))
    }

    @Test
    fun `computeWorkerBearer is 64 lowercase hex chars and deterministic`() {
        val eventKey = ByteArray(32) { (it + 1).toByte() }
        val a = SyncCrypto.computeWorkerBearer(eventKey, "evt-1")
        val b = SyncCrypto.computeWorkerBearer(eventKey, "evt-1")
        assertEquals(a, b)
        assertEquals(64, a.length)
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `computeWorkerBearer differs per event and per key`() {
        val k1 = ByteArray(32) { 1 }
        val k2 = ByteArray(32) { 2 }
        assertNotEquals(
            SyncCrypto.computeWorkerBearer(k1, "evt-1"),
            SyncCrypto.computeWorkerBearer(k1, "evt-2"),
        )
        assertNotEquals(
            SyncCrypto.computeWorkerBearer(k1, "evt-1"),
            SyncCrypto.computeWorkerBearer(k2, "evt-1"),
        )
    }
}
