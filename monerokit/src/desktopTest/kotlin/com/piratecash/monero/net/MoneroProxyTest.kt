package com.piratecash.monero.net

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class MoneroProxyTest {
    @After
    fun tearDown() {
        System.clearProperty("socksProxyHost")
        System.clearProperty("socksProxyPort")
    }

    private fun proxyFor(host: String?, port: String?): String {
        host?.let { System.setProperty("socksProxyHost", it) }
        port?.let { System.setProperty("socksProxyPort", it) }
        return MoneroProxy.current()
    }

    @Test
    fun current_ipv4AndPort_returnsHostPort() {
        assertEquals("127.0.0.1:9050", proxyFor("127.0.0.1", "9050"))
    }

    @Test
    fun current_noProperties_returnsDirect() {
        assertEquals("", proxyFor(null, null))
    }

    @Test
    fun current_emptyProperties_returnsDirect() {
        assertEquals("", proxyFor("", ""))
    }

    @Test
    fun current_hostname_returnsDirect() {
        assertEquals("", proxyFor("localhost", "9050"))
    }

    @Test
    fun current_ipv6_returnsDirect() {
        assertEquals("", proxyFor("::1", "9050"))
    }

    @Test
    fun current_ipv4OutOfRangeOctet_returnsDirect() {
        assertEquals("", proxyFor("127.0.0.256", "9050"))
    }

    @Test
    fun current_portZero_returnsDirect() {
        assertEquals("", proxyFor("127.0.0.1", "0"))
    }

    @Test
    fun current_portAboveRange_returnsDirect() {
        assertEquals("", proxyFor("127.0.0.1", "65536"))
    }

    @Test
    fun current_portNotNumber_returnsDirect() {
        assertEquals("", proxyFor("127.0.0.1", "tor"))
    }

    @Test
    fun current_missingPort_returnsDirect() {
        assertEquals("", proxyFor("127.0.0.1", null))
    }
}
