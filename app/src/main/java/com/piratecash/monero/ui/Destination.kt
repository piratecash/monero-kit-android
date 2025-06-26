package com.piratecash.monero.ui

import kotlinx.serialization.Serializable

sealed interface Destination {
    @Serializable
    data object Balance : Destination

    @Serializable
    data object Transactions : Destination

    @Serializable
    data object Send : Destination

    companion object {
        val entries = listOf(Balance, Transactions, Send)
    }
}