package com.m2049r.xmrwallet.offline

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class SignedMoneroTransaction(
    val txId: String,
    val blob: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignedMoneroTransaction) return false

        return txId == other.txId && blob.contentEquals(other.blob)
    }

    override fun hashCode(): Int {
        var result = txId.hashCode()
        result = 31 * result + blob.contentHashCode()
        return result
    }
}

/**
 * Envelope carrying signed transactions between devices:
 *
 * `magic | 0x00 | count:u32 | (txid:64 ASCII hex | length:u32 | blob)*count`
 *
 * Blobs are serialized transactions ready for `/sendrawtransaction`, so the receiving device
 * broadcasts them without any wallet key of its own.
 */
object SignedMoneroTransactionEnvelope {
    private val magic = "PCASH_XMR_SIGNED_TX_V2".toByteArray(StandardCharsets.US_ASCII)
    private val legacyMagic = "PCASH_XMR_SIGNED_TX_V1".toByteArray(StandardCharsets.US_ASCII)
    private const val separator: Byte = 0
    private const val txIdLength = 64

    fun encode(transactions: List<SignedMoneroTransaction>): ByteArray {
        if (transactions.isEmpty()) {
            throw MoneroRawTransactionError.InvalidRawTransaction("Signed transaction set is empty")
        }
        transactions.forEach(::validate)

        val size = magic.size + 1 + Int.SIZE_BYTES +
            transactions.sumOf { txIdLength + Int.SIZE_BYTES + it.blob.size }
        val buffer = ByteBuffer.allocate(size)
        buffer.put(magic).put(separator).putInt(transactions.size)
        transactions.forEach {
            buffer.put(it.txId.toByteArray(StandardCharsets.US_ASCII))
                .putInt(it.blob.size)
                .put(it.blob)
        }
        return buffer.array()
    }

    fun decode(raw: ByteArray): List<SignedMoneroTransaction> {
        if (raw.startsWith(legacyMagic)) {
            throw MoneroRawTransactionError.LegacyEnvelope()
        }
        if (!raw.startsWith(magic) || raw.size <= magic.size || raw[magic.size] != separator) {
            throw MoneroRawTransactionError.InvalidRawTransaction("Invalid Monero transaction envelope")
        }

        val body = ByteBuffer.wrap(raw, magic.size + 1, raw.size - magic.size - 1)
        return try {
            val count = body.int
            if (count <= 0) {
                throw MoneroRawTransactionError.InvalidRawTransaction("Envelope has no transactions")
            }
            buildList { repeat(count) { add(readTransaction(body)) } }.also {
                if (body.hasRemaining()) {
                    throw MoneroRawTransactionError.InvalidRawTransaction("Envelope has trailing bytes")
                }
            }
        } catch (ex: BufferUnderflowException) {
            throw MoneroRawTransactionError.InvalidRawTransaction("Envelope is truncated")
        }
    }

    private fun readTransaction(body: ByteBuffer): SignedMoneroTransaction {
        val txId = ByteArray(txIdLength).also(body::get)
        val length = body.int
        if (length <= 0 || length > body.remaining()) {
            throw MoneroRawTransactionError.InvalidRawTransaction("Invalid transaction length in envelope")
        }
        val blob = ByteArray(length).also(body::get)
        return SignedMoneroTransaction(String(txId, StandardCharsets.US_ASCII), blob)
            .also(::validate)
    }

    private fun validate(transaction: SignedMoneroTransaction) {
        val txId = transaction.txId
        if (txId.length != txIdLength || txId.any { it !in '0'..'9' && it !in 'a'..'f' }) {
            throw MoneroRawTransactionError.InvalidRawTransaction("Invalid Monero transaction txid")
        }
        if (transaction.blob.isEmpty()) {
            throw MoneroRawTransactionError.InvalidRawTransaction("Signed transaction blob is empty")
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
