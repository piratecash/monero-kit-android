package com.piratecash.monero.net

import com.m2049r.levin.util.NetCipherHelper

actual object MoneroProxy {
    /** Orbot first; throws like before when `NetCipherHelper` was never created. */
    @JvmStatic
    actual fun current(): String = NetCipherHelper.getProxy().ifEmpty { systemSocksProxy() }
}
