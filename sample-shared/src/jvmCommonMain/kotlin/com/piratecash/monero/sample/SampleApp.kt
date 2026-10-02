package com.piratecash.monero.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SampleApp(controller: SampleController) {
    val uiState by controller.uiState.collectAsStateWithLifecycle()
    SampleContent(
        uiState = uiState,
        onStartClick = controller::onStartClick,
        onClearClick = controller::onClearClick,
        onAddressChange = controller::onAddressChange,
        onAmountChange = controller::onAmountChange,
        onNotesChange = controller::onNotesChange,
        onSendClick = controller::onSendClick,
    )
}
