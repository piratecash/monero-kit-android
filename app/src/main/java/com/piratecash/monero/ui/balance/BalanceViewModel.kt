package com.piratecash.monero.ui.balance

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m2049r.levin.util.NetCipherHelper
import com.m2049r.levin.util.NetCipherHelper.OnStatusChangedListener
import com.m2049r.xmrwallet.data.DefaultNodes
import com.m2049r.xmrwallet.data.NodeInfo
import com.m2049r.xmrwallet.model.NetworkType
import com.m2049r.xmrwallet.model.PendingTransaction
import com.m2049r.xmrwallet.model.Wallet
import com.m2049r.xmrwallet.model.WalletManager
import com.m2049r.xmrwallet.service.WalletService
import com.m2049r.xmrwallet.util.Helper
import com.m2049r.xmrwallet.util.KeyStoreHelper
import com.m2049r.xmrwallet.util.NodePinger
import com.m2049r.xmrwallet.util.RestoreHeight
import com.piratecash.monero.BuildConfig
import com.piratecash.monero.MyApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Collections

class BalanceViewModel : ViewModel(), WalletService.Observer {

    private companion object {
        const val WALLET_NAME = "test"
        const val TAG = "BalanceViewModel"
    }

    val uiState = mutableStateOf<BalanceUiState>(BalanceUiState())

    private lateinit var walletService: WalletService

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

    fun onDebugClick() {

    }

    fun onStatusClick() {

    }

    fun initWallet() {
        NetCipherHelper.createInstance(MyApplication.Companion.instance)
        NetCipherHelper.getInstance().createClearnetClient()

        if (!isWalletFilesExist(MyApplication.Companion.instance, WALLET_NAME)) {
            createWallet()
        }

        walletService = WalletService(MyApplication.Companion.instance)
        walletService.setObserver(this)
        walletService.create()

//        walletService.stop() - must be called in the end

        /*        if (isWalletFilesExist(MyApplication.instance, WALLET_NAME)) {
                    openWallet()
                } else {
                    createWallet()
                }
                wallet.setListener(this)*/
//        updateUIState()
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
        /*viewModelScope.launch(Dispatchers.IO) {
//            val fastestNode = NodePinger.findFirstRespondingNodeAsync(getNodes())
//            if (fastestNode == null) return@launch
            val fastestNode = NodeInfo.fromString(DefaultNodes.MONERUJO.getUri())
            WalletManager.getInstance().setDaemon(fastestNode)
            wallet.init(0)
            wallet.startRefresh()
        }*/
    }

    fun onClearClick() {
        viewModelScope.launch {
            walletService.stop()
            uiState.value = uiState.value.copy(
                state = "Not synced"
            )
//        wallet.pauseRefresh()
            removeWalletRelatedFiles(MyApplication.Companion.instance, WALLET_NAME)
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
        val crazyPass: String? = KeyStoreHelper.getCrazyPass(MyApplication.Companion.instance, "")
        val seed = BuildConfig.WORDS
        val restoreHeight = getHeight(BuildConfig.RESTORE_HEIGHT)
        WalletManager.getInstance()
            .recoveryWallet(getWalletFullPath(), crazyPass, seed, "", restoreHeight)
    }
    /*
        private fun openWallet() {
            val walletPassword = Helper.getWalletPassword(MyApplication.instance, WALLET_NAME, "")
            wallet = WalletManager.getInstance()
                .openWallet(getWalletFullPath().absolutePath, walletPassword)
        }*/


    /*
        private fun updateUIState(height: Long = 0) {
            uiState.value = uiState.value.copy(
                networkName = wallet.networkType.name,
                state = getConnectionStatus(),
                balance = wallet.unlockedBalance.toString(),
                balanceUnspendable = wallet.balance.toString(),
                lastBlock = height.toString(),
            )
        }
    */

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
        getFileName(context, walletName) == null

    private fun getFileName(context: Context, walletName: String): File? {
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
        File(Helper.getWalletRoot(MyApplication.Companion.instance), WALLET_NAME)

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

    override fun onRefreshed(
        wallet: Wallet?,
        full: Boolean
    ): Boolean {
        Log.d(TAG, "onRefreshed() called with: blocks = ${WalletManager.getInstance().blockchainHeight}, ${WalletManager.getInstance().blockTarget}, ${WalletManager.getInstance().blockchainTargetHeight}")
        val progress: Double = if (full) {
            1.0
        } else {
            (wallet?.blockChainHeight ?: 0.0).toDouble() / WalletManager.getInstance().blockchainHeight
        }
        val lockedBalance = (wallet?.balance?:0) - (wallet?.unlockedBalance?:0)
        uiState.value = uiState.value.copy(
            balance = wallet?.unlockedBalance?.toString() ?: "0",
            balanceUnspendable = lockedBalance.toString(),
            lastBlock = WalletManager.getInstance().blockchainHeight.toString(),
            state = if (progress == 1.0) "Synced" else "Syncing %.2f".format(progress)
        )
        Log.d(TAG, "onRefreshed() called with: wallet = $wallet, full = $full")
        return true
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
        Log.d(
            TAG,
            "onTransactionCreated() called with: tag = $tag, pendingTransaction = $pendingTransaction"
        )
    }

    override fun onTransactionSent(txid: String?) {
        Log.d(TAG, "onTransactionSent() called with: txid = $txid")
    }

    override fun onSendTransactionFailed(error: String?) {
        Log.d(TAG, "onSendTransactionFailed() called with: error = $error")
    }

    override fun onWalletStarted(walletStatus: Wallet.Status?) {
        Log.d(TAG, "onWalletStarted() called with: walletStatus = $walletStatus")
    }

    override fun onWalletOpen(device: Wallet.Device?) {
        Log.d(TAG, "onWalletOpen() called with: device = $device")
    }
}

data class BalanceUiState(
    val networkName: String = "",
    val address: String = "N/A",
    val balance: String = "",
    val balanceUnspendable: String = "",
    val state: String = "Not synced",
    val lastBlock: String = "N/A",
    val lastBlockDate: String = ""
)