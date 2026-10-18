package com.piratecash.monero.net

import com.piratecash.monero.log.MoneroLog

/** SOCKS proxy handed to `wallet2` as `"<ipv4>:<port>"`, or `""` for a direct connection. */
expect object MoneroProxy {
    fun current(): String
}

private const val TAG = "MoneroKit:Proxy"
private const val IPV4_OCTET = "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)"
private val IPV4 = Regex("$IPV4_OCTET(\\.$IPV4_OCTET){3}")

/**
 * The JVM-wide SOCKS proxy (`socksProxyHost`/`socksProxyPort`). `wallet2` silently fails every
 * connection on a proxy it cannot parse, so anything but an IPv4 literal and a valid port is dropped.
 */
internal fun systemSocksProxy(): String {
    val host = System.getProperty("socksProxyHost").orEmpty()
    val port = System.getProperty("socksProxyPort").orEmpty()
    if (host.isEmpty() && port.isEmpty()) return ""

    val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 }
    if (!IPV4.matches(host) || portNumber == null) {
        MoneroLog.w(TAG, "Ignoring system SOCKS proxy '%s:%s': wallet2 needs an IPv4 literal and a port", host, port)
        return ""
    }
    return "$host:$portNumber"
}
