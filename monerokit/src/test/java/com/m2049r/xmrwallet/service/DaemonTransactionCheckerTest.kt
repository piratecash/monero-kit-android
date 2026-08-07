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
    private val secondTxId = "b".repeat(64)
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
    fun checkKnownTransactions_slowDaemon_returnsEmptyWithinBound() = runBlocking {
        val serverDelayMs = 1500L
        val checkTimeoutMs = 200L
        server.enqueue(
            MockResponse()
                .setHeadersDelay(serverDelayMs, TimeUnit.MILLISECONDS)
                .setBody("""{"txs":[{"tx_hash":"$txId"}]}""")
        )

        val start = System.currentTimeMillis()
        val result = DaemonTransactionChecker.checkKnownTransactions(
            txIds = listOf(txId),
            daemonAddress = daemonAddress(),
            username = "",
            password = "",
            timeoutMs = checkTimeoutMs,
        )
        val elapsedMs = System.currentTimeMillis() - start

        assertEquals(emptySet<String>(), result)
        assertTrue(
            "expected the check to abort well before the ${serverDelayMs}ms server delay, took ${elapsedMs}ms",
            elapsedMs < serverDelayMs,
        )
    }

    @Test
    fun checkKnownTransactions_txIdInResponse_returnsQueriedId() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"txs":[{"tx_hash":"$txId"}]}"""))

        val result = DaemonTransactionChecker.checkKnownTransactions(
            txIds = listOf(txId),
            daemonAddress = daemonAddress(),
            username = "",
            password = "",
        )

        assertEquals(setOf(txId), result)
    }

    @Test
    fun checkKnownTransactions_missedTx_returnsEmpty() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"OK","missed_tx":["$txId"]}"""))

        val result = DaemonTransactionChecker.checkKnownTransactions(
            txIds = listOf(txId),
            daemonAddress = daemonAddress(),
            username = "",
            password = "",
        )

        assertEquals(emptySet<String>(), result)
    }

    @Test
    fun checkKnownTransactions_multipleIds_usesOneRequestWithCompleteBatch() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"txs":[{"tx_hash":"$secondTxId"}]}"""))
        val txIds = listOf(txId, secondTxId)

        val result = DaemonTransactionChecker.checkKnownTransactions(
            txIds = txIds,
            daemonAddress = daemonAddress(),
            username = "",
            password = "",
        )

        assertEquals(setOf(secondTxId), result)
        val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals("/get_transactions", request.path)
        val hashes = org.json.JSONObject(request.body.readUtf8()).getJSONArray("txs_hashes")
        assertEquals(txIds, (0 until hashes.length()).map(hashes::getString))
        assertEquals(1, server.requestCount)
    }
}
