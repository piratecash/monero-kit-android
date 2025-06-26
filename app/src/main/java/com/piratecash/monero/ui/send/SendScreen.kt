package com.piratecash.monero.ui.send

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.piratecash.monero.ui.theme.MonerokitandroidTheme

@Composable
fun SendScreen(
    uiState: SendUiState,
    onAddressChange: (String) -> Unit,
    onAmountChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onSendClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Send Monero",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        // Address Field
        OutlinedTextField(
            value = uiState.address,
            onValueChange = onAddressChange,
            label = { Text("Recipient Address") },
            placeholder = { Text("Enter Monero address") },
            modifier = Modifier.fillMaxWidth(),
            isError = uiState.address.isNotEmpty() && !uiState.isAddressValid,
            supportingText = {
                if (uiState.address.isNotEmpty() && !uiState.isAddressValid) {
                    Text(
                        text = "Invalid Monero address",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            enabled = !uiState.isLoading
        )

        // Amount Field
        OutlinedTextField(
            value = uiState.amount,
            onValueChange = onAmountChange,
            label = { Text("Amount") },
            placeholder = { Text("0.00") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            suffix = { Text("XMR") },
            enabled = !uiState.isLoading,
            supportingText = {
                if (uiState.maxAmount > 0) {
                    Text("Available: ${uiState.maxAmount} XMR")
                }
            }
        )

        // Notes Field (Optional)
        OutlinedTextField(
            value = uiState.notes,
            onValueChange = onNotesChange,
            label = { Text("Notes (Optional)") },
            placeholder = { Text("Add a note for this transaction") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
            enabled = !uiState.isLoading
        )

        // Error message
        if (uiState.error != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = uiState.error,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Transaction Summary Card
        if (uiState.address.isNotEmpty() && uiState.amount.isNotEmpty() && uiState.isAddressValid) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Transaction Summary",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Amount:")
                        Text("${uiState.amount} XMR", fontWeight = FontWeight.Medium)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Network Fee:")
                        Text("~0.00015 XMR", fontWeight = FontWeight.Medium)
                    }

                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Total:", fontWeight = FontWeight.Bold)
                        Text(
                            text = "${(uiState.amount.toDoubleOrNull() ?: 0.0) + 0.00015} XMR",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Send Button
        Button(
            onClick = onSendClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            enabled = uiState.address.isNotEmpty() &&
                    uiState.amount.isNotEmpty() &&
                    uiState.isAddressValid &&
                    (uiState.amount.toDoubleOrNull() ?: 0.0) > 0 &&
                    !uiState.isLoading,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (uiState.isLoading) "Sending..." else "Send Monero",
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SendScreenPreview() {
    MonerokitandroidTheme {
        SendScreen(
            uiState = SendUiState(),
            onAddressChange = {},
            onAmountChange = {},
            onNotesChange = {},
            onSendClick = {},
        )
    }
}
