package com.piratecash.monero.ui.balance

import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun BalanceScreen(
    uiState: MainUiState,
    onStartClick: () -> Unit,
    onDebugClick: () -> Unit,
    onClearClick: () -> Unit,
    onStatusClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(16.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            text = "Balance",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        SelectionContainer(
            modifier = Modifier
                .padding(vertical = 20.dp)
        ) {
            Text(
                text = "Address: ${uiState.address}",
                fontSize = 20.sp,
                fontWeight = FontWeight.Normal,
            )
        }
        // Network
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Network:",
                fontSize = 18.sp,
                fontWeight = FontWeight.Normal
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = uiState.networkName,
                fontSize = 18.sp,
                fontWeight = FontWeight.Normal
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Balance
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Balance:",
                fontSize = 18.sp,
                fontWeight = FontWeight.Normal
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = uiState.balance,
                fontSize = 18.sp,
                fontWeight = FontWeight.Normal
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Balance Unspendable
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Balance Unspendable:",
                fontSize = 18.sp,
                fontWeight = FontWeight.Normal
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = uiState.balanceUnspendable,
                fontSize = 18.sp,
                fontWeight = FontWeight.Normal
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // State
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "State:",
                fontSize = 18.sp,
                fontWeight = FontWeight.Normal
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = uiState.state,
                fontSize = 18.sp,
                fontWeight = FontWeight.Normal
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Last Block
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Last Block:",
                fontSize = 18.sp,
                fontWeight = FontWeight.Normal
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = uiState.lastBlock,
                fontSize = 18.sp,
                fontWeight = FontWeight.Normal
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Until
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Until:",
                fontSize = 18.sp,
                fontWeight = FontWeight.Normal
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = uiState.lastBlockDate,
                fontSize = 18.sp,
                fontWeight = FontWeight.Normal
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Button(
                    onClick = onStartClick
                ) {
                    Text("Start")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = onClearClick
                ) {
                    Text("Clear")
                }
            }

            Column {
                Button(
                    onClick = onDebugClick
                ) {
                    Text("Debug info")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = onStatusClick
                ) {
                    Text("Status")
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    BalanceScreen(
        uiState = MainUiState(
            networkName = "MainNet",
            balance = "100.0",
            balanceUnspendable = "10.0",
            state = "syncing",
            lastBlock = "123000",
            lastBlockDate = "2019-01-01 12:12:12"
        ),
        onStartClick = {},
        onDebugClick = {},
        onClearClick = {},
        onStatusClick = {}
    )
}