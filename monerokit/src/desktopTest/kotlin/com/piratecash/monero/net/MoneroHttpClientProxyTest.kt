package com.piratecash.monero.net

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class MoneroHttpClientProxyTest {
    private val proxyProperties = listOf("http.proxyHost", "http.proxyPort", "socksProxyHost", "socksProxyPort")
    private val savedProperties = proxyProperties.associateWith { System.getProperty(it) }
    private val httpProxy = localServer()
    private val socksProxy = localServer()
    private val nodeUrl = "http://node.invalid:18089/get_info".toHttpUrl()

    @After
    fun tearDown() {
        httpProxy.close()
        socksProxy.close()
        savedProperties.forEach { (key, value) ->
            if (value == null) System.clearProperty(key) else System.setProperty(key, value)
        }
    }

    @Test
    fun execute_httpAndSocksSystemProxies_routesThroughSocks() {
        // Like Tor's HTTPTunnelPort: drops the absolute-form GET an HTTP proxy would get.
        thread(isDaemon = true) {
            try {
                while (true) httpProxy.accept().close()
            } catch (_: SocketException) {
            }
        }
        val socksTarget = AtomicReference<String>()
        socksProxy.serveOnce { socket ->
            socksTarget.set(acceptSocks5Connect(socket))
            readRequestLine(socket)
            respondOk(socket)
        }
        useProxy("http.proxy", httpProxy)
        useProxy("socksProxy", socksProxy)

        MoneroHttpClient.Request(nodeUrl).execute().use { assertEquals("{}", it.body?.string()) }

        assertEquals("node.invalid:18089", socksTarget.get())
    }

    @Test
    fun execute_httpSystemProxyOnly_keepsDefaultSelector() {
        val requestLine = AtomicReference<String>()
        httpProxy.serveOnce { socket ->
            requestLine.set(readRequestLine(socket))
            respondOk(socket)
        }
        useProxy("http.proxy", httpProxy)
        System.clearProperty("socksProxyHost")
        System.clearProperty("socksProxyPort")

        MoneroHttpClient.Request(nodeUrl).execute().close()

        assertEquals("GET $nodeUrl HTTP/1.1", requestLine.get())
    }

    private fun localServer() = ServerSocket(0, 50, InetAddress.getLoopbackAddress())

    private fun useProxy(prefix: String, server: ServerSocket) {
        System.setProperty("${prefix}Host", "127.0.0.1")
        System.setProperty("${prefix}Port", server.localPort.toString())
    }

    private fun ServerSocket.serveOnce(handle: (Socket) -> Unit) = thread(isDaemon = true) {
        try {
            accept().use(handle)
        } catch (_: SocketException) {
        }
    }

    /** Minimal SOCKS5 CONNECT without auth; returns the requested "host:port". */
    private fun acceptSocks5Connect(socket: Socket): String {
        val input = DataInputStream(socket.getInputStream())
        input.readByte()
        input.skipNBytes(input.readUnsignedByte().toLong())
        socket.getOutputStream().write(byteArrayOf(5, 0))
        // Version, CONNECT, reserved, address type: a domain name, which the proxy resolves.
        input.skipNBytes(4)
        val host = String(ByteArray(input.readUnsignedByte()).also(input::readFully))
        val target = "$host:${input.readUnsignedShort()}"
        socket.getOutputStream().write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        return target
    }

    private fun readRequestLine(socket: Socket): String {
        val reader = socket.getInputStream().bufferedReader()
        val requestLine = reader.readLine()
        while (!reader.readLine().isNullOrEmpty()) Unit
        return requestLine
    }

    private fun respondOk(socket: Socket) {
        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray())
    }
}
