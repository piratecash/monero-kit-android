package com.m2049r.xmrwallet.model

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.m2049r.levin.util.NetCipherHelper
import com.m2049r.xmrwallet.data.Node
import com.m2049r.xmrwallet.service.MoneroWalletService
import com.m2049r.xmrwallet.util.Helper
import com.piratecash.monero.signer.HardwareKeyImageRefreshResult
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
        File("${walletFile.absolutePath}.new").deleteRecursively()
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

    @Test
    fun storeWithKeysSafe_persistsRestoreHeightAcrossNonSavingClose() {
        val wallet = createWallet()
        val restoreHeight = 123_456L
        wallet.setRestoreHeight(restoreHeight)

        assertEquals(0, wallet.storeWithKeysSafe())
        assertTrue(wallet.close(false))
        val reopened = walletManager.openWallet(walletFile.absolutePath, "")

        assertEquals(restoreHeight, reopened.restoreHeight)
    }

    @Test
    fun storeWithKeysSafe_cacheNewFailureAfterKeysRewrite_returnsFailureAndNonSavingCloseLeavesNoCacheCommit() {
        val wallet = createWallet()
        val oldCacheLength = walletFile.length()
        val restoreHeight = 234_567L
        wallet.setRestoreHeight(restoreHeight)

        // wallet2::storeWithKeys() writes keys first, then writes cache to
        // <wallet>.new. A directory at that cache-temp path makes the second
        // step fail deterministically without preventing the keys rewrite.
        val cacheNew = File("${walletFile.absolutePath}.new")
        assertTrue(cacheNew.mkdir())

        assertNotEquals("cache failure must not report a committed store", 0, wallet.storeWithKeysSafe())
        assertTrue(wallet.close(false))
        assertTrue("no cache replacement occurred", cacheNew.isDirectory)
        assertEquals("close(false) must not perform a second cache save", oldCacheLength, walletFile.length())

        assertTrue(cacheNew.delete())
        val reopened = walletManager.openWallet(walletFile.absolutePath, "")
        assertEquals("keys rewrite happened before the forced cache failure", restoreHeight, reopened.restoreHeight)
    }

    @Test
    fun refreshWithHardwareKeyImages_softwareWalletFailsBeforeSignerUse() {
        val wallet = createWallet()

        try {
            wallet.refreshWithHardwareKeyImages(HardwareKeyImageRefreshResult.Request(
                HardwareKeyImageRefreshResult.Mode.Continue,
            ))
            fail("Software wallet must not enter hardware refresh")
        } catch (_: IllegalStateException) {
            // JNI rejects the device before beginning an external-signer operation.
        }
    }

    @Test
    fun startPaused_initFailureRetainsOwnershipUntilTheCallerPerformsOneNonSavingAbort() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        NetCipherHelper.createInstance(context)
        val name = "paused-start-${System.nanoTime()}"
        val path = Helper.getWalletFile(context, name)
        val created = walletManager.createWallet(path, "", "English", 0)
        assertTrue(created.close(true))
        walletManager.setDaemon(requireNotNull(Node.fromString("127.0.0.1:1")))
        try {
            val service = MoneroWalletService(context)
            val status = requireNotNull(service.startPaused(name, ""))

            assertTrue("controlled startup must return its non-OK init status", !status.isOk)
            assertTrue("paused startup keeps the caller-owned wallet", walletManager.wallet != null)
            assertTrue("the controlled caller owns the one non-saving abort", service.stop(false))
            assertNull(walletManager.wallet)
        } finally {
            walletManager.wallet?.close(false)
            walletManager.setDaemon(null)
            path.delete()
            File("${path.absolutePath}.keys").delete()
        }
    }

    @Test
    fun startPaused_openFailureReturnsOriginalStatusUntilTheCallerPerformsOneNonSavingAbort() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "paused-open-${System.nanoTime()}"
        val path = Helper.getWalletFile(context, name)
        val created = walletManager.createWallet(path, "correct-password", "English", 0)
        assertTrue(created.close(true))
        val expectedStatus = walletManager.openWallet(path.absolutePath, "wrong-password").status
        assertTrue("wrong password must produce an actual open failure", !expectedStatus.isOk)
        assertTrue(walletManager.wallet?.close(false) == true)

        try {
            val service = MoneroWalletService(context)
            val actualStatus = requireNotNull(service.startPaused(name, "wrong-password"))

            assertEquals(expectedStatus.status, actualStatus.status)
            assertEquals(expectedStatus.errorString, actualStatus.errorString)
            assertTrue("paused startup keeps the caller-owned wallet", walletManager.wallet != null)
            assertTrue("the controlled caller owns the one non-saving abort", service.stop(false))
            assertNull(walletManager.wallet)
        } finally {
            walletManager.wallet?.close(false)
            path.delete()
            File("${path.absolutePath}.keys").delete()
        }
    }

    private fun createWallet(): Wallet =
        walletManager.createWallet(walletFile, "", "English", 0)
}
