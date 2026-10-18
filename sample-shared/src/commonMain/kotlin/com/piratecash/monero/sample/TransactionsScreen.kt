package com.piratecash.monero.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
internal fun TransactionsScreen(uiState: MainUiState, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("Transactions", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        if (uiState.transactions.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = "No transactions found",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn {
                items(uiState.transactions) { TransactionCard(it) }
            }
        }
    }
}

@Composable
private fun TransactionCard(tx: TransactionUiModel) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tx.direction,
                    fontWeight = FontWeight.Bold,
                    color = if (tx.direction == "Incoming") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (tx.isPending) Text("Pending", color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Medium)
                    if (tx.isFailed) Text("Failed", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
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
            Text("Date: ${tx.date}")
            Text("Fee: ${tx.fee}")
            Text("Amount: ${tx.amount}")
        }
    }
}

@Preview
@Composable
private fun TransactionsScreenPreview() {
    SampleTheme {
        TransactionsScreen(
            MainUiState(
                transactions = listOf(
                    TransactionUiModel(0, "Test note", "44Affq5kSiGBoZ...", null, "b1a2c3d4e5f6...", null, 123456, "2024-03-09 16:00:00", "0.00015", "1.0", false, "Incoming", false),
                    TransactionUiModel(1, null, "48fFq5kSiGBoZ...", null, "c2b3d4e5f6a7...", null, 123457, "2024-03-09 16:16:40", "0.00002", "2.0", true, "Outgoing", false),
                ),
            ),
        )
    }
}
