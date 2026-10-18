package com.piratecash.monero.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
internal fun SendScreen(
    uiState: MainUiState,
    onAddressChange: (String) -> Unit,
    onAmountChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onSendClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canSend = uiState.addressTo.isNotEmpty() &&
        (uiState.amountTo.toDoubleOrNull() ?: 0.0) > 0 &&
        uiState.isAddressToValid &&
        !uiState.isLoadingSending

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Send Monero", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        OutlinedTextField(
            value = uiState.addressTo,
            onValueChange = onAddressChange,
            label = { Text("Recipient Address") },
            modifier = Modifier.fillMaxWidth(),
            isError = !uiState.isAddressToValid,
            supportingText = { if (!uiState.isAddressToValid) Text("Invalid Monero address") },
            enabled = !uiState.isLoadingSending,
        )
        OutlinedTextField(
            value = uiState.amountTo,
            onValueChange = onAmountChange,
            label = { Text("Amount") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            suffix = { Text("XMR") },
            enabled = !uiState.isLoadingSending,
        )
        OutlinedTextField(
            value = uiState.notesTo,
            onValueChange = onNotesChange,
            label = { Text("Notes (Optional)") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
            enabled = !uiState.isLoadingSending,
        )

        uiState.errorSending?.let { error ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Text(error, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onSendClick,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            enabled = canSend,
        ) {
            if (uiState.isLoadingSending) CircularProgressIndicator(modifier = Modifier.size(18.dp))
            else Text("Send Monero", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Preview
@Composable
private fun SendScreenPreview() {
    SampleTheme {
        SendScreen(MainUiState(addressTo = "44Affq5kSiGBoZ...", amountTo = "0.5", errorSending = "Invalid amount"), {}, {}, {}, {})
    }
}
