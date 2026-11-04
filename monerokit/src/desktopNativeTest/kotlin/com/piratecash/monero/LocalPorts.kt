package com.piratecash.monero

import java.net.ServerSocket

/** A loopback port nothing listens on, so every connection to it is refused at once. */
internal fun closedLocalPort(): Int = ServerSocket(0).use { it.localPort }
