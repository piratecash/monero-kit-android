package com.piratecash.monero.signer

/**
 * Blocking packet channel owned by the application.
 *
 * Reads and writes contain exactly 64 bytes. [deadlineNanos] is an absolute deadline in the
 * monotonic [System.nanoTime] clock domain and stays unchanged across every packet belonging to
 * one protocol response. Calls run on native worker threads.
 *
 * [cancel] must be idempotent, return promptly, and unblock every active packet call.
 */
interface ExternalSignerChannel {
    @Throws(ExternalSignerException::class)
    fun writePacket(packet: ByteArray)

    @Throws(ExternalSignerException::class)
    fun readPacket(deadlineNanos: Long): ByteArray

    @Throws(ExternalSignerException::class)
    fun cancel()
}

interface ExternalSignerRegistration {
    /**
     * Starts cancellation without waiting for packet and native calls to drain.
     * Safe for UI/coroutine cancellation callbacks; [release] performs the blocking teardown.
     */
    @Throws(ExternalSignerException::class)
    fun signalCancellation() {
        cancel()
    }

    @Throws(ExternalSignerException::class)
    fun cancel()

    @Throws(ExternalSignerException::class)
    fun release()
}

object ExternalSigner {
    @JvmStatic
    @Throws(ExternalSignerException::class)
    fun register(
        channel: ExternalSignerChannel,
        sessionId: ByteArray,
    ): ExternalSignerRegistration =
        ExternalSignerNativeBridge.register(channel, sessionId)
}
