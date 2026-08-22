package com.m2049r.xmrwallet.service

import com.m2049r.xmrwallet.offline.RawMoneroBroadcastResult
import com.m2049r.xmrwallet.offline.SignedMoneroTransaction
import com.m2049r.xmrwallet.offline.SignedMoneroTransactionEnvelope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MoneroWalletServiceBroadcastTest {
    private val sent = mutableListOf<Byte>()
    private val lookupBatches = mutableListOf<List<String>>()

    private fun txId(marker: Char) = marker.toString().repeat(64)

    private fun envelope(vararg markers: Char) = SignedMoneroTransactionEnvelope.encode(
        markers.map { SignedMoneroTransaction(txId(it), byteArrayOf(it.code.toByte())) }
    )

    private fun broadcast(raw: ByteArray, known: Set<String>) = runBlocking {
        MoneroWalletService.broadcastEnvelope(
            raw = raw,
            knownTransactions = {
                lookupBatches += it
                it.filterTo(mutableSetOf()) { txId -> txId in known }
            },
            send = { sent += it.single() },
        )
    }

    private fun markers(vararg expected: Char) = expected.map { it.code.toByte() }

    @Test
    fun broadcastEnvelope_noTransactionKnown_sendsEveryTransaction() {
        val result = broadcast(envelope('a', 'b', 'c'), known = emptySet())

        assertEquals(markers('a', 'b', 'c'), sent)
        assertEquals(listOf(listOf(txId('a'), txId('b'), txId('c'))), lookupBatches)
        assertEquals(RawMoneroBroadcastResult.Submitted(txId('a')), result)
    }

    @Test
    fun broadcastEnvelope_firstTransactionAlreadyKnown_sendsTheRemainingOnes() {
        val result = broadcast(envelope('a', 'b', 'c'), known = setOf(txId('a')))

        assertEquals(markers('b', 'c'), sent)
        assertEquals(listOf(listOf(txId('a'), txId('b'), txId('c'))), lookupBatches)
        assertEquals(RawMoneroBroadcastResult.Submitted(txId('a')), result)
    }

    @Test
    fun broadcastEnvelope_everyTransactionKnown_sendsNothing() {
        val known = setOf(txId('a'), txId('b'), txId('c'))

        val result = broadcast(envelope('a', 'b', 'c'), known = known)

        assertEquals(emptyList<Byte>(), sent)
        assertEquals(listOf(listOf(txId('a'), txId('b'), txId('c'))), lookupBatches)
        assertEquals(RawMoneroBroadcastResult.AlreadyKnown(txId('a')), result)
    }

    @Test
    fun broadcastEnvelope_singleKnownTransaction_reportsAlreadyKnown() {
        val result = broadcast(envelope('a'), known = setOf(txId('a')))

        assertEquals(emptyList<Byte>(), sent)
        assertEquals(RawMoneroBroadcastResult.AlreadyKnown(txId('a')), result)
    }

    @Test
    fun broadcastEnvelope_singleUnknownTransaction_sendsIt() {
        val result = broadcast(envelope('a'), known = emptySet())

        assertEquals(markers('a'), sent)
        assertEquals(RawMoneroBroadcastResult.Submitted(txId('a')), result)
    }
}
