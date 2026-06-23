package com.m2049r.xmrwallet.offline

class SignedRawMoneroTransaction(
    val raw: ByteArray,
    val txId: String,
    val fee: Long,
    val txCount: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignedRawMoneroTransaction) return false

        return raw.contentEquals(other.raw) &&
            txId == other.txId &&
            fee == other.fee &&
            txCount == other.txCount
    }

    override fun hashCode(): Int {
        var result = raw.contentHashCode()
        result = 31 * result + txId.hashCode()
        result = 31 * result + fee.hashCode()
        result = 31 * result + txCount.hashCode()
        return result
    }
}
