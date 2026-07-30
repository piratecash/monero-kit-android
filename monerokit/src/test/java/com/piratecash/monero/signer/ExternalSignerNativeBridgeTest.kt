package com.piratecash.monero.signer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ExternalSignerNativeBridgeTest {
    @Test
    fun register_channelRegistered_forwardsPackets() {
        val writtenPacket = ByteArray(PACKET_SIZE) { it.toByte() }
        val readPacket = ByteArray(PACKET_SIZE) { (it + 1).toByte() }
        var actualWrittenPacket: ByteArray? = null
        var actualDeadlineNanos: Long? = null
        val registration = registerSigner(
            channel(
                write = { actualWrittenPacket = it },
                read = {
                    actualDeadlineNanos = it
                    readPacket
                },
            ),
        )

        try {
            val generation = ExternalSignerNativeBridge.currentGeneration()

            ExternalSignerNativeBridge.writePacket(generation, writtenPacket)

            assertArrayEquals(writtenPacket, actualWrittenPacket)
            assertArrayEquals(
                readPacket,
                ExternalSignerNativeBridge.readPacket(generation, DEADLINE_NANOS),
            )
            assertEquals(DEADLINE_NANOS, actualDeadlineNanos)
        } finally {
            registration.release()
        }
    }

    @Test
    fun register_sessionId_defensivelyCopiesInputAndOutput() {
        val input = byteArrayOf(1, 2, 3)
        val registration = registerSigner(channel(), input)

        try {
            val generation = ExternalSignerNativeBridge.currentGeneration()
            input[0] = 9
            val returned = ExternalSignerNativeBridge.currentSessionId(generation)
            returned[1] = 9

            assertArrayEquals(
                byteArrayOf(1, 2, 3),
                ExternalSignerNativeBridge.currentSessionId(generation),
            )
        } finally {
            registration.release()
        }
    }

    @Test
    fun register_emptySessionId_throwsTypedError() {
        val error = assertSignerError {
            ExternalSigner.register(channel(), byteArrayOf())
        }

        assertEquals(ExternalSignerError.CHANNEL_FAILURE, error.error)
    }

    @Test
    fun register_channelAlreadyRegistered_throwsTypedError() {
        val registration = registerSigner(channel())

        try {
            val error = assertSignerError {
                registerSigner(channel())
            }

            assertEquals(ExternalSignerError.CHANNEL_ALREADY_REGISTERED, error.error)
        } finally {
            registration.release()
        }
    }

    @Test
    fun writePacket_oldGenerationAfterReplacement_throwsStaleError() {
        val firstRegistration = registerSigner(channel())
        val oldGeneration = ExternalSignerNativeBridge.currentGeneration()
        firstRegistration.release()
        val currentRegistration = registerSigner(channel())

        try {
            val error = assertSignerError {
                ExternalSignerNativeBridge.writePacket(oldGeneration, ByteArray(PACKET_SIZE))
            }

            assertEquals(ExternalSignerError.STALE_CHANNEL, error.error)
        } finally {
            currentRegistration.release()
        }
    }

    @Test
    fun writePacket_invalidPacketSize_throwsTypedError() {
        val registration = registerSigner(channel())

        try {
            val generation = ExternalSignerNativeBridge.currentGeneration()
            val error = assertSignerError {
                ExternalSignerNativeBridge.writePacket(generation, ByteArray(PACKET_SIZE - 1))
            }

            assertEquals(ExternalSignerError.INVALID_PACKET, error.error)
        } finally {
            registration.release()
        }
    }

    @Test
    fun readPacket_invalidPacketSize_throwsTypedError() {
        val registration = registerSigner(
            channel(read = { ByteArray(PACKET_SIZE + 1) }),
        )

        try {
            val generation = ExternalSignerNativeBridge.currentGeneration()
            val error = assertSignerError {
                ExternalSignerNativeBridge.readPacket(generation, DEADLINE_NANOS)
            }

            assertEquals(ExternalSignerError.INVALID_PACKET, error.error)
        } finally {
            registration.release()
        }
    }

    @Test
    fun readPacket_channelFailure_wrapsCause() {
        val cause = IllegalStateException("read failed")
        val registration = registerSigner(
            channel(read = { throw cause }),
        )

        try {
            val generation = ExternalSignerNativeBridge.currentGeneration()
            val error = assertSignerError {
                ExternalSignerNativeBridge.readPacket(generation, DEADLINE_NANOS)
            }

            assertEquals(ExternalSignerError.CHANNEL_FAILURE, error.error)
            assertSame(cause, error.cause)
        } finally {
            registration.release()
        }
    }

    @Test
    fun release_registrationReleased_isIdempotent() {
        val registration = registerSigner(channel())

        registration.release()
        registration.release()

        val error = assertSignerError {
            ExternalSignerNativeBridge.currentGeneration()
        }
        assertEquals(ExternalSignerError.NO_CHANNEL, error.error)
    }

    @Test
    fun cancel_registrationActive_closesChannelWithoutReleasingRegistration() {
        var cancelCalls = 0
        val registration = registerSigner(
            channel(cancel = { cancelCalls++ }),
        )

        registration.cancel()
        val closingError = assertSignerError {
            ExternalSignerNativeBridge.currentGeneration()
        }
        registration.release()

        assertEquals(1, cancelCalls)
        assertEquals(ExternalSignerError.CANCELLED, closingError.error)
        val noChannelError = assertSignerError {
            ExternalSignerNativeBridge.currentGeneration()
        }
        assertEquals(ExternalSignerError.NO_CHANNEL, noChannelError.error)
    }

    @Test
    fun signalCancellation_cancelBlocked_returnsWithoutWaitingForDrain() {
        val cancelStarted = CountDownLatch(1)
        val finishCancel = CountDownLatch(1)
        val signalFinished = CountDownLatch(1)
        val registration = registerSigner(
            channel(
                cancel = {
                    cancelStarted.countDown()
                    finishCancel.await()
                },
            ),
        )
        val signalThread = thread {
            registration.signalCancellation()
            signalFinished.countDown()
        }

        try {
            assertTrue(cancelStarted.await(1, TimeUnit.SECONDS))
            assertTrue(signalFinished.await(100, TimeUnit.MILLISECONDS))
        } finally {
            finishCancel.countDown()
            signalThread.join()
            registration.release()
        }
    }

    @Test
    fun release_cancellationFails_reapsClosedRegistration() {
        var cancelAttempts = 0
        val registration = registerSigner(
            channel(
                cancel = {
                    cancelAttempts++
                    if (cancelAttempts == 1) {
                        throw ExternalSignerException(
                            ExternalSignerError.CHANNEL_FAILURE,
                            "cancel failed",
                        )
                    }
                },
            ),
        )

        val error = assertSignerError(registration::release)
        awaitNoChannel()
        val nextRegistration = registerSigner(channel())
        nextRegistration.release()

        assertEquals(ExternalSignerError.CHANNEL_FAILURE, error.error)
        assertEquals(1, cancelAttempts)
        val noChannelError = assertSignerError {
            ExternalSignerNativeBridge.currentGeneration()
        }
        assertEquals(ExternalSignerError.NO_CHANNEL, noChannelError.error)
    }

    @Test
    fun release_readInProgress_cancelsAndWaitsForDrain() {
        val readStarted = CountDownLatch(1)
        val finishRead = CountDownLatch(1)
        val cancelCalled = CountDownLatch(1)
        val releaseFinished = CountDownLatch(1)
        val readFailure = AtomicReference<Throwable>()
        val registration = registerSigner(
            channel(
                read = {
                    readStarted.countDown()
                    finishRead.await()
                    ByteArray(PACKET_SIZE)
                },
                cancel = cancelCalled::countDown,
            ),
        )
        val generation = ExternalSignerNativeBridge.currentGeneration()
        val readThread = thread {
            try {
                ExternalSignerNativeBridge.readPacket(generation, DEADLINE_NANOS)
            } catch (error: Throwable) {
                readFailure.set(error)
            }
        }

        assertTrue(readStarted.await(1, TimeUnit.SECONDS))
        val releaseThread = thread {
            registration.release()
            releaseFinished.countDown()
        }

        try {
            assertTrue(cancelCalled.await(1, TimeUnit.SECONDS))
            assertFalse(releaseFinished.await(100, TimeUnit.MILLISECONDS))
        } finally {
            finishRead.countDown()
            readThread.join()
            releaseThread.join()
        }

        val failure = readFailure.get() as ExternalSignerException
        assertEquals(ExternalSignerError.CANCELLED, failure.error)
        assertTrue(releaseFinished.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun cancel_concurrentRelease_cancelsOnceAndReleases() {
        val cancelStarted = CountDownLatch(1)
        val finishCancel = CountDownLatch(1)
        val cancelCalls = AtomicInteger()
        val cancelFailure = AtomicReference<Throwable>()
        val releaseFailure = AtomicReference<Throwable>()
        val registration = registerSigner(
            channel(
                cancel = {
                    cancelCalls.incrementAndGet()
                    cancelStarted.countDown()
                    finishCancel.await()
                },
            ),
        )

        val cancelThread = thread {
            try {
                registration.cancel()
            } catch (error: Throwable) {
                cancelFailure.set(error)
            }
        }
        assertTrue(cancelStarted.await(1, TimeUnit.SECONDS))
        val releaseThread = thread {
            try {
                registration.release()
            } catch (error: Throwable) {
                releaseFailure.set(error)
            }
        }

        finishCancel.countDown()
        cancelThread.join()
        releaseThread.join()

        assertEquals(1, cancelCalls.get())
        assertEquals(null, cancelFailure.get())
        assertEquals(null, releaseFailure.get())
        val noChannelError = assertSignerError {
            ExternalSignerNativeBridge.currentGeneration()
        }
        assertEquals(ExternalSignerError.NO_CHANNEL, noChannelError.error)
    }

    @Test
    fun release_cancelBlocked_quarantinesUntilCancellationStops() {
        val cancelStarted = CountDownLatch(1)
        val finishCancel = CountDownLatch(1)
        val registration = registerSigner(
            channel(
                cancel = {
                    cancelStarted.countDown()
                    finishCancel.await()
                },
            ),
        )
        ExternalSignerNativeBridge.setReleaseTimeoutNanosForTests(
            TimeUnit.MILLISECONDS.toNanos(100),
        )

        try {
            val error = assertSignerError(registration::release)

            assertTrue(cancelStarted.await(1, TimeUnit.SECONDS))
            assertEquals(ExternalSignerError.CHANNEL_FAILURE, error.error)
            val closingError = assertSignerError {
                ExternalSignerNativeBridge.currentGeneration()
            }
            assertEquals(ExternalSignerError.CANCELLED, closingError.error)
            val registrationError = assertSignerError {
                registerSigner(channel())
            }
            assertEquals(
                ExternalSignerError.CHANNEL_ALREADY_REGISTERED,
                registrationError.error,
            )
        } finally {
            finishCancel.countDown()
            awaitNoChannel()
            ExternalSignerNativeBridge.resetReleaseTimeoutForTests()
        }
    }

    private fun channel(
        write: (ByteArray) -> Unit = {},
        read: (Long) -> ByteArray = { ByteArray(PACKET_SIZE) },
        cancel: () -> Unit = {},
    ) = object : ExternalSignerChannel {
        override fun writePacket(packet: ByteArray) = write(packet)

        override fun readPacket(deadlineNanos: Long): ByteArray = read(deadlineNanos)

        override fun cancel() = cancel()
    }

    private fun registerSigner(
        channel: ExternalSignerChannel,
        sessionId: ByteArray = TEST_SESSION_ID,
    ): ExternalSignerRegistration = ExternalSigner.register(channel, sessionId)

    private fun assertSignerError(block: () -> Unit): ExternalSignerException =
        try {
            block()
            fail("Expected ExternalSignerException")
            throw AssertionError("unreachable")
        } catch (error: ExternalSignerException) {
            error
        }

    private fun awaitNoChannel() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (System.nanoTime() < deadline) {
            val error = assertSignerError {
                ExternalSignerNativeBridge.currentGeneration()
            }
            if (error.error == ExternalSignerError.NO_CHANNEL) return
            Thread.sleep(10)
        }
        fail("External signer registration was not reaped")
    }

    private companion object {
        const val PACKET_SIZE = 64
        const val DEADLINE_NANOS = 123L
        val TEST_SESSION_ID = byteArrayOf(1, 2, 3)
    }
}
