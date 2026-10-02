package com.m2049r.xmrwallet.data

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.piratecash.monero.MoneroNative
import io.mockk.every
import io.mockk.just
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket

class NodeInfoTest {
    private val messages = mutableListOf<String>()
    private val previousWriters = Logger.config.logWriterList
    private val previousSeverity = Logger.config.minSeverity

    @Before
    fun setUp() {
        // Node() asks WalletManager for the network type; its static initializer loads libmonerujo.
        mockkStatic(MoneroNative::class)
        every { MoneroNative.load() } just runs
        Logger.setMinSeverity(Severity.Verbose)
        Logger.setLogWriters(object : LogWriter() {
            override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
                messages += message
            }
        })
    }

    @After
    fun tearDown() {
        Logger.setLogWriters(previousWriters)
        Logger.setMinSeverity(previousSeverity)
        unmockkStatic(MoneroNative::class)
    }

    @Test
    fun testRpcService_nodeWithCredentials_logsNoPassword() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val node = NodeInfo("user:$PASSWORD@127.0.0.1:$closedPort/mainnet")

        assertFalse(node.testRpcService())
        assertTrue(messages.isNotEmpty())
        assertTrue(messages.toString(), messages.none { PASSWORD in it })
    }

    private companion object {
        const val PASSWORD = "s3cretPassw0rd"
    }
}
