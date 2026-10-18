package com.piratecash.monero.sample

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

private enum class Tab(val title: String) { Balance("Balance"), Transactions("Transactions"), Send("Send") }

@Composable
fun SampleContent(
    uiState: MainUiState,
    onStartClick: () -> Unit,
    onClearClick: () -> Unit,
    onAddressChange: (String) -> Unit,
    onAmountChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onSendClick: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(Tab.Balance) }

    SampleTheme {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            icon = {},
                            label = { Text(tab.title) },
                        )
                    }
                }
            },
        ) { innerPadding ->
            val modifier = Modifier.padding(innerPadding)
            when (selectedTab) {
                Tab.Balance -> BalanceScreen(uiState, onStartClick, onClearClick, modifier)
                Tab.Transactions -> TransactionsScreen(uiState, modifier)
                Tab.Send -> SendScreen(uiState, onAddressChange, onAmountChange, onNotesChange, onSendClick, modifier)
            }
        }
    }
}

@Preview
@Composable
private fun SampleContentPreview() {
    SampleContent(MainUiState(balance = "1.5"), {}, {}, {}, {}, {}, {})
}
