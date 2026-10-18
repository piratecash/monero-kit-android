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
interface ExternalSignerChannel : ExternalSignerTransport {
    @Throws(ExternalSignerException::class)
    fun writePacket(packet: ByteArray)

    @Throws(ExternalSignerException::class)
    fun readPacket(deadlineNanos: Long): ByteArray
}

/** What every vendor channel shares: [cancel] must be idempotent, return promptly, and unblock active calls. */
interface ExternalSignerTransport {
    @Throws(ExternalSignerException::class)
    fun cancel()
}

/**
 * Ledger: one whole APDU in, the response data plus its status word out. Calls run on native worker threads.
 * A lost transport throws [ExternalSignerException] with [HardwareWalletErrorCode.Disconnected].
 */
interface ExternalSignerApduChannel : ExternalSignerTransport {
    @Throws(ExternalSignerException::class)
    fun exchange(apdu: ByteArray): ByteArray
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

    @JvmStatic
    @Throws(ExternalSignerException::class)
    fun registerApdu(channel: ExternalSignerApduChannel): ExternalSignerRegistration =
        ExternalSignerNativeBridge.registerApdu(channel)
}
