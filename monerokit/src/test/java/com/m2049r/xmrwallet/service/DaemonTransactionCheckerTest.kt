package com.m2049r.xmrwallet.service

import com.m2049r.levin.util.NetCipherHelper
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class DaemonTransactionCheckerTest {
    private val txId = "a".repeat(64)
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

    private fun daemonAddress() = "127.0.0.1:${server.port}"

    @Test
    fun checkTransactionExists_slowDaemon_returnsFalseWithinBound() = runBlocking {
        val serverDelayMs = 1500L
        val checkTimeoutMs = 200L
        server.enqueue(
            MockResponse()
                .setHeadersDelay(serverDelayMs, TimeUnit.MILLISECONDS)
                .setBody("""{"txs":[{"tx_hash":"$txId"}]}""")
        )

        val start = System.currentTimeMillis()
        val result = DaemonTransactionChecker.checkTransactionExists(
            txId = txId,
            daemonAddress = daemonAddress(),
            username = "",
            password = "",
            timeoutMs = checkTimeoutMs,
        )
        val elapsedMs = System.currentTimeMillis() - start

        assertEquals(false, result)
        assertTrue(
            "expected the check to abort well before the ${serverDelayMs}ms server delay, took ${elapsedMs}ms",
            elapsedMs < serverDelayMs,
        )
    }

    @Test
    fun checkTransactionExists_txIdInResponse_returnsTrue() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"txs":[{"tx_hash":"$txId"}]}"""))

        val result = DaemonTransactionChecker.checkTransactionExists(
            txId = txId,
            daemonAddress = daemonAddress(),
            username = "",
            password = "",
        )

        assertEquals(true, result)
    }

    @Test
    fun checkTransactionExists_missedTx_returnsFalse() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"OK","missed_tx":["$txId"]}"""))

        val result = DaemonTransactionChecker.checkTransactionExists(
            txId = txId,
            daemonAddress = daemonAddress(),
            username = "",
            password = "",
        )

        assertEquals(false, result)
    }
}
