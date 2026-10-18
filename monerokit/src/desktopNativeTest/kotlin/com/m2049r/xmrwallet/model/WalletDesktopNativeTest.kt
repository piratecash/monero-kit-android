package com.m2049r.xmrwallet.model

import com.m2049r.xmrwallet.data.Node
import com.piratecash.monero.closedLocalPort
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.io.File

class WalletDesktopNativeTest {
    @get:Rule
    val temp = TemporaryFolder()

    @get:Rule
    val timeout: Timeout = Timeout.seconds(300)

    private val walletManager = WalletManager.getInstance()

    @After
    fun tearDown() {
        walletManager.wallet?.close(false)
        walletManager.setDaemon(null)
    }

    @Test
    fun openWallet_storedAndClosed_reopensOnlyWithItsPassword() {
        val path = walletFile("lifecycle")
        val created = createWallet(path)
        val address = created.address
        assertTrue(created.store())
        assertTrue(created.close())

        val wrong = walletManager.openWallet(path.absolutePath, "wrong-password")
        assertFalse(wrong.status.isOk)
        assertTrue(wrong.close())

        val reopened = walletManager.openWallet(path.absolutePath, PASSWORD)
        assertTrue(reopened.status.toString(), reopened.status.isOk)
        assertEquals(address, reopened.address)
    }

    @Test
    fun recoveryWallet_seedOfCreatedWallet_restoresSameAddress() {
        val original = createWallet(walletFile("original"))
        val seed = original.getSeed("")
        val address = original.address
        assertTrue(original.close())
        assertEquals(25, seed.split(' ').size)

        val restored = walletManager.recoveryWallet(walletFile("restored"), PASSWORD, seed, "", 0)

        assertTrue(restored.status.toString(), restored.status.isOk)
        assertEquals(address, restored.address)
    }

    @Test
    fun close_storeDuringRefreshAgainstDeadDaemon_reopensEveryRound() {
        val path = walletFile("stress")
        val created = createWallet(path)
        val address = created.address
        assertTrue(created.close(true))
        walletManager.setDaemon(Node.fromString("127.0.0.1:${closedLocalPort()}"))

        repeat(STRESS_ROUNDS) { round ->
            val wallet = walletManager.openWallet(path.absolutePath, PASSWORD)
            assertTrue("round $round: ${wallet.status}", wallet.status.isOk)
            wallet.init(0)
            wallet.startRefresh()
            wallet.refreshAsync()
            // Shift where store and close land inside the refresh loop.
            Thread.sleep(round % 5 * 10L)
            assertTrue("round $round", wallet.store())
            assertTrue("round $round", wallet.close(true))
        }

        val reopened = walletManager.openWallet(path.absolutePath, PASSWORD)
        assertTrue(reopened.status.toString(), reopened.status.isOk)
        assertEquals(address, reopened.address)
    }

    @Test
    fun emptyWallet_statusCoinsAndHistory_reportNothing() {
        val wallet = createWallet(walletFile("empty"))

        assertEquals(Wallet.StatusEnum.Status_Ok, wallet.status.status)
        assertEquals(0L, wallet.balanceAll)
        assertTrue(wallet.getCoinsInfos(false).isEmpty())
        wallet.refreshHistory()
        assertEquals(0, wallet.history.count)
        assertTrue(wallet.history.all.isEmpty())
    }

    private fun walletFile(name: String) = File(temp.root, name)

    private fun createWallet(path: File): Wallet =
        walletManager.createWallet(path, PASSWORD, "English", 0).also {
            assertTrue(it.status.toString(), it.status.isOk)
        }

    private companion object {
        const val PASSWORD = "native-test-password"
        const val STRESS_ROUNDS = 20
    }
}
