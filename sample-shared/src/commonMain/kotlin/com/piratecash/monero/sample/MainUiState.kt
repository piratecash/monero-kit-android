package com.piratecash.monero.sample

data class TransactionUiModel(
    val account: Int,
    val notes: String?,
    val destination: String?,
    val paymentId: String?,
    val txId: String?,
    val txKey: String?,
    val block: Long,
    val date: String,
    val fee: String,
    val amount: String,
    val isPending: Boolean,
    val direction: String,
    val isFailed: Boolean,
)

data class MainUiState(
    val networkName: String = "",
    val address: String = "N/A",
    val balance: String = "",
    val balanceUnspendable: String = "",
    val state: String = "Not synced",
    val lastBlock: String = "N/A",
    val transactions: List<TransactionUiModel> = emptyList(),

    val addressTo: String = "",
    val amountTo: String = "",
    val notesTo: String = "",
    val isAddressToValid: Boolean = true,
    val isLoadingSending: Boolean = false,
    val errorSending: String? = null,
)
