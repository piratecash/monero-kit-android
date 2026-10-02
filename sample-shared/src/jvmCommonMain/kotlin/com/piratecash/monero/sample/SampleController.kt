package com.piratecash.monero.sample

import com.m2049r.xmrwallet.data.DefaultNodes
import com.m2049r.xmrwallet.data.NodeInfo
import com.m2049r.xmrwallet.data.TxData
import com.m2049r.xmrwallet.data.UserNotes
import com.m2049r.xmrwallet.model.PendingTransaction
import com.m2049r.xmrwallet.model.TransactionInfo
import com.m2049r.xmrwallet.model.Wallet
import com.m2049r.xmrwallet.model.WalletManager
import com.m2049r.xmrwallet.service.MoneroWalletService
import com.m2049r.xmrwallet.util.Helper
import com.m2049r.xmrwallet.util.RestoreHeight
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val WALLET_NAME = "test"
private const val DISPLAY_DECIMALS = 5

/** Sample wallet logic over [MoneroWalletService]; [walletRoot] holds the wallet files. */
class SampleController(private val walletRoot: File) : MoneroWalletService.Observer {

    // Serial so operations run in call order; owned here so stop() outlives the UI host.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val mutableState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = mutableState.asStateFlow()

    private val walletService = MoneroWalletService(walletRoot)

    // Sample only: a random password kept beside the wallet directory, not production storage.
    private val walletPassword: String by lazy {
        val file = walletRoot.resolveSibling("sample-wallet.pass")
        if (file.exists()) file.readText() else randomPassword().also { file.writeText(it) }
    }

    fun init() {
        scope.launch {
            walletRoot.mkdirs()
            if (walletFiles().none { it.exists() }) createWallet()
        }
    }

    fun onStartClick() {
        scope.launch {
            val node = NodeInfo.fromString(SampleConfig.NODE.ifBlank { DefaultNodes.MONERUJO.uri })
            WalletManager.getInstance().setDaemon(node)
            // stop() clears the observer, so register it for every session.
            walletService.setObserver(this@SampleController)
            walletService.start(WALLET_NAME, walletPassword)
            val wallet = walletService.wallet
            mutableState.update {
                it.copy(networkName = wallet?.networkType?.name.orEmpty(), address = wallet?.address.orEmpty())
            }
        }
    }

    fun stop(): Job =
        scope.launch {
            walletService.stop()
            mutableState.update { it.copy(state = "Not synced") }
        }

    fun onClearClick() {
        scope.launch {
            walletService.stop(false)
            mutableState.update { it.copy(state = "Not synced") }
            walletFiles().forEach { it.delete() }
            check(walletFiles().none { it.exists() }) { "Wallet files not removed" }
            createWallet()
        }
    }

    fun onAddressChange(address: String) = mutableState.update { it.copy(addressTo = address) }

    fun onAmountChange(amount: String) = mutableState.update { it.copy(amountTo = amount) }

    fun onNotesChange(notes: String) = mutableState.update { it.copy(notesTo = notes) }

    fun onSendClick() {
        val state = mutableState.value
        val wallet = walletService.wallet
        if (wallet == null) {
            mutableState.update { it.copy(errorSending = "Wallet not loaded") }
            return
        }
        scope.launch {
            if (!Wallet.isAddressValid(state.addressTo)) {
                mutableState.update { it.copy(isAddressToValid = false, errorSending = "Invalid address") }
                return@launch
            }
            val amount = state.amountTo.toDoubleOrNull()?.takeIf { it > 0 }
            if (amount == null) {
                mutableState.update { it.copy(errorSending = "Invalid amount") }
                return@launch
            }
            mutableState.update { it.copy(isLoadingSending = true, errorSending = null) }
            try {
                val txData = TxData().apply {
                    destination = state.addressTo
                    setAmount(amount)
                    setMixin(wallet.defaultMixin)
                    setPriority(PendingTransaction.Priority.Priority_Default)
                    setUserNotes(UserNotes(state.notesTo))
                }
                walletService.prepareTransaction(txData)
                walletService.sendTransaction(state.notesTo)
                mutableState.update { it.copy(isLoadingSending = false, amountTo = "", notesTo = "") }
            } catch (e: Exception) {
                mutableState.update { it.copy(isLoadingSending = false, errorSending = e.message) }
            }
        }
    }

    override fun onRefreshed(wallet: Wallet?, full: Boolean): Boolean {
        wallet ?: return true
        val daemonHeight = WalletManager.getInstance().blockchainHeight
        val blocksLeft = if (full) 0 else daemonHeight - wallet.blockChainHeight
        mutableState.update {
            it.copy(
                balance = Helper.getDisplayAmount(wallet.balanceAll, DISPLAY_DECIMALS),
                balanceUnspendable = (wallet.balance - wallet.unlockedBalance).toString(),
                lastBlock = daemonHeight.toString(),
                state = if (blocksLeft == 0L) "Synced" else "Syncing, $blocksLeft blocks left",
                transactions = wallet.history.all.map { tx -> tx.toUiModel() },
            )
        }
        return true
    }

    override fun onProgress(text: String?) = Unit

    override fun onProgress(n: Int) = mutableState.update { it.copy(state = "Syncing...") }

    override fun onWalletStarted(walletStatus: Wallet.Status?) = Unit

    override fun onWalletOpen(device: Wallet.Device?) = Unit

    private fun createWallet() {
        val wallet = WalletManager.getInstance().recoveryWallet(
            File(walletRoot, WALLET_NAME), walletPassword, SampleConfig.WORDS, "", restoreHeight(),
        )
        wallet.close()
        // A recovered wallet's cache file is corrupt; deleting it forces a clean rescan.
        File(walletRoot, WALLET_NAME).delete()
    }

    private fun walletFiles() = listOf(WALLET_NAME, "$WALLET_NAME.keys", "$WALLET_NAME.address.txt")
        .map { File(walletRoot, it) }

    /** [SampleConfig.RESTORE_HEIGHT] is a `yyyy-MM-dd` or `yyyyMMdd` date, or a block height; -1 when unset or invalid. */
    private fun restoreHeight(): Long {
        val value = SampleConfig.RESTORE_HEIGHT
        val dateHeight = dateHeight(value, "yyyy-MM-dd")
            ?: value.takeIf { it.length == 8 }?.let { dateHeight(it, "yyyyMMdd") }
        return dateHeight ?: value.toLongOrNull() ?: -1
    }

    private fun dateHeight(value: String, pattern: String): Long? = tryOrNull {
        SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(value)
    }?.let { RestoreHeight.getInstance().getHeight(it) }?.takeIf { it >= 0 }

    private fun TransactionInfo.toUiModel() = TransactionUiModel(
        account = accountIndex,
        notes = notes,
        destination = address,
        paymentId = paymentId,
        txId = hash,
        txKey = txKey,
        block = blockheight,
        date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestamp * 1000)),
        fee = Helper.getDisplayAmount(fee, DISPLAY_DECIMALS),
        amount = Helper.getDisplayAmount(amount, DISPLAY_DECIMALS),
        isPending = isPending,
        direction = if (direction == TransactionInfo.Direction.Direction_In) "Incoming" else "Outgoing",
        isFailed = isFailed,
    )

    private fun randomPassword() = ByteArray(32).also(SecureRandom()::nextBytes)
        .joinToString("") { "%02x".format(it) }

    private inline fun <T> tryOrNull(block: () -> T): T? = try {
        block()
    } catch (_: Exception) {
        null
    }
}
