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
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import com.m2049r.levin.util.NetCipherHelper
import com.m2049r.xmrwallet.data.TxData
import com.m2049r.xmrwallet.model.PendingTransaction
import com.m2049r.xmrwallet.model.Wallet
import com.m2049r.xmrwallet.model.Wallet.ConnectionStatus
import com.m2049r.xmrwallet.model.WalletListener
import com.m2049r.xmrwallet.model.WalletManager
import com.m2049r.xmrwallet.offline.MoneroRawTransactionError
import com.m2049r.xmrwallet.offline.RawMoneroBroadcastResult
import com.m2049r.xmrwallet.offline.SignedMoneroTransaction
import com.m2049r.xmrwallet.offline.SignedMoneroTransactionEnvelope
import com.m2049r.xmrwallet.offline.SignedRawMoneroTransaction
import com.m2049r.xmrwallet.util.Helper
import timber.log.Timber

class MoneroWalletService(private val appContext: Context) {
    private var listener: MyWalletListener? = null
    @Volatile
    private var isStopping = false
    @Volatile
    private var isPaused = false
    private var failedWalletStatus: Wallet.Status? = null
    private val controlledRefreshGate = ControlledRefreshSessionGate()

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

        fun attachPaused() {
            val wallet = wallet ?: return
            wallet.setListener(this)
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
            if (isStopping) return
            if (isPaused) {
                controlledRefreshGate.onPausedNewBlock(height)
                return
            }
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
            val forcedNotification = controlledRefreshGate.consumeRefreshedNotification()
            if (updated || forcedNotification) {
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
        failedWalletStatus = null
        running = true
        Timber.d("start()")

        showProgress(10)
        if (listener == null) {
            Timber.d("start() loadWallet")
            val aWallet = loadWallet(
                walletName,
                walletPassword,
                closeOnInitFailure = true,
                closeOnOpenFailure = true,
            )
            if (aWallet == null) return failedWalletStatus
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

    /** Opens a hardware wallet and attaches its listener without starting refresh. */
    fun startPaused(walletName: String?, walletPassword: String?): Wallet.Status? {
        isStopping = false
        isPaused = true
        failedWalletStatus = null
        running = true
        if (listener == null) {
            val aWallet = loadWallet(
                walletName,
                walletPassword,
                closeOnInitFailure = false,
                closeOnOpenFailure = false,
            )
                ?: return failedWalletStatus
            listener = MyWalletListener()
            listener?.attachPaused()
            val status = aWallet.getFullStatus()
            observer?.onWalletStarted(status)
            return status
        }
        return wallet?.getFullStatus()
    }

    /***
     * must be called from worker thread to avoid ANR
     */
    @WorkerThread
    fun stop(saveWallet: Boolean = true): Boolean {
        isStopping = true
        Timber.d("stop()")

        controlledRefreshGate.stop()
        setObserver(null) // in case it was not reset already
        listener?.stop()
        val myWallet = wallet
        val closed = if (myWallet != null) {
            Timber.d("stop() closing")
            // JNI closeJ stores separately (with SIGSEGV protection), then
            // closes without store — which joins the refresh thread via stop()/deinit().
            val result = myWallet.close(saveWallet)
            Timber.d("stop() closed=%b", result)
            result
        } else {
            true
        }
        if (closed) {
            listener = null
        }
        running = !closed
        isStopping = false
        isPaused = !closed
        return closed
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
        controlledRefreshGate.cancel()
        setObserver(null)
        listener?.stop()
    }

    fun setControlledRefreshProgressObserver(observer: ((Long) -> Unit)?) {
        controlledRefreshGate.setProgressObserver(observer)
    }

    fun clearControlledRefreshProgressObserver() {
        controlledRefreshGate.clearProgressObserver()
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

    /** Resumes a paused controlled session and guarantees one refreshed callback. */
    @WorkerThread
    fun resumeAfterControlledRefresh(anObserver: Observer): Boolean {
        if (listener == null) return false
        controlledRefreshGate.resumeAfterControlledRefresh()
        isPaused = false
        setObserver(anObserver)
        listener?.resume()
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

        return try {
            val pendingTransaction = myWallet.createCheckedTransaction(txData) { error ->
                MoneroRawTransactionError.CreateFailed(error)
            }
            val transactions = pendingTransaction.toSignedTransactions()

            SignedRawMoneroTransaction(
                raw = SignedMoneroTransactionEnvelope.encode(transactions),
                txIds = transactions.map { it.txId },
                fee = pendingTransaction.getFee(),
            )
        } finally {
            myWallet.disposePendingTransaction()
        }
    }

    private fun PendingTransaction.toSignedTransactions(): List<SignedMoneroTransaction> {
        val txIds = getTxIdsJ()?.toList().orEmpty()
        val rawHex = getTxRawHexJ()?.toList().orEmpty()
        if (txIds.isEmpty() || txIds.size != rawHex.size) {
            throw MoneroRawTransactionError.CreateFailed("Transaction has no serialized data")
        }
        return txIds.mapIndexed { index, txId ->
            SignedMoneroTransaction(txId, Helper.hexToBytes(rawHex[index]))
        }
    }

    /**
     * Relays the transactions of an offline-signed envelope through the daemon. No wallet key
     * takes part, so an envelope signed on another device broadcasts here just as well.
     *
     * The local wallet learns about the spend on its next refresh rather than from this call.
     */
    suspend fun submitSignedRawTransaction(raw: ByteArray): RawMoneroBroadcastResult =
        broadcastEnvelope(
            raw = raw,
            knownTransactions = { DaemonTransactionChecker.knownTransactions(it) },
            send = { DaemonTransactionSender.sendRawTransaction(it) },
        ).also { if (it is RawMoneroBroadcastResult.Submitted) listener?.updated = true }

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

    private fun loadWallet(
        walletName: String?,
        walletPassword: String?,
        closeOnInitFailure: Boolean,
        closeOnOpenFailure: Boolean,
    ): Wallet? {
        val wallet = openWallet(walletName, walletPassword, closeOnOpenFailure)
        if (wallet != null) {
            Timber.d("Using daemon %s", WalletManager.getInstance().getDaemonAddress())
            showProgress(55)
            if (!wallet.init(0)) {
                Timber.e("wallet.init failed")
                failedWalletStatus = wallet.getFullStatus()
                if (closeOnInitFailure) {
                    wallet.close()
                }
                // Keep an owned paused wallet available for its caller to abort, but
                // never let later setup replace the failed init status.
                return null
            }
            wallet.setProxy(NetCipherHelper.getProxy())
            showProgress(90)
        }
        return wallet
    }

    private fun openWallet(
        walletName: String?,
        walletPassword: String?,
        closeOnFailure: Boolean,
    ): Wallet? {
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
                failedWalletStatus = walletStatus
                if (closeOnFailure) {
                    WalletManager.getInstance().close(wallet) // TODO close() failed?
                }
                if (closeOnFailure && walletStatus.status == Wallet.StatusEnum.Status_Critical) {
                    throw WalletCorruptedException(walletStatus.errorString)
                }
                // A paused caller owns this failed wallet and must receive the
                // status captured before loadWallet() can call init().
                return null
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

        /**
         * Sends every transaction the daemon does not know yet, so retrying an envelope whose
         * earlier transactions already landed still delivers the remaining ones.
         */
        @VisibleForTesting
        internal suspend fun broadcastEnvelope(
            raw: ByteArray,
            knownTransactions: suspend (List<String>) -> Set<String>,
            send: suspend (ByteArray) -> Unit,
        ): RawMoneroBroadcastResult {
            val transactions = SignedMoneroTransactionEnvelope.decode(raw)
            val txId = transactions.first().txId
            val known = knownTransactions(transactions.map { it.txId })
            val unsent = transactions.filterNot { it.txId in known }

            if (unsent.isEmpty()) return RawMoneroBroadcastResult.AlreadyKnown(txId)

            unsent.forEach { send(it.blob) }
            return RawMoneroBroadcastResult.Submitted(txId)
        }
    }
}

/**
 * Listener-side state for a scoped hardware refresh.  It deliberately owns no
 * wallet state: while paused, blocks may only reach this session's progress UI;
 * normal daemon/history/readiness callbacks stay in the regular listener.
 */
internal class ControlledRefreshSessionGate(
    private val now: () -> Long = System::currentTimeMillis,
    private val progressIntervalMs: Long = 500,
) {
    private var armed = false
    private var progressObserver: ((Long) -> Unit)? = null
    private var lastProgressTime: Long = 0

    fun setProgressObserver(observer: ((Long) -> Unit)?) {
        progressObserver = observer
        lastProgressTime = 0
    }

    fun onPausedNewBlock(height: Long) {
        val observer = progressObserver ?: return
        val time = now()
        if (time - lastProgressTime >= progressIntervalMs) {
            lastProgressTime = time
            observer(height)
        }
    }

    /** Re-arms exactly one regular refreshed notification after controlled completion. */
    fun resumeAfterControlledRefresh() {
        armed = true
        clearProgressObserver()
    }

    fun consumeRefreshedNotification(): Boolean = armed.also { armed = false }

    fun clearProgressObserver() {
        progressObserver = null
        lastProgressTime = 0
    }

    fun complete() = clearProgressObserver()
    fun cancel() = clearProgressObserver()
    fun fail() = clearProgressObserver()
    fun stop() = clearProgressObserver()
}
