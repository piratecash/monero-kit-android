package com.m2049r.xmrwallet.model

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.m2049r.xmrwallet.service.MoneroWalletService
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WalletNativeLifecycleTest {
    private val walletManager = WalletManager.getInstance()
    private val walletFile = File(
        ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir,
        "native-lifecycle-${System.nanoTime()}",
    )

    @After
    fun tearDown() {
        walletManager.wallet?.close(false)
        walletFile.delete()
        File("${walletFile.absolutePath}.keys").delete()
    }

    @Test
    fun pauseRefreshAndDrain_alreadyPaused_succeedsRepeatedly() {
        val wallet = createWallet()
        wallet.startRefresh()
        wallet.pauseRefresh()

        assertTrue(wallet.pauseRefreshAndDrain())
        assertTrue(wallet.pauseRefreshAndDrain())
    }

    @Test
    fun stop_managedWalletWithoutListener_closesWallet() {
        createWallet()
        val service = MoneroWalletService(ApplicationProvider.getApplicationContext())

        assertTrue(service.stop(false))
        assertNull(walletManager.wallet)
    }

    private fun createWallet(): Wallet =
        walletManager.createWallet(walletFile, "", "English", 0)
}
