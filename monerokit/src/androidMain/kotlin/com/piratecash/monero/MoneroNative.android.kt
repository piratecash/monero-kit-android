package com.piratecash.monero

actual object MoneroNative {
    private val library = lazy { System.loadLibrary("monerujo") }

    @JvmStatic
    actual fun load() {
        library.value
    }

    @JvmStatic
    actual fun isHostSupported(): Boolean = true
}
