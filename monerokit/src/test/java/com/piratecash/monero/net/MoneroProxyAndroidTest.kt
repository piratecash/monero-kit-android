package com.piratecash.monero.net

import com.m2049r.levin.util.NetCipherHelper
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class MoneroProxyAndroidTest {
    @Before
    fun setUp() {
        mockkStatic(NetCipherHelper::class)
        System.setProperty("socksProxyHost", "127.0.0.1")
        System.setProperty("socksProxyPort", "9050")
    }

    @After
    fun tearDown() {
        unmockkStatic(NetCipherHelper::class)
        System.clearProperty("socksProxyHost")
        System.clearProperty("socksProxyPort")
    }

    @Test
    fun current_orbotProxy_winsOverSystemSocks() {
        every { NetCipherHelper.getProxy() } returns "10.0.0.2:9150"

        assertEquals("10.0.0.2:9150", MoneroProxy.current())
    }

    @Test
    fun current_noOrbotProxy_fallsBackToSystemSocks() {
        every { NetCipherHelper.getProxy() } returns ""

        assertEquals("127.0.0.1:9050", MoneroProxy.current())
    }
}
