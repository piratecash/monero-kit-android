package com.piratecash.monero

/** Loads `libmonerujo` once per process; every JNI class calls [load] from its static initializer. */
expect object MoneroNative {
    fun load()

    /**
     * Whether the natives for this host are packaged, so [load] can succeed. A failing [load] called from a JNI class's static
     * initializer reaches the caller wrapped in `ExceptionInInitializerError`, so check this first.
     */
    fun isHostSupported(): Boolean
}
