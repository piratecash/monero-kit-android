package com.piratecash.monero.sample.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m2049r.levin.util.NetCipherHelper
import com.m2049r.levin.util.NetCipherHelper.OnStatusChangedListener
import com.m2049r.xmrwallet.model.WalletManager
import com.piratecash.monero.MoneroWalletFiles
import com.piratecash.monero.sample.SampleApp

class MainActivity : ComponentActivity() {
    private val walletRoot by lazy { MoneroWalletFiles.root(this) }
    private var viewModel: SampleViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NetCipherHelper.createInstance(applicationContext)
        NetCipherHelper.register(object : OnStatusChangedListener {
            override fun connected() {
                WalletManager.getInstance().setProxy(NetCipherHelper.getProxy())
            }

            override fun disconnected() {
                WalletManager.getInstance().setProxy("")
            }

            override fun notInstalled() = disconnected()

            override fun notEnabled() = disconnected()
        })
        setContent {
            val sampleViewModel = viewModel { SampleViewModel(walletRoot) }
            viewModel = sampleViewModel
            SampleApp(sampleViewModel.controller)
        }
    }

    override fun onPause() {
        viewModel?.controller?.stop()
        super.onPause()
    }
}
