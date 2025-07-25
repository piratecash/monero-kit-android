package com.piratecash.monero.ui

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m2049r.levin.util.NetCipherHelper
import com.m2049r.levin.util.NetCipherHelper.OnStatusChangedListener
import com.m2049r.xmrwallet.data.DefaultNodes
import com.m2049r.xmrwallet.data.NodeInfo
import com.m2049r.xmrwallet.data.TxData
import com.m2049r.xmrwallet.data.UserNotes
import com.m2049r.xmrwallet.model.NetworkType
import com.m2049r.xmrwallet.model.PendingTransaction
import com.m2049r.xmrwallet.model.TransactionInfo
import com.m2049r.xmrwallet.model.Wallet
import com.m2049r.xmrwallet.model.WalletManager
import com.m2049r.xmrwallet.service.MoneroWalletService
import com.m2049r.xmrwallet.util.Helper
import com.m2049r.xmrwallet.util.KeyStoreHelper
import com.m2049r.xmrwallet.util.NodePinger
import com.m2049r.xmrwallet.util.RestoreHeight
import com.piratecash.monero.BuildConfig
import com.piratecash.monero.MyApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Collections

class MainViewModel : ViewModel(), MoneroWalletService.Observer {

    private companion object {
        const val WALLET_NAME = "test"
        const val TAG = "MainViewModel"
    }

    val uiState = mutableStateOf<MainUiState>(MainUiState())

    private lateinit var walletService: MoneroWalletService

    private fun registerTor() {
        NetCipherHelper.register(object : OnStatusChangedListener {
            override fun connected() {
                Timber.d("CONNECTED")
                WalletManager.getInstance().setProxy(NetCipherHelper.getProxy())
            }

            override fun disconnected() {
                Timber.d("DISCONNECTED")
                WalletManager.getInstance().setProxy("")
            }

            override fun notInstalled() {
                Timber.d("NOT INSTALLED")
                WalletManager.getInstance().setProxy("")
            }

            override fun notEnabled() {
                Timber.d("NOT ENABLED")
                notInstalled()
            }
        })
    }

    fun stop() {
        viewModelScope.launch {
            walletService.stop()

            uiState.value = uiState.value.copy(
                state = "Not synced"
            )
        }
    }

    fun initWallet() {
        NetCipherHelper.createInstance(MyApplication.instance)
        Helper.setMoneroHome(MyApplication.instance)

        if (BuildConfig.DEBUG) {
            Helper.initLogger(MyApplication.instance, WalletManager.LOGLEVEL_DEBUG)
        }

        NetCipherHelper.register(object : OnStatusChangedListener {
            override fun connected() {
                Timber.d("CONNECTED")
                WalletManager.getInstance().setProxy(NetCipherHelper.getProxy())
            }

            override fun disconnected() {
                Timber.d("DISCONNECTED")
                WalletManager.getInstance().setProxy("")
            }

            override fun notInstalled() {
                Timber.d("NOT INSTALLED")
                WalletManager.getInstance().setProxy("")
            }

            override fun notEnabled() {
                Timber.d("NOT ENABLED")
                notInstalled()
            }
        })

        if (!isWalletFilesExist(MyApplication.Companion.instance, WALLET_NAME)) {
            createWallet()
        }

        walletService = MoneroWalletService(MyApplication.Companion.instance)
        walletService.setObserver(this)

//        walletService.stop() - must be called in the end
    }

    fun onStartClick() {
        viewModelScope.launch(Dispatchers.IO) {
            val fastestNode = NodeInfo.fromString(DefaultNodes.MONERUJO.uri)
            WalletManager.getInstance().setDaemon(fastestNode)

            val crazyPass: String? = KeyStoreHelper.getCrazyPass(MyApplication.Companion.instance, "")
            walletService.start(WALLET_NAME, crazyPass)

            uiState.value = uiState.value.copy(
                networkName = walletService.wallet?.networkType?.name ?: "",
                address = walletService.wallet?.address ?: ""
            )
        }
    }

    fun clearWallet() {
        viewModelScope.launch(Dispatchers.Default) {
            walletService.stop(false)
            uiState.value = uiState.value.copy(
                state = "Not synced"
            )
            removeWalletRelatedFiles(MyApplication.Companion.instance, WALLET_NAME)
            if(!isWalletFilesExist(MyApplication.Companion.instance, WALLET_NAME)) {
                Timber.tag(TAG).d("Wallet files removed")
            } else {
                throw Exception("Wallet files not removed")
            }
            initWallet()
        }
    }


    private suspend fun getNodes(): Set<NodeInfo> {
        return DefaultNodes.entries.mapNotNull { node ->
            NodeInfo.fromString(node.uri)
        }.toSet()
    }

    private fun autoselect(nodes: Set<NodeInfo>): NodeInfo? {
        if (nodes.isEmpty()) return null
        NodePinger.execute(nodes, null)
        val nodeList: MutableList<NodeInfo?> = ArrayList<NodeInfo?>(nodes)
        Collections.sort<NodeInfo?>(nodeList, NodeInfo.BestNodeComparator)
        return nodeList[0]
    }

    private fun createWallet() {
        // create the real wallet password
        val crazyPass: String? = KeyStoreHelper.getCrazyPass(MyApplication.instance, "")
        val seed = BuildConfig.WORDS
        val restoreHeight = getHeight(BuildConfig.RESTORE_HEIGHT)
        WalletManager.getInstance()
            .recoveryWallet(getWalletFullPath(), crazyPass, seed, "", restoreHeight)

        val walletFolder: File = Helper.getWalletRoot(MyApplication.instance)
        val walletFile = File(walletFolder, WALLET_NAME)
        Timber.d("New Wallet %s", walletFile.absolutePath)
        // NEXT line is VERY important for correct update
        walletFile.delete() // when recovering wallets, the cache seems corrupt - so remove it
    }

    /***
     * @param dateOrHeight - YYYY-MM-DD or height
     */
    private fun getHeight(dateOrHeight: String): Long {
        var height: Long = -1

        if (dateOrHeight.isEmpty()) return -1
        if (WalletManager.getInstance().networkType === NetworkType.NetworkType_Mainnet) {
            try {
                // is it a date?
                val parser = SimpleDateFormat("yyyy-MM-dd")
                parser.isLenient = false
                height = RestoreHeight.getInstance().getHeight(parser.parse(dateOrHeight))
            } catch (ignored: ParseException) {
            }
            if ((height < 0) && (dateOrHeight.length == 8)) try {
                // is it a date without dashes?
                val parser = SimpleDateFormat("yyyyMMdd")
                parser.isLenient = false
                height = RestoreHeight.getInstance().getHeight(parser.parse(dateOrHeight))
            } catch (_: ParseException) {
            }
        }
        if (height < 0) try {
            // or is it a height?
            height = dateOrHeight.toLong()
        } catch (ex: NumberFormatException) {
            return -1
        }
        return height
    }

    private fun isWalletFilesExist(context: Context, walletName: String) =
        getFileNameIfNotExists(context, walletName) == null

    private fun getFileNameIfNotExists(context: Context, walletName: String): File? {
        // check if the wallet we want to create already exists
        val walletFolder: File = Helper.getWalletRoot(context);
        if (!walletFolder.isDirectory()) {
            Timber.e("Wallet dir " + walletFolder.absolutePath + "is not a directory")
            return null
        }
        val cacheFile = File(walletFolder, walletName)
        val keysFile = File(walletFolder, "$walletName.keys")
        val addressFile = File(walletFolder, "$walletName.address.txt")

        if (cacheFile.exists() || keysFile.exists() || addressFile.exists()) {
            Timber.e("Some wallet files already exist for %s", cacheFile.absolutePath)
            return null
        }

        return File(walletFolder, walletName)
    }

    private fun getWalletFullPath() =
        File(Helper.getWalletRoot(MyApplication.instance), WALLET_NAME)

    private fun removeWalletRelatedFiles(context: Context, walletName: String) {
        // check if the wallet we want to create already exists
        val walletFolder: File = Helper.getWalletRoot(context);
        if (!walletFolder.isDirectory()) {
            Timber.e("Wallet dir " + walletFolder.absolutePath + "is not a directory")
        }
        File(walletFolder, walletName).delete()
        File(walletFolder, "$walletName.keys").delete()
        File(walletFolder, "$walletName.address.txt").delete()
    }

    private fun updateTransactions(wallet: Wallet) {
        val txs = wallet.getHistory().all.map { tx ->
            TransactionUiModel(
                account = tx.accountIndex,
                notes = tx.notes,
                destination = tx.address,
                paymentId = tx.paymentId,
                txId = tx.hash,
                txKey = tx.txKey,
                block = tx.blockheight,
                date = tx.timestamp,
                fee = Helper.getDisplayAmount(tx.fee, 5),
                amount = Helper.getDisplayAmount(tx.amount, 5),
                isPending = tx.isPending,
                direction = if (tx.direction == TransactionInfo.Direction.Direction_In) "Incoming" else "Outgoing",
                isFailed = tx.isFailed
            )
        }
        uiState.value = uiState.value.copy(transactions = txs)
    }

    override fun onRefreshed(
        wallet: Wallet?,
        full: Boolean
    ): Boolean {
        val wallet = wallet ?: return true
        Timber.tag(TAG)
            .d("Balance: ${wallet.getBalanceAll()}")
        Timber.tag(TAG)
            .d("Unlocked balance: ${wallet.getUnlockedBalanceAll()}")
        Timber.tag(TAG)
            .d("onRefreshed() called with blocks: wallet ${wallet.blockChainHeight}, walletManager ${WalletManager.getInstance().blockchainHeight},")
        val blocksLeft = if (full) {
            0
        } else {
            WalletManager.getInstance().blockchainHeight - wallet.blockChainHeight
        }
        val lockedBalance = (wallet.balance) - (wallet.unlockedBalance)
        uiState.value = uiState.value.copy(
            balance = Helper.getDisplayAmount(wallet.getBalanceAll(), 5),
            balanceUnspendable = lockedBalance.toString(),
            lastBlock = WalletManager.getInstance().blockchainHeight.toString(),
            state = if (blocksLeft == 0L) "Synced" else "Syncing, $blocksLeft blocks left"
        )
        updateTransactions(wallet)
        Log.d(TAG, "onRefreshed() called with: isSynchronized = ${wallet.isSynchronized}, secretViewKey = ${wallet.secretViewKey}, full = $full")
        return true
    }

    fun onAddressChange(address: String) {
        uiState.value = uiState.value.copy(addressTo = address)
    }

    fun onAmountChange(amount: String) {
        uiState.value = uiState.value.copy(amountTo = amount)
    }

    fun onNotesChange(notes: String) {
        uiState.value = uiState.value.copy(notesTo = notes)
    }

    fun onSendClick() {
        val address = uiState.value.addressTo
        val amountStr = uiState.value.amountTo
        val notes = uiState.value.notesTo
        val wallet = walletService.wallet
        if (wallet == null) {
            uiState.value = uiState.value.copy(errorSending = "Wallet not loaded")
            return
        }
        viewModelScope.launch {
            val isValid = withContext(Dispatchers.Default) {
                Wallet.isAddressValid(address)
            }
            if (!isValid) {
                uiState.value = uiState.value.copy(isAddressToValid = false, errorSending = "Invalid address")
                return@launch
            }
            val amount = amountStr.toDoubleOrNull()
            if (amount == null || amount <= 0) {
                uiState.value = uiState.value.copy(errorSending = "Invalid amount")
                return@launch
            }
            uiState.value = uiState.value.copy(isLoadingSending = true, errorSending = null)
            withContext(Dispatchers.IO) {
                try {
                    val txData = TxData()
                    txData.destination = address
                    txData.setAmount(amount)
                    txData.setMixin(wallet.getDefaultMixin())
                    txData.setPriority(PendingTransaction.Priority.Priority_Default)
                    txData.setUserNotes(UserNotes(notes))
                    walletService.prepareTransaction("send", txData)
                    walletService.sendTransaction(notes)
                } catch (e: Exception) {
                    uiState.value = uiState.value.copy(isLoadingSending = false, errorSending = e.message)
                }
            }
        }
    }

    override fun onProgress(text: String?) {
        Log.d(TAG, "onProgress() called with: text = $text")
    }

    override fun onProgress(n: Int) {
        Log.d(TAG, "onProgress() called with: n = $n")
        uiState.value = uiState.value.copy(
            state = "Syncing..."
        )
    }

    override fun onWalletStored(success: Boolean) {
        Log.d(TAG, "onWalletStored() called with: success = $success")
    }

    override fun onTransactionCreated(
        tag: String?,
        pendingTransaction: PendingTransaction?
    ) {
        Log.d(TAG, "onTransactionCreated() called with: tag = $tag, pendingTransaction = $pendingTransaction")
        uiState.value = uiState.value.copy(isLoadingSending = false)
    }

    override fun onTransactionSent(txid: String?) {
        Log.d(TAG, "onTransactionSent() called with: txid = $txid")
        uiState.value = uiState.value.copy(isLoadingSending = false, errorSending = null, addressTo = "", amountTo = "", notesTo = "")
    }

    override fun onSendTransactionFailed(error: String?) {
        Log.d(TAG, "onSendTransactionFailed() called with: error = $error")
        uiState.value = uiState.value.copy(isLoadingSending = false, errorSending = error)
    }

    override fun onWalletStarted(walletStatus: Wallet.Status?) {
        Log.d(TAG, "onWalletStarted() called with: walletStatus = $walletStatus")
    }

    override fun onWalletOpen(device: Wallet.Device?) {
        Log.d(TAG, "onWalletOpen() called with: device = $device")
    }
}

data class TransactionUiModel(
    val account: Int,
    val notes: String?,
    val destination: String?,
    val paymentId: String?,
    val txId: String?,
    val txKey: String?,
    val block: Long,
    val date: Long,
    val fee: String,
    val amount: String,
    val isPending: Boolean,
    val direction: String,
    val isFailed: Boolean
)

data class MainUiState(
    val networkName: String = "",
    val address: String = "N/A",
    val balance: String = "",
    val balanceUnspendable: String = "",
    val state: String = "Not synced",
    val lastBlock: String = "N/A",
    val lastBlockDate: String = "",
    val transactions: List<TransactionUiModel> = emptyList(),

    val addressTo: String = "",
    val amountTo: String = "",
    val notesTo: String = "",
    val isAddressToValid: Boolean = true,
    val isLoadingSending: Boolean = false,
    val errorSending: String? = null,
    val maxAmountTo: Double = 0.0
)