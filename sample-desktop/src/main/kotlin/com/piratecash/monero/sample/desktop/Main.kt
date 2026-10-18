package com.piratecash.monero.sample.desktop

import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.piratecash.monero.MoneroNative
import com.piratecash.monero.sample.SampleApp
import com.piratecash.monero.sample.SampleController
import com.piratecash.monero.sample.SampleTheme
import java.io.File
import kotlinx.coroutines.runBlocking

fun main() = application {
    // Checked before any JNI class is touched: on an unsupported host that fails with an
    // ExceptionInInitializerError.
    val supported = MoneroNative.isHostSupported()
    val controller = remember {
        if (!supported) return@remember null
        val walletRoot = File(System.getProperty("user.home"), ".monero-kit-sample/wallets")
        SampleController(walletRoot).also { it.init() }
    }
    Window(
        onCloseRequest = {
            // Store the wallet before the process exits, or the next launch rescans.
            runBlocking { controller?.stop()?.join() }
            exitApplication()
        },
        title = "Monero Kit Sample",
    ) {
        if (controller != null) {
            SampleApp(controller)
        } else {
            SampleTheme { Text("Monero natives are not available for this platform.") }
        }
    }
}
