package com.m2049r.xmrwallet.offline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SignedMoneroTransactionEnvelopeTest {
    private fun txId(marker: Char) = marker.toString().repeat(64)

    private fun transaction(marker: Char, blob: ByteArray) = SignedMoneroTransaction(txId(marker), blob)

    @Test
    fun encode_singleTransaction_roundTrips() {
        val transactions = listOf(transaction('a', byteArrayOf(1, 2, 3, 0, 4)))

        val decoded = SignedMoneroTransactionEnvelope.decode(
            SignedMoneroTransactionEnvelope.encode(transactions)
        )

        assertEquals(transactions, decoded)
    }

    @Test
    fun encode_threeTransactions_keepsBlobsPairedWithTheirTxIds() {
        val transactions = listOf(
            transaction('a', byteArrayOf(1)),
            transaction('b', byteArrayOf(2, 2)),
            transaction('c', byteArrayOf(3, 3, 3)),
        )

        val decoded = SignedMoneroTransactionEnvelope.decode(
            SignedMoneroTransactionEnvelope.encode(transactions)
        )

        assertEquals(listOf(txId('a'), txId('b'), txId('c')), decoded.map { it.txId })
        assertArrayEquals(byteArrayOf(2, 2), decoded[1].blob)
    }

    @Test
    fun decode_legacyV1Envelope_throwsLegacyEnvelope() {
        val raw = "PCASH_XMR_SIGNED_TX_V1".encodeToByteArray() + byteArrayOf(0, 1, 2, 3)

        assertThrows(MoneroRawTransactionError.LegacyEnvelope::class.java) {
            SignedMoneroTransactionEnvelope.decode(raw)
        }
    }

    @Test
    fun decode_invalidMagic_throws() {
        val raw = encodeOne()
        raw[0] = 'x'.code.toByte()

        assertThrowsInvalid { SignedMoneroTransactionEnvelope.decode(raw) }
    }

    @Test
    fun decode_invalidSeparator_throws() {
        val raw = encodeOne()
        raw[MAGIC_LENGTH] = 1

        assertThrowsInvalid { SignedMoneroTransactionEnvelope.decode(raw) }
    }

    @Test
    fun decode_magicOnly_throws() {
        assertThrowsInvalid {
            SignedMoneroTransactionEnvelope.decode("PCASH_XMR_SIGNED_TX_V2".encodeToByteArray())
        }
    }

    @Test
    fun decode_zeroCount_throws() {
        val raw = encodeOne()
        writeInt(raw, MAGIC_LENGTH + 1, 0)

        assertThrowsInvalid { SignedMoneroTransactionEnvelope.decode(raw) }
    }

    @Test
    fun decode_countLargerThanPayload_throws() {
        val raw = encodeOne()
        writeInt(raw, MAGIC_LENGTH + 1, 2)

        assertThrowsInvalid { SignedMoneroTransactionEnvelope.decode(raw) }
    }

    @Test
    fun decode_blobLengthBeyondBuffer_throws() {
        val raw = encodeOne()
        writeInt(raw, MAGIC_LENGTH + 1 + Int.SIZE_BYTES + 64, Int.MAX_VALUE)

        assertThrowsInvalid { SignedMoneroTransactionEnvelope.decode(raw) }
    }

    @Test
    fun decode_truncatedBuffer_throws() {
        val raw = encodeOne()

        assertThrowsInvalid { SignedMoneroTransactionEnvelope.decode(raw.copyOf(raw.size - 1)) }
    }

    @Test
    fun decode_trailingBytes_throws() {
        val raw = encodeOne() + byteArrayOf(7)

        assertThrowsInvalid { SignedMoneroTransactionEnvelope.decode(raw) }
    }

    @Test
    fun decode_nonHexTxId_throws() {
        val raw = encodeOne()
        raw[MAGIC_LENGTH + 1 + Int.SIZE_BYTES] = 'z'.code.toByte()

        assertThrowsInvalid { SignedMoneroTransactionEnvelope.decode(raw) }
    }

    @Test
    fun encode_noTransactions_throws() {
        assertThrowsInvalid { SignedMoneroTransactionEnvelope.encode(emptyList()) }
    }

    @Test
    fun encode_uppercaseTxId_throws() {
        assertThrowsInvalid {
            SignedMoneroTransactionEnvelope.encode(listOf(transaction('A', byteArrayOf(1))))
        }
    }

    @Test
    fun encode_shortTxId_throws() {
        assertThrowsInvalid {
            SignedMoneroTransactionEnvelope.encode(listOf(SignedMoneroTransaction("abc", byteArrayOf(1))))
        }
    }

    @Test
    fun encode_emptyBlob_throws() {
        assertThrowsInvalid {
            SignedMoneroTransactionEnvelope.encode(listOf(transaction('a', byteArrayOf())))
        }
    }

    @Test
    fun signedRawMoneroTransaction_sameBytes_comparesByContent() {
        val first = SignedRawMoneroTransaction(byteArrayOf(1, 2, 3), listOf(txId('a')), fee = 10)
        val second = SignedRawMoneroTransaction(byteArrayOf(1, 2, 3), listOf(txId('a')), fee = 10)

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals(1, first.txCount)
    }

    @Test
    fun signedRawMoneroTransaction_differentBytes_notEqual() {
        val first = SignedRawMoneroTransaction(byteArrayOf(1, 2, 3), listOf(txId('a')), fee = 10)
        val second = SignedRawMoneroTransaction(byteArrayOf(1, 2, 4), listOf(txId('a')), fee = 10)

        assertNotEquals(first, second)
    }

    private fun encodeOne() =
        SignedMoneroTransactionEnvelope.encode(listOf(transaction('a', byteArrayOf(1, 2, 3))))

    private fun writeInt(raw: ByteArray, offset: Int, value: Int) {
        for (i in 0 until Int.SIZE_BYTES) {
            raw[offset + i] = (value ushr (8 * (Int.SIZE_BYTES - 1 - i))).toByte()
        }
    }

    private fun assertThrowsInvalid(block: () -> Unit) {
        assertThrows(MoneroRawTransactionError.InvalidRawTransaction::class.java) { block() }
    }

    private companion object {
        const val MAGIC_LENGTH = 22
    }
}
