package com.fairshare.data.sync

import com.fairshare.domain.repository.CloudTransport.EncryptedOp
import com.fairshare.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Wire-level tests for [WorkerCloudTransport]. We exercise the JSON
 * encoder/decoder and the HTTP plumbing against a [MockWebServer] —
 * not the real Cloudflare endpoint — so the tests stay hermetic.
 */
class WorkerCloudTransportTest {

    private lateinit var server: MockWebServer
    private lateinit var settings: FakeSettings
    private lateinit var transport: WorkerCloudTransport

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        settings = FakeSettings(server.url("/").toString().trimEnd('/'))
        transport = WorkerCloudTransport(OkHttpClient(), settings)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun encryptedOp(opId: String, lamport: Long): EncryptedOp = EncryptedOp(
        opId = opId,
        lamport = lamport,
        deviceId = "dev-A",
        nonce = ByteArray(12) { it.toByte() },
        ciphertext = byteArrayOf(0x10, 0x20, 0x30),
    )

    @Test
    fun `push posts JSON with bearer and parses inserted count`() = runTest {
        server.enqueue(MockResponse().setBody("""{"inserted":2}""").addHeader("content-type", "application/json"))
        val result = transport.push(
            eventId = "evt-1",
            bearer = "deadbeef",
            ops = listOf(encryptedOp("op-1", 1), encryptedOp("op-2", 2)),
        ).getOrThrow()
        assertEquals(2, result.inserted)

        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/events/evt-1/ops", rec.path)
        assertEquals("Bearer deadbeef", rec.getHeader("authorization"))
        val body = rec.body.readUtf8()
        assertTrue("expected op-1 in body: $body", body.contains("\"opId\":\"op-1\""))
        assertTrue("expected op-2 in body: $body", body.contains("\"opId\":\"op-2\""))
        assertTrue("expected base64 nonce in body: $body", body.contains("\"nonce\":\""))
    }

    @Test
    fun `push surfaces HTTP errors as Result failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"bad_bearer"}"""))
        val result = transport.push("evt-1", "x", listOf(encryptedOp("op-1", 1)))
        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull())
    }

    @Test
    fun `pull builds since query and parses ops`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "ops": [
                    {"opId":"op-1","lamport":3,"deviceId":"dev-B","nonce":"AAECAwQFBgcICQoL","ciphertext":"EBAQ"}
                  ],
                  "nextSince": 3,
                  "hasMore": false
                }
                """.trimIndent(),
            ).addHeader("content-type", "application/json"),
        )
        val result = transport.pull("evt-1", "abcd", since = 0L, sinceOp = "").getOrThrow()
        assertEquals(1, result.ops.size)
        assertEquals("op-1", result.ops[0].opId)
        assertEquals(3L, result.ops[0].lamport)
        assertEquals(3L, result.nextSince)
        assertEquals(false, result.hasMore)
        // 12-byte nonce decoded from "AAECAwQFBgcICQoL".
        assertEquals(12, result.ops[0].nonce.size)
        assertEquals(0.toByte(), result.ops[0].nonce[0])
        assertEquals(11.toByte(), result.ops[0].nonce[11])

        val rec = server.takeRequest()
        assertEquals("GET", rec.method)
        assertEquals("/events/evt-1/ops?since=0", rec.path)
        assertEquals("Bearer abcd", rec.getHeader("authorization"))
    }

    @Test
    fun `pull handles empty page`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ops":[],"nextSince":42,"hasMore":false}"""))
        val result = transport.pull("evt-1", "abcd", since = 42L, sinceOp = "").getOrThrow()
        assertEquals(0, result.ops.size)
        assertEquals(42L, result.nextSince)
    }

    @Test
    fun `pull rejects malformed JSON as failure`() = runTest {
        server.enqueue(MockResponse().setBody("not-json"))
        val result = transport.pull("evt-1", "abcd", since = 0L, sinceOp = "")
        assertTrue(result.isFailure)
    }

    /**
     * Minimal in-memory [SettingsRepository] stand-in. Only
     * [cloudBaseUrl] is exercised; everything else throws so a future
     * accidental call shows up as a test failure rather than a
     * silent default.
     */
    private class FakeSettings(baseUrl: String) : SettingsRepository {
        private val base = MutableStateFlow(baseUrl)
        override val cloudBaseUrl: Flow<String> = base.asStateFlow()
        override suspend fun setCloudBaseUrl(value: String) { base.value = value }

        override val expandQuantities: Flow<Boolean> get() = error("unused")
        override suspend fun setExpandQuantities(value: Boolean) = error("unused")
        override val geminiApiKey: Flow<String> get() = error("unused")
        override suspend fun setGeminiApiKey(value: String) = error("unused")
        override val geminiModel: Flow<String> get() = error("unused")
        override suspend fun setGeminiModel(value: String) = error("unused")
        override val alwaysUseGemini: Flow<Boolean> get() = error("unused")
        override suspend fun setAlwaysUseGemini(value: Boolean) = error("unused")
    }
}
