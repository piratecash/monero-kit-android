package com.m2049r.xmrwallet.offline

class SignedRawMoneroTransaction(
    val raw: ByteArray,
    val txIds: List<String>,
    val fee: Long,
) {
    val txCount: Int get() = txIds.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignedRawMoneroTransaction) return false

        return raw.contentEquals(other.raw) &&
            txIds == other.txIds &&
            fee == other.fee
    }

    override fun hashCode(): Int {
        var result = raw.contentHashCode()
        result = 31 * result + txIds.hashCode()
        result = 31 * result + fee.hashCode()
        return result
    }
}
