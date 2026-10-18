package com.piratecash.monero.signer

import com.m2049r.xmrwallet.data.Node
import com.m2049r.xmrwallet.model.Wallet
import com.m2049r.xmrwallet.model.WalletManager
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Monero on the Ledger app in speculos through the kit's APDU channel; skipped unless `SPECULOS_APDU_PORT` is set. */
class LedgerSpeculosTest {
    @get:Rule
    val temp = TemporaryFolder()

    @get:Rule
    val timeout: Timeout = Timeout.seconds(300)

    private val walletManager = WalletManager.getInstance()
    private lateinit var walletFile: File

    @Before
    fun setUp() {
        assumeTrue("SPECULOS_APDU_PORT is not set", Speculos.apduPort != null)
        walletFile = File(temp.root, "ledger")
    }

    @After
    fun tearDown() {
        walletManager.setDaemon(null)
    }

    @Test
    fun createWalletFromDevice_ledger_derivesDeviceAddress() {
        val address = withSpeculosLedger {
            val wallet = walletManager.createWalletFromDevice(walletFile, PASSWORD, 1, Wallet.Device.Ledger)
            try {
                assertTrue(wallet.status.toString(), wallet.status.isOk)
                wallet.address
            } finally {
                wallet.close(true)
            }
        }

        assertEquals(SPECULOS_SEED_ADDRESS, address)
    }

    @Test
    fun openWallet_ledgerConnected_opensWithFilesUnchanged() {
        createStoredWallet()
        val files = walletDigest()

        val status = withSpeculosLedger { openAndClose() }

        assertTrue(status.toString(), status.isOk)
        assertEquals(files, walletDigest())
    }

    @Test
    fun openWallet_noChannelRegistered_throwsDeviceNotFound() {
        createStoredWallet()
        val files = walletDigest()

        val error = runCatching { walletManager.openWallet(walletFile.path, PASSWORD) }.exceptionOrNull()

        assertEquals(HardwareWalletErrorCode.DeviceNotFound, (error as ExternalSignerException).hardwareErrorCode)
        assertEquals(files, walletDigest())
    }

    @Test
    fun openWallet_deviceLostMidOpen_reportsDisconnectedNotCorruption() {
        createStoredWallet()
        val files = walletDigest()
        val unplugged = SpeculosApduChannel { number, _ ->
            if (number == LOST_AT_APDU) throw ExternalSignerException(HardwareWalletErrorCode.Disconnected, "unplugged")
        }

        val status = withSpeculosLedger(channel = unplugged) { openAndClose(store = true) }

        assertEquals(Wallet.StatusEnum.Status_Error, status.status)
        assertEquals(HardwareWalletErrorCode.Disconnected, status.hardwareWalletError)
        assertEquals(files, walletDigest())
    }

    @Test
    fun openWallet_viewKeyExportRejected_failsWithViewKeyExportDenied() {
        createStoredWallet()
        val files = walletDigest()

        val status = withSpeculosLedger(approve = false) { openAndClose(store = true) }

        assertEquals(Wallet.StatusEnum.Status_Error, status.status)
        assertEquals(HardwareWalletErrorCode.ViewKeyExportDenied, status.hardwareWalletError)
        assertEquals(files, walletDigest())
    }

    @Test
    fun deviceShowAddress_ledgerWallet_showsSubaddressOnDevice() {
        createStoredWallet()

        val (subaddress, screens) = withSpeculosLedger { walker ->
            val wallet = walletManager.openWallet(walletFile.path, PASSWORD)
            try {
                wallet.deviceShowAddress(0, 1, "")
                wallet.getSubaddress(0, 1) to walker.screens.toList()
            } finally {
                wallet.close(false)
            }
        }

        assertTrue(screens.toString(), screens.any { it.contains(subaddress.take(16)) })
    }

    @Test
    fun init_neverSyncedLedgerWallet_keepsRestoreHeight() {
        createStoredWallet(restoreHeight = RESTORE_HEIGHT)

        val restoreHeight = FakeDaemon(DAEMON_HEIGHT).use { daemon ->
            walletManager.setDaemon(Node.fromString(daemon.address))
            withSpeculosLedger {
                val wallet = walletManager.openWallet(walletFile.path, PASSWORD)
                try {
                    assertTrue(wallet.status.toString(), wallet.init(0))
                    wallet.restoreHeight
                } finally {
                    wallet.close(false)
                }
            }
        }

        assertEquals(RESTORE_HEIGHT, restoreHeight)
    }

    @Test
    fun openWallet_cancelledWhileCacheLoadFails_keepsCorruptionStatus() {
        createStoredWallet()
        walletFile.delete()
        assertEquals(0, ProcessBuilder("mkfifo", walletFile.path).start().waitFor())
        // wallet2 opens the cache twice (read, then copy aside); the device exchanges are over by the first.
        val cancelBetweenCacheReads = thread(isDaemon = true) {
            FileOutputStream(walletFile).close()
            ExternalSignerNativeBridge.signalCancellation(ExternalSignerNativeBridge.currentGeneration())
            FileOutputStream(walletFile).close()
        }

        val status = withSpeculosLedger { openAndClose() }

        cancelBetweenCacheReads.join(CANCEL_JOIN_MILLIS)
        assertFalse("cache was not read twice", cancelBetweenCacheReads.isAlive)
        assertEquals(Wallet.StatusEnum.Status_Critical, status.status)
        assertEquals(HardwareWalletErrorCode.Protocol, status.hardwareWalletError)
    }

    @Test
    fun signUnsignedTx_deviceLostAtOpenTx_freesDeviceForOtherThreads() = assertDeviceFreeAfterLostSigning(INS_OPEN_TX)

    @Test
    fun signUnsignedTx_deviceLostInsideTransaction_freesDeviceForOtherThreads() = assertDeviceFreeAfterLostSigning(INS_STEALTH)

    // A leaked device lock makes the other thread time out. No re-sign: speculos exits on a cold-signed INS_BLIND.
    private fun assertDeviceFreeAfterLostSigning(lostAtIns: Int) {
        createStoredWallet()
        val unsignedTx = File(temp.root, "unsigned_monero_tx")
        unsignedTx.writeBytes(requireNotNull(javaClass.getResource(UNSIGNED_TX_RESOURCE)).readBytes())
        // Opened under an approving walker: the open asks to export the view key.
        val wallet = withSpeculosLedger { walletManager.openWallet(walletFile.path, PASSWORD) }
        val lostAt = AtomicInteger(NOT_LOST)
        val unplugged = SpeculosApduChannel { _, apdu ->
            val ins = apdu[1].toInt() and 0xff
            if (ins == lostAtIns) lostAt.compareAndSet(NOT_LOST, ins)
            if (lostAt.get() != NOT_LOST) throw ExternalSignerException(HardwareWalletErrorCode.Disconnected, "unplugged")
        }

        val failure = withSpeculosLedger(channel = unplugged) { signUnsignedTx(wallet, unsignedTx) }
        val otherThread = Executors.newSingleThreadExecutor { Thread(it).apply { isDaemon = true } }
        val showStatus = withSpeculosLedger {
            otherThread.submit<Wallet.Status> {
                wallet.deviceShowAddress(0, 1, "")
                wallet.status
            }.get(DEVICE_FREE_SECONDS, TimeUnit.SECONDS)
        }
        wallet.close(false)

        assertEquals("device was never lost", lostAtIns, lostAt.get())
        assertTrue("lost signing unexpectedly succeeded", failure != null)
        assertTrue(showStatus.toString(), showStatus.isOk)
    }

    /** Returns null once signed, else the wallet's error. */
    private fun signUnsignedTx(wallet: Wallet, unsignedTx: File): String? {
        val tx = requireNotNull(wallet.loadUnsignedTx(unsignedTx.path)) { wallet.status.toString() }
        try {
            return if (tx.sign(File(temp.root, "signed_monero_tx").path)) null else tx.errorString.ifEmpty { "sign failed" }
        } finally {
            tx.dispose()
        }
    }

    private fun createStoredWallet(restoreHeight: Long = 1) = withSpeculosLedger {
        val wallet = walletManager.createWalletFromDevice(walletFile, PASSWORD, restoreHeight, Wallet.Device.Ledger)
        assertTrue(wallet.status.toString(), wallet.status.isOk)
        assertTrue(wallet.close(true))
    }

    private fun openAndClose(store: Boolean = false): Wallet.Status {
        val wallet = walletManager.openWallet(walletFile.path, PASSWORD)
        val status = wallet.status
        wallet.close(store)
        return status
    }

    private fun walletDigest(): List<String> = listOf(walletFile, File("$walletFile.keys")).map { file ->
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    }

    /** Just enough of monerod's JSON-RPC for wallet2 to see a synced mainnet daemon at [height]. */
    private class FakeDaemon(height: Long) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/json_rpc") { exchange ->
                val request = exchange.requestBody.readBytes().decodeToString()
                val result = when {
                    "\"get_version\"" in request ->
                        """{"version":$RPC_VERSION,"release":true,"current_height":$height,"target_height":0,""" +
                            """"hard_forks":[${MAINNET_FORKS.joinToString { (version, from) -> """{"hf_version":$version,"height":$from}""" }}],""" +
                            """"status":"OK","untrusted":false}"""
                    else -> """{"height":$height,"target_height":0,"status":"OK","untrusted":false}"""
                }
                val body = """{"jsonrpc":"2.0","id":"0","result":$result}""".encodeToByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }

        val address: String get() = "127.0.0.1:${server.address.port}"

        override fun close() = server.stop(0)

        private companion object {
            const val RPC_VERSION = (3 shl 16) or 14
            val MAINNET_FORKS = listOf(
                1 to 1, 2 to 1009827, 3 to 1141317, 4 to 1220516, 5 to 1288616, 6 to 1400000, 7 to 1546000,
                8 to 1685555, 9 to 1686275, 10 to 1788000, 11 to 1788720, 12 to 1978433, 13 to 2210000,
                14 to 2210720, 15 to 2688888, 16 to 2689608,
            )
        }
    }

    private companion object {
        const val PASSWORD = "ledger-test"
        const val LOST_AT_APDU = 4
        const val RESTORE_HEIGHT = 3_000_000L
        const val DAEMON_HEIGHT = 3_500_000L
        const val CANCEL_JOIN_MILLIS = 10_000L
        const val INS_OPEN_TX = 0x70
        const val INS_STEALTH = 0x76
        const val NOT_LOST = -1
        const val DEVICE_FREE_SECONDS = 60L

        // Regtest transfer from the speculos seed's watch-only wallet: signing it needs no daemon.
        const val UNSIGNED_TX_RESOURCE = "/ledger/unsigned_monero_tx"

        // speculos' default seed
        const val SPECULOS_SEED_ADDRESS =
            "44Mj3HHnj7pPhnLepXWRyk25oxQTNv2yCQQMtBLtUTxQVAJQ3BX6WPrFxe1yosuNocYh1E6NV53ARUDJ6Xmv28VwQUL97KW"
    }
}
