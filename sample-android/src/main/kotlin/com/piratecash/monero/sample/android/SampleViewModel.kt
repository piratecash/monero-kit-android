package com.piratecash.monero.sample.android

import androidx.lifecycle.ViewModel
import com.piratecash.monero.sample.SampleController
import java.io.File

internal class SampleViewModel(walletRoot: File) : ViewModel() {
    val controller = SampleController(walletRoot).also { it.init() }
}
