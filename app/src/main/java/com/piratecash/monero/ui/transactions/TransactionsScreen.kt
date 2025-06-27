package com.piratecash.monero.ui.transactions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import com.piratecash.monero.ui.MainUiState
import java.text.SimpleDateFormat
import java.util.Date
import androidx.compose.ui.tooling.preview.Preview
import com.piratecash.monero.ui.TransactionUiModel
import androidx.compose.foundation.layout.Box

@Composable
fun TransactionsScreen(uiState: MainUiState) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = "Transactions",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))
        if (uiState.transactions.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = "No transactions found",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn {
                items(uiState.transactions) { tx ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            // Header row with direction and status (только текст)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = tx.direction,
                                    fontWeight = FontWeight.Bold,
                                    color = if (tx.direction == "Incoming") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (tx.isPending) {
                                        Text(
                                            text = "Pending",
                                            color = MaterialTheme.colorScheme.tertiary,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    if (tx.isFailed) {
                                        Text(
                                            text = "Failed",
                                            color = MaterialTheme.colorScheme.error,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Account: ${tx.account}", fontWeight = FontWeight.Bold)
                            if (!tx.notes.isNullOrEmpty()) Text("Notes: ${tx.notes}")
                            if (!tx.destination.isNullOrEmpty()) Text("Destination: ${tx.destination}")
                            if (!tx.paymentId.isNullOrEmpty()) Text("Payment ID: ${tx.paymentId}")
                            if (!tx.txId.isNullOrEmpty()) Text("Tx ID: ${tx.txId}")
                            if (!tx.txKey.isNullOrEmpty()) Text("Tx Key: ${tx.txKey}")
                            Text("Block: ${tx.block}")
                            Text("Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(tx.date * 1000))}")
                            Text("Fee: ${tx.fee}")
                            Text("Amount: ${tx.amount}")
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun TransactionsScreenPreview() {
    TransactionsScreen(
        uiState = MainUiState(
            transactions = listOf(
                TransactionUiModel(
                    account = 0,
                    notes = "Test note",
                    destination = "44Affq5kSiGBoZ...",
                    paymentId = "0000000000000000",
                    txId = "b1a2c3d4e5f6...",
                    txKey = "aabbccddeeff...",
                    block = 123456,
                    date = 1710000000,
                    fee = "15000",
                    amount = "1000",
                    isPending = false,
                    direction = "Incoming",
                    isFailed = false
                ),
                TransactionUiModel(
                    account = 1,
                    notes = null,
                    destination = "48fFq5kSiGBoZ...",
                    paymentId = null,
                    txId = "c2b3d4e5f6a7...",
                    txKey = null,
                    block = 123457,
                    date = 1710001000,
                    fee = "2000",
                    amount = "2000",
                    isPending = true,
                    direction = "Outgoing",
                    isFailed = false
                ),
                TransactionUiModel(
                    account = 2,
                    notes = "Failed transaction",
                    destination = "49gGq5kSiGBoZ...",
                    paymentId = null,
                    txId = "d3c4e5f6a7b8...",
                    txKey = null,
                    block = 123458,
                    date = 1710002000,
                    fee = "2500",
                    amount = "3000",
                    isPending = false,
                    direction = "Outgoing",
                    isFailed = true
                )
            )
        )
    )
} 