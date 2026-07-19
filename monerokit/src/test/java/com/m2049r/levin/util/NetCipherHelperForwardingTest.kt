package com.m2049r.levin.util

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Guards the passive network-observer seam of [NetCipherHelper]: a supplied [EventListener.Factory]
 * is attached to every daemon call — both the zero-timeout fast path ([NetCipherHelper.Request.execute])
 * and the derived-client path ([NetCipherHelper.Request.newCall] with a call timeout) — and the
 * default (null) factory attaches no observer, leaving behavior unchanged.
 */
class NetCipherHelperForwardingTest {

    private lateinit var server: MockWebServer
    private var created = 0
    private val recordingFactory = object : EventListener.Factory {
        override fun create(call: Call): EventListener {
            created++
            return EventListener.NONE
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // Route NetCipherHelper.Request through a plain client pointed at the mock server,
        // instead of the real Tor/clearnet singleton (never initialized in a JVM unit test).
        NetCipherHelper.Request.mockClient = OkHttpClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
        NetCipherHelper.Request.mockClient = null
        NetCipherHelper.setEventListenerFactory(null)
    }

    @Test
    fun execute_withFactory_attachesObserver() {
        server.enqueue(MockResponse().setBody("{}"))
        NetCipherHelper.setEventListenerFactory(recordingFactory)

        NetCipherHelper.Request(server.url("/")).execute().close()

        assertEquals(1, created)
    }

    @Test
    fun execute_withoutFactory_attachesNoObserver() {
        server.enqueue(MockResponse().setBody("{}"))

        NetCipherHelper.Request(server.url("/")).execute().close()

        assertEquals(0, created)
    }

    @Test
    fun newCallWithTimeout_withFactory_attachesObserver() {
        server.enqueue(MockResponse().setBody("{}"))
        NetCipherHelper.setEventListenerFactory(recordingFactory)

        NetCipherHelper.Request(server.url("/")).newCall(2_000L).execute().close()

        assertEquals(1, created)
    }

    @Test
    fun newCallWithTimeout_withoutFactory_attachesNoObserver() {
        server.enqueue(MockResponse().setBody("{}"))

        NetCipherHelper.Request(server.url("/")).newCall(2_000L).execute().close()

        assertEquals(0, created)
    }
}
