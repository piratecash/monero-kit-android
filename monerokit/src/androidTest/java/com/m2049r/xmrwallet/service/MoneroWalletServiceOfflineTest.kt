package com.m2049r.xmrwallet.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.m2049r.levin.util.NetCipherHelper
import com.m2049r.xmrwallet.data.DefaultNodes
import com.m2049r.xmrwallet.data.Node
import com.m2049r.xmrwallet.model.Wallet
import com.m2049r.xmrwallet.model.WalletManager
import com.m2049r.xmrwallet.util.Helper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MoneroWalletServiceOfflineTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val walletManager = WalletManager.getInstance()
    private val walletName = "offline-open-${System.nanoTime()}"
    private val walletFile = Helper.getWalletFile(context, walletName)

    @Test
    fun startOffline_daemonNotSet_opensWalletAndKeepsStoredDataReadable() {
        createWallet()
        walletManager.setDaemon(null)
        val service = MoneroWalletService(context)

        try {
            // With no daemon set every daemon call throws, so reaching Opened proves none was made.
            assertEquals(LocalOpenResult.Opened, service.startOffline(walletName, ""))

            val wallet = requireNotNull(walletManager.wallet)
            assertEquals(0L, wallet.balanceAll)
            assertNotNull(wallet.history)
        } finally {
            service.stop(false)
            deleteWalletFiles()
        }
    }

    @Test
    fun connectDaemon_unreachableDaemon_failsAndLeavesWalletOpenAndReadable() {
        NetCipherHelper.createInstance(context)
        createWallet()
        walletManager.setDaemon(null)
        val service = MoneroWalletService(context)

        try {
            assertEquals(LocalOpenResult.Opened, service.startOffline(walletName, ""))
            walletManager.setDaemon(requireNotNull(Node.fromString("127.0.0.1:1")))

            val result = service.connectDaemon()

            assertTrue("unreachable daemon must fail", result is DaemonConnectResult.Failed)
            val wallet = requireNotNull(walletManager.wallet)
            assertEquals(0L, wallet.balanceAll)
            assertNotNull(wallet.history)
        } finally {
            service.stop(false)
            walletManager.setDaemon(null)
            deleteWalletFiles()
        }
    }

    /** Live integration: needs one of [CLEARNET_NODES] reachable, so it is not a deterministic gate. */
    @Test
    fun connectDaemonAndResume_liveNode_restartsRefresh() {
        NetCipherHelper.createInstance(context)
        createWallet()
        walletManager.setDaemon(null)
        val service = MoneroWalletService(context)
        val refreshed = CountDownLatch(1)

        try {
            assertEquals(LocalOpenResult.Opened, service.startOffline(walletName, ""))

            val connectedNode = CLEARNET_NODES.firstOrNull { node ->
                // Without DNS the node does not even parse — that is a skip, not a failure.
                val resolved = Node.fromString(node.uri) ?: return@firstOrNull false
                walletManager.setDaemon(resolved)
                service.connectDaemon() == DaemonConnectResult.Connected
            }
            // Skip rather than fail: an unreachable public node says nothing about this code.
            assumeTrue("no default node reachable: $CLEARNET_NODES", connectedNode != null)

            assertTrue(service.resume(refreshCountingObserver(refreshed)))
            assertTrue("resume() must restart refresh", refreshed.await(3, TimeUnit.MINUTES))
        } finally {
            service.stop(false)
            walletManager.setDaemon(null)
            deleteWalletFiles()
        }
    }

    @Test
    fun connectDaemon_globalWalletReplacedByAnother_reportsNoWallet() {
        createWallet()
        walletManager.setDaemon(null)
        val service = MoneroWalletService(context)
        val otherFile = Helper.getWalletFile(context, "$walletName-other")

        try {
            assertEquals(LocalOpenResult.Opened, service.startOffline(walletName, ""))
            // Anyone can replace the process-global wallet; this service still owns the old one.
            walletManager.createWallet(otherFile, "", "English", 0)

            assertEquals(DaemonConnectResult.NoWallet, service.connectDaemon())
        } finally {
            service.stop(false)
            walletManager.wallet?.close(false)
            deleteWalletFiles(otherFile)
            deleteWalletFiles()
        }
    }

    @Test
    fun stop_globalWalletReplacedByAnother_closesOwnedWalletOnly() {
        createWallet()
        walletManager.setDaemon(null)
        val service = MoneroWalletService(context)
        val otherFile = Helper.getWalletFile(context, "$walletName-other")
        var other: Wallet? = null

        try {
            assertEquals(LocalOpenResult.Opened, service.startOffline(walletName, ""))
            other = walletManager.createWallet(otherFile, "", "English", 0)

            assertTrue("stop() must close the wallet the service opened", service.stop(false))

            assertSame("the foreign wallet must stay open", other, walletManager.wallet)
            assertEquals(0L, requireNotNull(walletManager.wallet).balanceAll)
        } finally {
            other?.close(false)
            deleteWalletFiles(otherFile)
            deleteWalletFiles()
        }
    }

    @Test
    fun connectDaemon_walletNotOpen_reportsNoWallet() {
        walletManager.wallet?.close(false)
        assertNull(walletManager.wallet)

        assertEquals(DaemonConnectResult.NoWallet, MoneroWalletService(context).connectDaemon())
    }

    private fun createWallet() {
        val created = walletManager.createWallet(walletFile, "", "English", 0)
        assertTrue(created.close(true))
    }

    private fun deleteWalletFiles(file: File = walletFile) {
        file.delete()
        File("${file.absolutePath}.keys").delete()
        File("${file.absolutePath}.address.txt").delete()
    }

    private fun refreshCountingObserver(refreshed: CountDownLatch) =
        object : MoneroWalletService.Observer {
            override fun onRefreshed(wallet: Wallet?, full: Boolean): Boolean {
                refreshed.countDown()
                return true
            }

            override fun onProgress(text: String?) = Unit
            override fun onProgress(n: Int) = Unit
            override fun onWalletStarted(walletStatus: Wallet.Status?) = Unit
            override fun onWalletOpen(device: Wallet.Device?) = Unit
        }

    private companion object {
        private val CLEARNET_NODES = listOf(
            DefaultNodes.MONERODEVS,
            DefaultNodes.STORMYCLOUD,
            DefaultNodes.XMRROCKS,
        )
    }
}
