package com.piratecash.monero

import MainScreen
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import com.piratecash.monero.ui.balance.MainViewModel
import com.piratecash.monero.ui.theme.MonerokitandroidTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MonerokitandroidTheme {
                LaunchedEffect(Unit) {
                    viewModel.initWallet()
                }

                MainScreen(viewModel)
            }
        }
    }

    override fun onPause() {
        viewModel.stop()
        super.onPause()
    }
}