package com.m2049r.xmrwallet.offline

sealed class MoneroRawTransactionError(message: String) : Exception(message) {
    class InvalidRawTransaction(message: String) : MoneroRawTransactionError(message)
    class LegacyEnvelope : MoneroRawTransactionError("Signed transaction was created by an older app version")
    class WalletNotInitialized : MoneroRawTransactionError("Monero wallet not initialized")
    class CreateFailed(message: String) : MoneroRawTransactionError(message)
    class SubmitFailed(message: String) : MoneroRawTransactionError(message)
    class DaemonUnavailable(message: String) : MoneroRawTransactionError(message)
}
