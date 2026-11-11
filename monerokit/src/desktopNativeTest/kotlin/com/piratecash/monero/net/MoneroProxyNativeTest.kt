package com.piratecash.monero.net

import com.m2049r.xmrwallet.data.Node
import com.m2049r.xmrwallet.model.Wallet
import com.m2049r.xmrwallet.model.WalletManager
import com.piratecash.monero.closedLocalPort
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Proves wallet2 dials the proxy handed to `Wallet.init`, using a local socket as a fake SOCKS server. */
class MoneroProxyNativeTest {
    @get:Rule
    val temp = TemporaryFolder()

    private var wallet: Wallet? = null

    @After
    fun tearDown() {
        System.clearProperty("socksProxyHost")
        System.clearProperty("socksProxyPort")
        wallet?.let { WalletManager.getInstance().close(it) }
    }

    @Test
    fun init_systemSocksProxy_connectsThroughProxy() {
        fakeSocks().use { socks ->
            useSystemProxy(socks)

            val worker = startDaemonCalls()

            socks.accept().close()
            worker.join(TIMEOUT_MS)
            assertFalse(worker.isAlive)
        }
    }

    @Test
    fun init_systemSocksProxy_routesManagerDaemonCallsThroughProxy() {
        fakeSocks().use { socks ->
            useSystemProxy(socks)
            val dialed = AtomicInteger()
            thread(isDaemon = true) {
                try {
                    while (true) socks.accept().use { dialed.incrementAndGet() }
                } catch (_: IOException) {
                }
            }
            newWallet().init(0)
            val dialedByWallet = dialed.get()

            WalletManager.getInstance().blockchainHeight

            assertTrue(dialed.get() > dialedByWallet)
        }
    }

    @Test
    fun init_noProxy_leavesProxyPortUnused() {
        fakeSocks().use { socks ->
            socks.soTimeout = 3_000

            val worker = startDaemonCalls()

            assertThrows(SocketTimeoutException::class.java) { socks.accept() }
            worker.join(TIMEOUT_MS)
        }
    }

    private fun fakeSocks() = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).apply { soTimeout = TIMEOUT_MS.toInt() }

    private fun useSystemProxy(socks: ServerSocket) {
        System.setProperty("socksProxyHost", "127.0.0.1")
        System.setProperty("socksProxyPort", socks.localPort.toString())
    }

    /** A new wallet against a daemon nobody listens on: every daemon request can only fail. */
    private fun newWallet(): Wallet {
        val walletManager = WalletManager.getInstance()
        walletManager.setDaemon(Node.fromString("127.0.0.1:${closedLocalPort()}"))
        return walletManager.createWallet(File(temp.root, "proxy"), "password", "English", 3_000_000)
            .also { wallet = it }
    }

    private fun startDaemonCalls(): Thread {
        val created = newWallet()
        return thread {
            created.init(0)
            created.daemonBlockChainHeight
        }
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}
