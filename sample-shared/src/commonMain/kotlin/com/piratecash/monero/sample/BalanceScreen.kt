package com.piratecash.monero.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun BalanceScreen(
    uiState: MainUiState,
    onStartClick: () -> Unit,
    onClearClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("Balance", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        SelectionContainer(modifier = Modifier.padding(vertical = 20.dp)) {
            Text("Address: ${uiState.address}", fontSize = 20.sp)
        }
        InfoRow("Network:", uiState.networkName)
        InfoRow("Balance:", uiState.balance)
        InfoRow("Balance Unspendable:", uiState.balanceUnspendable)
        InfoRow("State:", uiState.state)
        InfoRow("Last Block:", uiState.lastBlock)

        Spacer(modifier = Modifier.height(24.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Button(onClick = onStartClick) { Text("Start") }
            Button(onClick = onClearClick) { Text("Clear") }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(label, fontSize = 18.sp)
        Spacer(modifier = Modifier.width(16.dp))
        Text(value, fontSize = 18.sp)
    }
}

@Preview
@Composable
private fun BalanceScreenPreview() {
    SampleTheme {
        BalanceScreen(
            uiState = MainUiState(
                networkName = "NetworkType_Mainnet",
                balance = "100.0",
                balanceUnspendable = "10.0",
                state = "Syncing, 120 blocks left",
                lastBlock = "123000",
            ),
            onStartClick = {},
            onClearClick = {},
        )
    }
}
