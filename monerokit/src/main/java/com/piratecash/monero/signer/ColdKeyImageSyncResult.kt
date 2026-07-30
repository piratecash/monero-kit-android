package com.piratecash.monero.signer

data class ColdKeyImageSyncResult(
    val height: Long,
    val spent: Long,
    val unspent: Long,
)
