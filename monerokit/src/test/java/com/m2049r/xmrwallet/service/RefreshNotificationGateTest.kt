package com.m2049r.xmrwallet.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshNotificationGateTest {
    @Test
    fun resumeAfterControlledRefresh_rearmsExactlyOneRefreshedNotification_withoutUpdatedEvent() {
        val gate = ControlledRefreshSessionGate()

        gate.resumeAfterControlledRefresh()

        assertTrue(gate.consumeRefreshedNotification())
        assertFalse(gate.consumeRefreshedNotification())
    }

    @Test
    fun pausedProgress_isThrottledAndNeverUsesTheOrdinaryRefreshNotification() {
        var now = 1_000L
        val progress = mutableListOf<Long>()
        val gate = ControlledRefreshSessionGate(now = { now }, progressIntervalMs = 500)
        gate.setProgressObserver { progress += it }

        gate.onPausedNewBlock(10)
        now += 100
        gate.onPausedNewBlock(11)
        now += 500
        gate.onPausedNewBlock(12)

        assertEquals(listOf(10L, 12L), progress)
        assertFalse(gate.consumeRefreshedNotification())
    }

    @Test
    fun completionFailureCancellationAndStop_clearTheControlledProgressObserver() {
        listOf<(ControlledRefreshSessionGate) -> Unit>(
            { it.complete() }, { it.fail() }, { it.cancel() }, { it.stop() },
        ).forEach { finish ->
            val progress = mutableListOf<Long>()
            val gate = ControlledRefreshSessionGate(progressIntervalMs = 0)
            gate.setProgressObserver { progress += it }
            finish(gate)
            gate.onPausedNewBlock(1)
            assertTrue("observer leaked after controlled session end", progress.isEmpty())
        }
    }
}
