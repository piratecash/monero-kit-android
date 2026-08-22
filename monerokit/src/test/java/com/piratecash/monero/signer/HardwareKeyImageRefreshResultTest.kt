package com.piratecash.monero.signer

import org.junit.Assert.assertEquals
import org.junit.Test

class HardwareKeyImageRefreshResultTest {
    @Test
    fun parsesCompletedProtocolResult() {
        val result = HardwareKeyImageRefreshResult.fromNative(longArrayOf(10, 20, 1, 1, 1, 0))

        assertEquals(10, result.startHeight)
        assertEquals(20, result.finalHeight)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsStartedProtocolWithoutCheckedFinalAck() {
        HardwareKeyImageRefreshResult.fromNative(longArrayOf(10, 20, 0, 1, 0, 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNativeResultWithUnknownKeyImages() {
        HardwareKeyImageRefreshResult.fromNative(longArrayOf(10, 20, 0, 0, 0, 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedNativeResult() {
        HardwareKeyImageRefreshResult.fromNative(longArrayOf(10, 20))
    }
}
