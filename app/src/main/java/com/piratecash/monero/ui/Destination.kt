package com.piratecash.monero.ui

sealed class Destination(val title: String) {
    object Balance : Destination("Balance")
    object Transactions : Destination("Transactions")
    object Send : Destination("Send")

    companion object {
        val entries = listOf(Balance, Transactions, Send)
    }
}