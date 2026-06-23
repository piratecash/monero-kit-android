package com.m2049r.xmrwallet.offline

import java.nio.charset.StandardCharsets

class DecodedSignedMoneroTransaction(
    val txId: String,
    val signedTransactionFile: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DecodedSignedMoneroTransaction) return false

        return txId == other.txId &&
            signedTransactionFile.contentEquals(other.signedTransactionFile)
    }

    override fun hashCode(): Int {
        var result = txId.hashCode()
        result = 31 * result + signedTransactionFile.contentHashCode()
        return result
    }
}

object SignedMoneroTransactionEnvelope {
    private val magic = "PCASH_XMR_SIGNED_TX_V1".toByteArray(StandardCharsets.US_ASCII)
    private const val separator: Byte = 0
    private const val txIdLength = 64

    fun encode(txId: String, signedTransactionFile: ByteArray): ByteArray {
        validateTxId(txId)
        if (signedTransactionFile.isEmpty()) {
            throw MoneroRawTransactionError.InvalidRawTransaction("Signed transaction file is empty")
        }

        val txIdBytes = txId.toByteArray(StandardCharsets.US_ASCII)
        return ByteArray(magic.size + 1 + txIdBytes.size + 1 + signedTransactionFile.size).also { raw ->
            var offset = 0
            magic.copyInto(raw, offset)
            offset += magic.size
            raw[offset++] = separator
            txIdBytes.copyInto(raw, offset)
            offset += txIdBytes.size
            raw[offset++] = separator
            signedTransactionFile.copyInto(raw, offset)
        }
    }

    fun decode(raw: ByteArray): DecodedSignedMoneroTransaction {
        val headerLength = magic.size + 1 + txIdLength + 1
        if (raw.size <= headerLength) {
            throw MoneroRawTransactionError.InvalidRawTransaction("Raw transaction is too short")
        }
        if (!raw.copyOfRange(0, magic.size).contentEquals(magic)) {
            throw MoneroRawTransactionError.InvalidRawTransaction("Invalid Monero transaction envelope")
        }
        if (raw[magic.size] != separator) {
            throw MoneroRawTransactionError.InvalidRawTransaction("Invalid Monero transaction envelope")
        }

        val txIdStart = magic.size + 1
        val txIdEnd = txIdStart + txIdLength
        if (raw[txIdEnd] != separator) {
            throw MoneroRawTransactionError.InvalidRawTransaction("Invalid Monero transaction envelope")
        }

        val txId = String(raw, txIdStart, txIdLength, StandardCharsets.US_ASCII)
        validateTxId(txId)

        val signedTransactionFile = raw.copyOfRange(txIdEnd + 1, raw.size)
        return DecodedSignedMoneroTransaction(txId, signedTransactionFile)
    }

    private fun validateTxId(txId: String) {
        if (txId.length != txIdLength || txId.any { it !in '0'..'9' && it !in 'a'..'f' }) {
            throw MoneroRawTransactionError.InvalidRawTransaction("Invalid Monero transaction txid")
        }
    }
}
