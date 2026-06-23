package com.m2049r.xmrwallet.offline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SignedMoneroTransactionEnvelopeTest {
    @Test
    fun encode_validInput_roundTrips() {
        val txId = "a".repeat(64)
        val signedTransactionFile = byteArrayOf(1, 2, 3, 0, 4)

        val raw = SignedMoneroTransactionEnvelope.encode(txId, signedTransactionFile)
        val decoded = SignedMoneroTransactionEnvelope.decode(raw)

        assertEquals(txId, decoded.txId)
        assertArrayEquals(signedTransactionFile, decoded.signedTransactionFile)
    }

    @Test
    fun decode_invalidMagic_throws() {
        val raw = SignedMoneroTransactionEnvelope.encode("a".repeat(64), byteArrayOf(1))
        raw[0] = 'x'.code.toByte()

        assertThrows(MoneroRawTransactionError.InvalidRawTransaction::class.java) {
            SignedMoneroTransactionEnvelope.decode(raw)
        }
    }

    @Test
    fun encode_invalidTxId_throws() {
        assertThrows(MoneroRawTransactionError.InvalidRawTransaction::class.java) {
            SignedMoneroTransactionEnvelope.encode("A".repeat(64), byteArrayOf(1))
        }
    }

    @Test
    fun encode_emptySignedTransactionFile_throws() {
        assertThrows(MoneroRawTransactionError.InvalidRawTransaction::class.java) {
            SignedMoneroTransactionEnvelope.encode("a".repeat(64), byteArrayOf())
        }
    }

    @Test
    fun signedRawMoneroTransaction_sameBytes_comparesByContent() {
        val first = SignedRawMoneroTransaction(
            raw = byteArrayOf(1, 2, 3),
            txId = "a".repeat(64),
            fee = 10,
            txCount = 1,
        )
        val second = SignedRawMoneroTransaction(
            raw = byteArrayOf(1, 2, 3),
            txId = "a".repeat(64),
            fee = 10,
            txCount = 1,
        )

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun signedRawMoneroTransaction_differentBytes_notEqual() {
        val first = SignedRawMoneroTransaction(
            raw = byteArrayOf(1, 2, 3),
            txId = "a".repeat(64),
            fee = 10,
            txCount = 1,
        )
        val second = SignedRawMoneroTransaction(
            raw = byteArrayOf(1, 2, 4),
            txId = "a".repeat(64),
            fee = 10,
            txCount = 1,
        )

        assertNotEquals(first, second)
    }
}
