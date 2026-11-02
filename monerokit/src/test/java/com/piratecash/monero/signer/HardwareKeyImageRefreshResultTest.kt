package com.piratecash.monero.signer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareKeyImageRefreshResultTest {
    @Test
    fun fromNative_completedProtocolResult_parsesAllValues() {
        val result = HardwareKeyImageRefreshResult.fromNative(longArrayOf(10, 20, 1, 1, 1, 0, 1))

        assertEquals(10, result.startHeight)
        assertEquals(20, result.finalHeight)
        assertTrue(result.suffixDetached)
        assertTrue(result.protocolStarted)
        assertTrue(result.finalAckChecked)
        assertTrue(result.completed)
    }

    @Test
    fun fromNative_stoppedPass_reportsNotCompleted() {
        val result = HardwareKeyImageRefreshResult.fromNative(longArrayOf(10, 15, 1, 0, 0, 0, 0))

        assertFalse(result.completed)
        assertEquals(15, result.finalHeight)
    }

    @Test
    fun fromNative_stoppedResetBelowStartHeight_accepted() {
        val result = HardwareKeyImageRefreshResult.fromNative(longArrayOf(100, 40, 0, 0, 0, 0, 0))

        assertFalse(result.completed)
        assertEquals(40, result.finalHeight)
    }

    @Test(expected = IllegalArgumentException::class)
    fun fromNative_completedBelowStartHeight_rejected() {
        HardwareKeyImageRefreshResult.fromNative(longArrayOf(100, 40, 0, 0, 0, 0, 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun fromNative_invalidCompletedFlag_rejected() {
        HardwareKeyImageRefreshResult.fromNative(longArrayOf(10, 20, 0, 0, 0, 0, 2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun fromNative_sixValues_rejected() {
        HardwareKeyImageRefreshResult.fromNative(longArrayOf(10, 20, 1, 1, 1, 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun fromNative_negativeFinalHeight_rejected() {
        HardwareKeyImageRefreshResult.fromNative(longArrayOf(0, -1, 0, 0, 0, 0, 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun fromNative_startedProtocolWithoutCheckedFinalAck_rejected() {
        HardwareKeyImageRefreshResult.fromNative(longArrayOf(10, 20, 0, 1, 0, 0, 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun fromNative_unknownKeyImages_rejected() {
        HardwareKeyImageRefreshResult.fromNative(longArrayOf(10, 20, 0, 0, 0, 1, 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun fromNative_malformedResult_rejected() {
        HardwareKeyImageRefreshResult.fromNative(longArrayOf(10, 20))
    }
}
