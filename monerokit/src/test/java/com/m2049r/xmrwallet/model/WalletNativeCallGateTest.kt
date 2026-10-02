package com.m2049r.xmrwallet.model

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class WalletNativeCallGateTest {
    private val executor: ExecutorService = Executors.newCachedThreadPool()

    @After
    fun tearDown() {
        executor.shutdownNow()
        assertTrue(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    }

    @Test
    fun close_activeRead_drainsReadAndRejectsNewReads() {
        val gate = WalletNativeCallGate()
        val readEntered = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val read = executor.submit<Long> {
            gate.read {
                readEntered.countDown()
                releaseRead.await()
                READ_RESULT
            }
        }
        assertTrue(readEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

        val closeActionStarted = CountDownLatch(1)
        val close = executor.submit<Boolean> {
            gate.close {
                closeActionStarted.countDown()
                true
            }
        }

        try {
            assertTrue(awaitRejectedRead(gate, closeActionStarted))
            assertEquals(1L, closeActionStarted.count)
        } finally {
            releaseRead.countDown()
        }
        assertEquals(READ_RESULT, read.getOrFail())
        assertTrue(close.getOrFail())
        assertEquals(0L, gate.read { READ_RESULT })
    }

    @Test
    fun close_failedAction_reopensOnlyAfterActionFinishes() {
        val gate = WalletNativeCallGate()
        val actionEntered = CountDownLatch(1)
        val releaseAction = CountDownLatch(1)
        val close = executor.submit<Boolean> {
            gate.close {
                actionEntered.countDown()
                releaseAction.await()
                false
            }
        }
        assertTrue(actionEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

        try {
            assertEquals(0L, gate.read { READ_RESULT })
        } finally {
            releaseAction.countDown()
        }
        assertFalse(close.getOrFail())
        assertEquals(READ_RESULT, gate.read { READ_RESULT })
    }

    @Test
    fun close_concurrentCaller_receivesSameFailedOutcomeWithoutSecondAction() {
        val gate = WalletNativeCallGate()
        val firstActionEntered = CountDownLatch(1)
        val releaseFirstAction = CountDownLatch(1)
        val first = executor.submit<Boolean> {
            gate.close {
                firstActionEntered.countDown()
                releaseFirstAction.await()
                false
            }
        }
        assertTrue(firstActionEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

        val secondActionCalls = AtomicInteger()
        val second = executor.submit<Boolean> {
            gate.close {
                secondActionCalls.incrementAndGet()
                true
            }
        }

        try {
            assertFalse(second.getOrFail())
        } finally {
            releaseFirstAction.countDown()
        }
        assertFalse(first.getOrFail())
        assertEquals(0, secondActionCalls.get())
        assertTrue(gate.close { true })
    }

    @Test
    fun close_concurrentCaller_returnsFalseAndLeaderExceptionReopensGate() {
        val gate = WalletNativeCallGate()
        val expected = IllegalStateException("close failed")
        val firstActionEntered = CountDownLatch(1)
        val releaseFirstAction = CountDownLatch(1)
        val first = executor.submit<Boolean> {
            gate.close {
                firstActionEntered.countDown()
                releaseFirstAction.await()
                throw expected
            }
        }
        assertTrue(firstActionEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

        val secondActionCalls = AtomicInteger()
        val second = executor.submit<Boolean> {
            gate.close {
                secondActionCalls.incrementAndGet()
                true
            }
        }

        try {
            assertFalse(second.getOrFail())
        } finally {
            releaseFirstAction.countDown()
        }
        assertSame(expected, first.failure())
        assertEquals(0, secondActionCalls.get())
        assertEquals(READ_RESULT, gate.read { READ_RESULT })
    }

    @Test
    fun close_interruptedWhileDraining_restoresInterruptAfterClose() {
        val gate = WalletNativeCallGate()
        val readEntered = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val read = executor.submit<Long> {
            gate.read {
                readEntered.countDown()
                releaseRead.await()
                READ_RESULT
            }
        }
        assertTrue(readEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

        val closeThreadReady = CountDownLatch(1)
        val closeThread = AtomicReference<Thread>()
        val interruptedAfterClose = AtomicBoolean()
        val close = executor.submit<Boolean> {
            closeThread.set(Thread.currentThread())
            closeThreadReady.countDown()
            val result = gate.close { true }
            interruptedAfterClose.set(Thread.currentThread().isInterrupted)
            result
        }
        assertTrue(closeThreadReady.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue(awaitRejectedRead(gate, CountDownLatch(1)))

        requireNotNull(closeThread.get()).interrupt()
        releaseRead.countDown()
        assertEquals(READ_RESULT, read.getOrFail())
        assertTrue(close.getOrFail())
        assertTrue(interruptedAfterClose.get())
    }

    private fun awaitRejectedRead(gate: WalletNativeCallGate, closeActionStarted: CountDownLatch): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline && closeActionStarted.count > 0) {
            if (gate.read { READ_RESULT } == 0L) return true
            Thread.yield()
        }
        return false
    }

    private fun <T> Future<T>.getOrFail(): T = get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private fun Future<*>.failure(): Throwable {
        return try {
            getOrFail()
            throw AssertionError("Expected close failure")
        } catch (error: ExecutionException) {
            error.cause ?: error
        }
    }

    private companion object {
        const val READ_RESULT = 7L
        const val TIMEOUT_SECONDS = 5L
    }

}
