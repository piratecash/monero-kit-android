package com.piratecash.monero.signer

import com.m2049r.xmrwallet.model.Wallet
import com.m2049r.xmrwallet.model.WalletManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LedgerLogNativeTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun setLogLevel_debugDuringLedgerExchanges_logsNoDeviceTraffic() {
        val logFile = initLoggerInTemp()
        WalletManager.setLogLevel(DEBUG_LOG_LEVEL)
        val registration = ExternalSigner.registerApdu(SecretAnsweringLedger)
        try {
            WalletManager.getInstance()
                .createWalletFromDevice(File(temp.root, "ledger"), "", 1, Wallet.Device.Ledger)
                .close(false)
        } finally {
            registration.release()
            WalletManager.setLogLevel(LOGS_OFF)
        }

        val log = logFile.readText()
        assertTrue("debug level is not active", "\tDEBUG\t" in log)
        assertFalse(log, "CMD  :" in log || "RESP :" in log || SECRET_HEX in log)
    }

    @Test
    fun setLogLevel_everyLevel_neverInstallsUncappedDeviceLogging() {
        val logFile = initLoggerInTemp()
        val beforeLevels = logFile.readText()
        try {
            (MIN_LOG_LEVEL..MAX_LOG_LEVEL).forEach(WalletManager::setLogLevel)
        } finally {
            WalletManager.setLogLevel(LOGS_OFF)
        }

        // mlog logs every configuration it installs; one without the caps is a window for device secrets.
        val configurations = logFile.readText().removePrefix(beforeLevels).lines()
            .filter { CONFIGURATION_LOG in it }
            .map { it.substringAfter(CONFIGURATION_LOG) }
        assertEquals(configurations.toString(), MAX_LOG_LEVEL - MIN_LOG_LEVEL + 1, configurations.size)
        configurations.forEach { assertTrue(it, it.endsWith(DEVICE_CAPS)) }
    }

    private fun initLoggerInTemp(): File {
        // On Windows epee ignores argv0 and logs next to the JVM executable, out of the test's reach.
        assumeFalse("log path is not settable on Windows", System.getProperty("os.name").startsWith("Windows"))
        val logFile = File(temp.root, "native.log")
        WalletManager.initLogger(File(temp.root, "native").path, logFile.name)
        return logFile
    }

    /** Answers every APDU with a stand-in secret, as GET_KEY would with the private view key. */
    private object SecretAnsweringLedger : ExternalSignerApduChannel {
        override fun exchange(apdu: ByteArray): ByteArray = SECRET + SW_OK

        override fun cancel() = Unit
    }

    private companion object {
        const val DEBUG_LOG_LEVEL = 2
        const val LOGS_OFF = -1
        const val MIN_LOG_LEVEL = 0
        const val MAX_LOG_LEVEL = 4
        const val CONFIGURATION_LOG = "New log categories: "
        const val DEVICE_CAPS = ",device:WARNING,device.ledger:WARNING"
        val SECRET = ByteArray(32) { 0x5a }
        val SECRET_HEX = "5a".repeat(32)
        val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
    }
}
