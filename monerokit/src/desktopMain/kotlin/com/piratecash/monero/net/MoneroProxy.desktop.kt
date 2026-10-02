package com.piratecash.monero.net

actual object MoneroProxy {
    @JvmStatic
    actual fun current(): String = systemSocksProxy()
}
