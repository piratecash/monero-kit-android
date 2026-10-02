package com.piratecash.monero

import com.m2049r.xmrwallet.model.WalletManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneroNativeTest {
    @Test
    fun load_supportedHost_exposesMoneroVersion() {
        assertTrue(MoneroNative.isHostSupported())

        MoneroNative.load()

        assertEquals("0.18.3.4", WalletManager.moneroVersion().substringBefore('-'))
    }
}
