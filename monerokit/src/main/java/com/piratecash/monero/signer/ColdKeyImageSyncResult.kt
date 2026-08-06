package com.piratecash.monero.signer

data class ColdKeyImageSyncResult(
    val height: Long,
    val spent: Long,
    val unspent: Long,
) {
    // Keep the primary constructor at three arguments for pcash.9 ABI compatibility.
    private var spentStatusVerifiedValue: Boolean = false

    val spentStatusVerified: Boolean
        get() = spentStatusVerifiedValue

    constructor(
        height: Long,
        spent: Long,
        unspent: Long,
        spentStatusVerified: Boolean,
    ) : this(height, spent, unspent) {
        spentStatusVerifiedValue = spentStatusVerified
    }
}
