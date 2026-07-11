/*
 * Copyright (c) 2017 m2049r
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.m2049r.xmrwallet.service

import android.content.Context
import androidx.annotation.WorkerThread
import com.m2049r.levin.util.NetCipherHelper
import com.m2049r.xmrwallet.data.TxData
import com.m2049r.xmrwallet.model.PendingTransaction
import com.m2049r.xmrwallet.model.UnsignedTransaction
import com.m2049r.xmrwallet.model.Wallet
import com.m2049r.xmrwallet.model.Wallet.ConnectionStatus
import com.m2049r.xmrwallet.model.WalletListener
import com.m2049r.xmrwallet.model.WalletManager
import com.m2049r.xmrwallet.offline.MoneroRawTransactionError
import com.m2049r.xmrwallet.offline.RawMoneroBroadcastResult
import com.m2049r.xmrwallet.offline.SignedMoneroTransactionEnvelope
import com.m2049r.xmrwallet.offline.SignedRawMoneroTransaction
import com.m2049r.xmrwallet.util.Helper
import java.io.File
import timber.log.Timber

class MoneroWalletService(private val appContext: Context) {
    private var listener: MyWalletListener? = null
    @Volatile
    private var isStopping = false
    @Volatile
    private var isPaused = false

    private inner class MyWalletListener : WalletListener {
        var updated: Boolean = true

        fun start() {
            Timber.d("MyWalletListener.start()")
            val wallet: Wallet? = wallet
            if (wallet == null) {
                Timber.d("MyWalletListener.start() wallet is null")
                return
            }

            wallet.setListener(this)
            wallet.startRefresh()
        }

        fun stop() {
            Timber.d("MyWalletListener.stop()")
            val wallet: Wallet? = wallet
            if(wallet == null) {
                Timber.d("MyWalletListener.stop() wallet is null")
                return
            }
            wallet.pauseRefresh()
            // Don't clear the listener here — the native refresh thread may still be
            // inside fast_refresh() and could callback into a null listener.
            // wallet.close() → closeWallet() joins the refresh thread first,
            // then the JNI layer safely cleans up the listener.
        }

        fun resume() {
            Timber.d("MyWalletListener.resume()")
            val wallet: Wallet? = wallet
            if (wallet == null) {
                Timber.d("MyWalletListener.resume() wallet is null")
                return
            }
            // Only restart refresh — the listener is already attached from start().
            // Do NOT call wallet.setListener(this) here to avoid native listener
            // churn (each setListenerJ leaks the old native listener by design).
            wallet.startRefresh()
        }

        // WalletListener callbacks
        override fun moneySpent(txId: String?, amount: Long) {
            if (isStopping || isPaused) return
            Timber.d("moneySpent() %d @ %s", amount, txId)
        }

        override fun moneyReceived(txId: String?, amount: Long) {
            if (isStopping || isPaused) return
            Timber.d("moneyReceived() %d @ %s", amount, txId)
        }

        override fun unconfirmedMoneyReceived(txId: String?, amount: Long) {
            if (isStopping || isPaused) return
            Timber.d("unconfirmedMoneyReceived() %d @ %s", amount, txId)
        }

        private var lastBlockTime: Long = 0
        private var lastTxCount = 0

        override fun newBlock(height: Long) {
            if (isStopping || isPaused) return
            val wallet: Wallet? = wallet
            if (wallet == null) {
                Timber.d("newBlock() wallet is null")
                return
            }
            // don't flood with an update for every block ...
            if (lastBlockTime < System.currentTimeMillis() - 2000) {
                lastBlockTime = System.currentTimeMillis()
                Timber.d("newBlock() @ %d with observer %s", height, observer)
                if (observer != null) {
                    var fullRefresh = false
                    updateDaemonState(wallet, if (wallet.isSynchronized) height else 0)
                    if (!wallet.isSynchronized) {
                        updated = true
                        // we want to see our transactions as they come in
                        wallet.refreshHistory()
                        val txCount = wallet.getHistory().getCount()
                        if (txCount > lastTxCount) {
                            // update the transaction list only if we have more than before
                            lastTxCount = txCount
                            fullRefresh = true
                        }
                    }
                    observer?.onRefreshed(wallet, fullRefresh)
                }
            }
        }

        override fun updated() {
            if (isStopping || isPaused) return
            Timber.d("updated()")
            val wallet: Wallet? = wallet
            if (wallet == null) {
                Timber.d("updated() wallet is null")
                return
            }
            updated = true
        }

        override fun refreshed() { // this means it's synced
            if (isStopping || isPaused) return
            Timber.d("refreshed()")
            val wallet: Wallet? = wallet
            if (wallet == null) {
                Timber.d("refreshed() wallet is null")
                return
            }
            wallet.setSynchronized()
            if (updated) {
                updateDaemonState(wallet, wallet.getBlockChainHeight())
                wallet.refreshHistory()

                val newUpdateValue = observer?.onRefreshed(wallet, true)?.not()
                updated = newUpdateValue ?: updated
            }
        }
    }

    private var lastDaemonStatusUpdate: Long = 0
    var daemonHeight: Long = 0
        private set
    var connectionStatus: ConnectionStatus = ConnectionStatus.ConnectionStatus_Disconnected
        private set

    private fun updateDaemonState(wallet: Wallet, height: Long) {
        val t = System.currentTimeMillis()
        if (height > 0) { // if we get a height, we are connected
            daemonHeight = height
            connectionStatus = ConnectionStatus.ConnectionStatus_Connected
            lastDaemonStatusUpdate = t
        } else {
            if (t - lastDaemonStatusUpdate > STATUS_UPDATE_INTERVAL) {
                lastDaemonStatusUpdate = t
                // these calls really connect to the daemon - wasting time
                daemonHeight = wallet.getDaemonBlockChainHeight()
                if (daemonHeight > 0) {
                    // if we get a valid height, then obviously we are connected
                    connectionStatus = ConnectionStatus.ConnectionStatus_Connected
                } else {
                    connectionStatus = ConnectionStatus.ConnectionStatus_Disconnected
                }
            }
        }
    }

    private var observer: Observer? = null

    fun setObserver(anObserver: Observer?) {
        observer = anObserver
        Timber.d("setObserver %s", observer)
    }

    interface Observer {
        /**
         * @return true if handled successfully
         */
        fun onRefreshed(wallet: Wallet?, full: Boolean): Boolean

        fun onProgress(text: String?)

        fun onProgress(n: Int)

        fun onWalletStarted(walletStatus: Wallet.Status?)

        fun onWalletOpen(device: Wallet.Device?)
    }

    var progressText: String? = null
    var progressValue: Int = -1

    private fun showProgress(text: String?) {
        progressText = text
        observer?.onProgress(text)
    }

    private fun showProgress(n: Int) {
        progressValue = n
        observer?.onProgress(n)
    }

    val wallet: Wallet?
        get() = WalletManager.getInstance().wallet


    private var errorState = false

    fun start(walletName: String?, walletPassword: String?): Wallet.Status? {
        isStopping = false
        isPaused = false
        running = true
        Timber.d("start()")

        showProgress(10)
        if (listener == null) {
            Timber.d("start() loadWallet")
            val aWallet = loadWallet(walletName, walletPassword)
            if (aWallet == null) return null
            val walletStatus = aWallet.getFullStatus()
            if (!walletStatus.isOk) {
                aWallet.close()
                return walletStatus
            }
            listener = MyWalletListener()
            listener?.start()
            showProgress(100)
        }
        //        showProgress(getString(R.string.status_wallet_connecting));
        showProgress(101)
        // if we try to refresh the history here we get occasional segfaults!
        // doesnt matter since we update as soon as we get a new block anyway
        Timber.d("start() done")

        val walletStatus = wallet?.getFullStatus()

        observer?.onWalletStarted(walletStatus)
        if ((walletStatus == null) || !walletStatus.isOk()) {
            errorState = true
            stop()
        }
        return walletStatus
    }

    /***
     * must be called from worker thread to avoid ANR
     */
    @WorkerThread
    fun stop(saveWallet: Boolean = true) {
        isStopping = true
        Timber.d("stop()")

        setObserver(null) // in case it was not reset already
        if (listener != null) {
            listener?.stop()
            val myWallet = wallet
            Timber.d("stop() closing")
            // JNI closeJ stores separately (with SIGSEGV protection), then
            // closes without store — which joins the refresh thread via stop()/deinit().
            myWallet?.close(saveWallet)
            Timber.d("stop() closed")
            listener = null
        }
        running = false
        isStopping = false
        isPaused = false
    }

    /**
     * Abandons ownership of a wallet whose native state faulted during a guarded
     * `storeSafe()` (status 2). The native handle is already zeroed, so this makes
     * no native calls on the dead wallet — it only clears Java-side references so
     * a subsequent `start()` opens a fresh wallet.
     */
    fun abandonFaultedWallet() {
        val w = WalletManager.getInstance().wallet
        WalletManager.getInstance().clearManagedWalletIfCurrent(w)
        listener = null
        running = false
        isStopping = false
        isPaused = false
        setObserver(null)
    }

    @WorkerThread
    fun pause() {
        Timber.d("pause()")
        isPaused = true
        setObserver(null)
        listener?.stop()
    }

    @WorkerThread
    fun resume(anObserver: Observer): Boolean {
        Timber.d("resume()")
        if (listener == null) {
            Timber.d("resume() listener is null — wallet not open")
            return false
        }
        isPaused = false
        setObserver(anObserver)
        listener?.resume()
        Timber.d("resume() done")
        return true
    }

    fun sweep() {
        val myWallet = wallet ?: throw IllegalStateException("Wallet not initialized")
        Timber.d("SWEEP TX for wallet: %s", myWallet.name)
        myWallet.disposePendingTransaction()

        val pendingTransaction = myWallet.createSweepUnmixableTransaction()
        if (pendingTransaction.getStatus() != PendingTransaction.Status.Status_Ok) {
            val error = pendingTransaction.getErrorString()
            myWallet.disposePendingTransaction()
            throw IllegalStateException("Create sweep transaction failed: $error")
        }
    }

    fun prepareTransaction(txData: TxData) {
        val myWallet = wallet ?: throw IllegalStateException("Wallet not initialized")
        Timber.d("CREATE TX for wallet: %s", myWallet.name)
        myWallet.createCheckedTransaction(txData) { error ->
            IllegalStateException("Create transaction failed: $error")
        }
    }

    fun createSignedRawTransaction(txData: TxData): SignedRawMoneroTransaction {
        val myWallet = wallet ?: throw MoneroRawTransactionError.WalletNotInitialized()
        Timber.d("CREATE SIGNED RAW TX for wallet: %s", myWallet.name)
        var unsignedFile: File? = null
        var signedFile: File? = null

        return try {
            val pendingTransaction = myWallet.createCheckedTransaction(txData) { error ->
                MoneroRawTransactionError.CreateFailed(error)
            }

            val txId = pendingTransaction.getFirstTxIdJ()
                ?: throw MoneroRawTransactionError.CreateFailed("Transaction has no txid")
            val fee = pendingTransaction.getFee()
            val txCount = pendingTransaction.getTxCount()
            unsignedFile = File.createTempFile("pcash-xmr-unsigned-", ".tx", appContext.cacheDir)
            signedFile = File.createTempFile("pcash-xmr-signed-", ".tx", appContext.cacheDir)

            saveUnsignedTransaction(pendingTransaction, unsignedFile)
            signUnsignedTransaction(myWallet, unsignedFile, signedFile)

            val signedTransactionFile = signedFile.readBytes()
            if (signedTransactionFile.isEmpty()) {
                throw MoneroRawTransactionError.SaveFailed("Signed transaction file is empty")
            }
            val raw = SignedMoneroTransactionEnvelope.encode(txId, signedTransactionFile)
            SignedRawMoneroTransaction(raw, txId, fee, txCount)
        } finally {
            myWallet.disposePendingTransaction()
            unsignedFile?.delete()
            signedFile?.delete()
        }
    }

    private fun saveUnsignedTransaction(pendingTransaction: PendingTransaction, unsignedFile: File) {
        if (!pendingTransaction.commit(unsignedFile.absolutePath, true)) {
            throw MoneroRawTransactionError.SaveFailed(pendingTransaction.getErrorString())
        }
    }

    private fun signUnsignedTransaction(wallet: Wallet, unsignedFile: File, signedFile: File) {
        var unsignedTransaction: UnsignedTransaction? = null
        try {
            unsignedTransaction = wallet.loadUnsignedTx(unsignedFile.absolutePath)
                ?: throw MoneroRawTransactionError.SignFailed("Load unsigned transaction failed")

            if (unsignedTransaction.getStatus() != UnsignedTransaction.Status.Status_Ok) {
                throw MoneroRawTransactionError.SignFailed(unsignedTransaction.getErrorString())
            }
            if (!unsignedTransaction.sign(signedFile.absolutePath)) {
                throw MoneroRawTransactionError.SignFailed(unsignedTransaction.getErrorString())
            }
        } finally {
            unsignedTransaction?.dispose()
        }
    }

    suspend fun submitSignedRawTransaction(raw: ByteArray): RawMoneroBroadcastResult {
        val decoded = SignedMoneroTransactionEnvelope.decode(raw)
        val myWallet = wallet ?: throw MoneroRawTransactionError.WalletNotInitialized()

        if (DaemonTransactionChecker.transactionExistsOnChain(decoded.txId)) {
            return RawMoneroBroadcastResult.AlreadyKnown(decoded.txId)
        }

        var tempFile: File? = null
        return try {
            tempFile = File.createTempFile("pcash-xmr-submit-", ".tx", appContext.cacheDir)
            tempFile.writeBytes(decoded.signedTransactionFile)

            if (!myWallet.submitTransaction(tempFile.absolutePath)) {
                throw MoneroRawTransactionError.SubmitFailed(myWallet.status.errorString)
            }

            listener?.updated = true
            RawMoneroBroadcastResult.Submitted(decoded.txId)
        } finally {
            tempFile?.delete()
        }
    }

    private fun Wallet.createCheckedTransaction(
        txData: TxData,
        createError: (String) -> Exception,
    ): PendingTransaction {
        disposePendingTransaction()
        txData.createPocketChange(this)
        val pendingTransaction = createTransaction(txData)
        if (pendingTransaction.getStatus() != PendingTransaction.Status.Status_Ok) {
            val error = pendingTransaction.getErrorString()
            disposePendingTransaction()
            throw createError(error)
        }
        return pendingTransaction
    }

    fun sendTransaction(notes: String?): String {
        val myWallet = wallet ?: throw IllegalStateException("Wallet not initialized")
        Timber.d("SEND TX for wallet: %s", myWallet.name)
        val pendingTransaction = requireNotNull(myWallet.pendingTransaction) {
            "PendingTransaction is null"
        }
        if (pendingTransaction.getStatus() != PendingTransaction.Status.Status_Ok) {
            val error = pendingTransaction.getErrorString()
            myWallet.disposePendingTransaction()
            throw IllegalStateException("PendingTransaction failed: $error")
        }
        val txid = pendingTransaction.getFirstTxId()
        if (txid == null) {
            myWallet.disposePendingTransaction()
            throw IllegalStateException("Transaction has no txid")
        }

        val success = pendingTransaction.commit("", true)
        if (!success) {
            val error = pendingTransaction.getErrorString()
            myWallet.disposePendingTransaction()
            throw IllegalStateException("Transaction commit failed: $error")
        }

        myWallet.disposePendingTransaction()
        if (!notes.isNullOrEmpty()) {
            myWallet.setUserNote(txid, notes)
        }
        val rc = myWallet.store()
        Timber.d("wallet stored: %s with rc=%b", myWallet.getName(), rc)
        if (!rc) {
            Timber.w("Wallet store failed: %s", myWallet.status.errorString)
        }
        listener?.updated = true
        return txid
    }

    private fun loadWallet(walletName: String?, walletPassword: String?): Wallet? {
        val wallet = openWallet(walletName, walletPassword)
        if (wallet != null) {
            Timber.d("Using daemon %s", WalletManager.getInstance().getDaemonAddress())
            showProgress(55)
            if (!wallet.init(0)) {
                Timber.e("wallet.init failed")
                wallet.close()
                return null
            }
            wallet.setProxy(NetCipherHelper.getProxy())
            showProgress(90)
        }
        return wallet
    }

    private fun openWallet(walletName: String?, walletPassword: String?): Wallet? {
        val path = Helper.getWalletFile(appContext, walletName).absolutePath
        showProgress(20)
        var wallet: Wallet? = null
        val walletMgr = WalletManager.getInstance()
        Timber.d("WalletManager network=%s", walletMgr.getNetworkType().name)
        showProgress(30)
        if (walletMgr.walletExists(path)) {
            Timber.d("open wallet %s", path)
            val device =
                WalletManager.getInstance().queryWalletDevice(path + ".keys", walletPassword)
            Timber.d("device is %s", device.toString())
            observer?.onWalletOpen(device)
            wallet = walletMgr.openWallet(path, walletPassword)
            showProgress(60)
            Timber.d("wallet opened")
            val walletStatus = wallet.getStatus()
            if (!walletStatus.isOk()) {
                Timber.d("wallet status is %s", walletStatus)
                WalletManager.getInstance().close(wallet) // TODO close() failed?
                if (walletStatus.status == Wallet.StatusEnum.Status_Critical) {
                    throw WalletCorruptedException(walletStatus.errorString)
                }
                wallet = null
                // TODO what do we do with the progress??
                // TODO tell the activity this failed
                // this crashes in MyWalletListener(Wallet aWallet) as wallet == null
            }
        }
        return wallet
    }

    companion object {
        var running: Boolean = false

        private const val STATUS_UPDATE_INTERVAL: Long = 120000 // 120s (blocktime)
    }
}
