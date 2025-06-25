package com.piratecash.monero.ui.send

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel

class SendViewModel: ViewModel() {
    val uiState = mutableStateOf(SendUiState())

    fun onAddressChange(address: String) {
        uiState.value = uiState.value.copy(address = address)
    }

    fun onAmountChange(amount: String) {
        uiState.value = uiState.value.copy(amount = amount)
    }

    fun onNotesChange(notes: String) {
        uiState.value = uiState.value.copy(notes = notes)
    }

    fun onSendClick() {
    }
}

data class SendUiState(
    val address: String = "",
    val amount: String = "",
    val notes: String = "",
    val isAddressValid: Boolean = true,
    val isLoading: Boolean = false,
    val error: String? = null,
    val maxAmount: Double = 0.0
)