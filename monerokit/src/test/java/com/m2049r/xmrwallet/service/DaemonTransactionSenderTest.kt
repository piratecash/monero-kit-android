package com.m2049r.xmrwallet.service

import com.m2049r.levin.util.NetCipherHelper
import com.m2049r.xmrwallet.offline.MoneroRawTransactionError
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DaemonTransactionSenderTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // Route NetCipherHelper.Request through a plain client pointed at the mock server,
        // instead of the real Tor/clearnet singleton (never initialized in a JVM unit test).
        NetCipherHelper.Request.mockClient = OkHttpClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
        NetCipherHelper.Request.mockClient = null
    }

    @Test
    fun sendRawTransaction_accepted_postsHexEncodedBlob() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"OK","not_relayed":false}"""))

        send(byteArrayOf(0x0a, 0x1b.toByte(), 0xff.toByte()))

        val request = server.takeRequest()
        assertEquals("/sendrawtransaction", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("0a1bff", body.getString("tx_as_hex"))
        assertEquals(false, body.getBoolean("do_not_relay"))
    }

    @Test
    fun sendRawTransaction_notRelayed_throwsSubmitFailed() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"OK","not_relayed":true}"""))

        val error = assertSubmitFailed { send(byteArrayOf(1)) }

        assertTrue(error.message, error.message?.contains("not relayed") == true)
    }

    @Test
    fun sendRawTransaction_doubleSpendFlag_throwsWithReadableReason() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"Failed","double_spend":true}"""))

        val error = assertSubmitFailed { send(byteArrayOf(1)) }

        assertEquals("double spend", error.message)
    }

    @Test
    fun sendRawTransaction_reasonField_throwsWithDaemonReason() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"status":"Failed","reason":"Not enough outputs","overspend":true}""")
        )

        val error = assertSubmitFailed { send(byteArrayOf(1)) }

        assertEquals("Not enough outputs", error.message)
    }

    @Test
    fun sendRawTransaction_severalFlags_throwsWithAllReasons() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"status":"Failed","reason":"","fee_too_low":true,"too_big":true}""")
        )

        val error = assertSubmitFailed { send(byteArrayOf(1)) }

        assertEquals("too big, fee too low", error.message)
    }

    @Test
    fun sendRawTransaction_statusOnly_throwsWithStatus() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"Failed"}"""))

        val error = assertSubmitFailed { send(byteArrayOf(1)) }

        assertEquals("Failed", error.message)
    }

    @Test
    fun sendRawTransaction_httpError_throwsDaemonUnavailable() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        assertThrows(MoneroRawTransactionError.DaemonUnavailable::class.java) {
            runBlocking { send(byteArrayOf(1)) }
        }
        Unit
    }

    @Test
    fun sendRawTransaction_transportError_throwsDaemonUnavailable() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertThrows(MoneroRawTransactionError.DaemonUnavailable::class.java) {
            runBlocking { send(byteArrayOf(1)) }
        }
        Unit
    }

    @Test
    fun sendRawTransaction_severalBlobs_postsEachBlobSeparately() = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setBody("""{"status":"OK"}""")) }

        listOf(byteArrayOf(1), byteArrayOf(2, 2), byteArrayOf(3, 3, 3)).forEach { send(it) }

        val posted = List(3) { JSONObject(server.takeRequest().body.readUtf8()).getString("tx_as_hex") }
        assertEquals(listOf("01", "0202", "030303"), posted)
    }

    private suspend fun send(blob: ByteArray) = DaemonTransactionSender.sendRawTransaction(
        blob = blob,
        daemonAddress = "127.0.0.1:${server.port}",
        username = "",
        password = "",
    )

    private fun assertSubmitFailed(block: suspend () -> Unit): MoneroRawTransactionError.SubmitFailed =
        assertThrows(MoneroRawTransactionError.SubmitFailed::class.java) { runBlocking { block() } }
}
