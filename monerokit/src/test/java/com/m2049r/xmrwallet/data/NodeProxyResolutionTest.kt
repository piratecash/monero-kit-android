package com.m2049r.xmrwallet.data

import com.piratecash.monero.MoneroNative
import io.mockk.every
import io.mockk.just
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

class NodeProxyResolutionTest {

    @Before
    fun setUp() {
        // Node asks WalletManager for the network type; its static initializer loads libmonerujo.
        mockkStatic(MoneroNative::class)
        every { MoneroNative.load() } just runs
        clearSocksProxy()
    }

    @After
    fun tearDown() {
        clearSocksProxy()
        unmockkStatic(MoneroNative::class)
    }

    @Test
    fun fromString_socksProxySet_keepsHostUnresolved() {
        setSocksProxy("127.0.0.1", "9050")

        // An unresolvable name: any local lookup would make fromString return null.
        val node = requireNotNull(Node.fromString("node.invalid:18081"))

        assertEquals("node.invalid", node.getHostAddress())
        assertEquals("node.invalid:18081", node.address)
        assertFalse(node.isOnion)
    }

    @Test
    fun fromString_noSocksProxy_resolvesHostLocally() {
        val node = requireNotNull(Node.fromString("localhost:18081"))

        assertTrue(InetAddress.getByName(node.getHostAddress()).isLoopbackAddress)
        assertNotEquals("localhost", node.getHostAddress())
    }

    @Test
    fun fromString_socksProxyUnusableByWallet_resolvesHostLocally() {
        setSocksProxy("proxy.example", "9050")

        val node = requireNotNull(Node.fromString("localhost:18081"))

        assertNotEquals("localhost", node.getHostAddress())
    }

    @Test
    fun fromString_onionHostWithSocksProxy_staysOnion() {
        setSocksProxy("127.0.0.1", "9050")

        val node = requireNotNull(Node.fromString("$ONION:18081"))

        assertTrue(node.isOnion)
        assertEquals(ONION, node.getHostAddress())
    }

    private fun setSocksProxy(host: String, port: String) {
        System.setProperty("socksProxyHost", host)
        System.setProperty("socksProxyPort", port)
    }

    private fun clearSocksProxy() {
        System.clearProperty("socksProxyHost")
        System.clearProperty("socksProxyPort")
    }

    private companion object {
        const val ONION = "xmrnodeabcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqr.onion"
    }
}
