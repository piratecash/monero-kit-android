package com.m2049r.xmrwallet.offline

sealed class MoneroRawTransactionError(message: String) : Exception(message) {
    class InvalidRawTransaction(message: String) : MoneroRawTransactionError(message)
    class WalletNotInitialized : MoneroRawTransactionError("Monero wallet not initialized")
    class CreateFailed(message: String) : MoneroRawTransactionError(message)
    class SaveFailed(message: String) : MoneroRawTransactionError(message)
    class SignFailed(message: String) : MoneroRawTransactionError(message)
    class SubmitFailed(message: String) : MoneroRawTransactionError(message)
}
