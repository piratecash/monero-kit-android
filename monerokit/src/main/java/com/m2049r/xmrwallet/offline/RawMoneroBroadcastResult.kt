package com.m2049r.xmrwallet.offline

/**
 * Outcome of [com.m2049r.xmrwallet.service.MoneroWalletService.submitSignedRawTransaction].
 */
sealed class RawMoneroBroadcastResult {
    abstract val txId: String

    /** The transaction was submitted to the daemon by this call. */
    data class Submitted(override val txId: String) : RawMoneroBroadcastResult()

    /**
     * The transaction was already known to the daemon (mempool or mined) before this call,
     * so it was not re-submitted. Callers must not report this as a fresh "sent" result.
     */
    data class AlreadyKnown(override val txId: String) : RawMoneroBroadcastResult()
}
