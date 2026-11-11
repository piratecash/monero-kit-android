package com.m2049r.xmrwallet.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.m2049r.xmrwallet.data.Node
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WalletManagerDaemonAddressTest {
    private val walletManager = WalletManager.getInstance()

    @After
    fun tearDown() {
        walletManager.setDaemon(null)
    }

    @Test
    fun setDaemon_retainsHostnameForRpcAndResolvedAddressForNativeWallet() {
        val node = requireNotNull(Node.fromString("localhost:18081"))
        walletManager.setDaemon(node)

        assertEquals("localhost:18081", walletManager.daemonRpcAddress)
        assertEquals(node.address, walletManager.daemonAddress)
        assertNotEquals(walletManager.daemonRpcAddress, walletManager.daemonAddress)
    }
}
