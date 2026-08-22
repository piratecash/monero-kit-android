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

/** Outcome of opening a wallet from local storage; carries no daemon state. */
sealed interface LocalOpenResult {
    data object Opened : LocalOpenResult
    data class Failed(val status: Wallet.Status?, val error: String?) : LocalOpenResult
}

/** Outcome of attaching a daemon to an already open wallet. */
sealed interface DaemonConnectResult {
    data object Connected : DaemonConnectResult
    data object NoWallet : DaemonConnectResult
    data class Failed(val status: Wallet.Status?) : DaemonConnectResult
}

class MoneroWalletService(private val appContext: Context) {
    private var listener: MyWalletListener? = null

    /** The wallet this service opened, independent of whichever wallet is process-global. */
    @Volatile
    private var ownedWallet: Wallet? = null

    @Volatile
    private var isStopping = false
    @Volatile
    private var isPaused = false

    /** Native `startRefresh()` requires a wallet whose `init()` succeeded (see wallet2_api.h). */
    @Volatile
    private var daemonInitialized = false
    private var failedWalletStatus: Wallet.Status? = null
    private val controlledRefreshGate = ControlledRefreshSessionGate()

    private inner class MyWalletListener(private val boundWallet: Wallet) : WalletListener {
        var updated: Boolean = true

        /** Null once the service stopped owning [boundWallet]; a late native callback must not touch it. */
        private val liveWallet: Wallet?
            get() = boundWallet.takeIf { it === ownedWallet }

        fun start() {
            Timber.d("MyWalletListener.start()")
            boundWallet.setListener(this)
            boundWallet.startRefresh()
        }

        fun attachPaused() {
            boundWallet.setListener(this)
        }

        fun stop() {
            Timber.d("MyWalletListener.stop()")
            liveWallet?.pauseRefresh()
            // Don't clear the listener here — the native refresh thread may still be
            // inside fast_refresh() and could callback into a null listener.
            // wallet.close() → closeWallet() joins the refresh thread first,
            // then the JNI layer safely cleans up the listener.
        }

        fun resume() {
            Timber.d("MyWalletListener.resume()")
            // Only restart refresh — the listener is already attached from start().
            // Do NOT call wallet.setListener(this) here to avoid native listener
            // churn (each setListenerJ leaks the old native listener by design).
            liveWallet?.startRefresh()
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
            val wallet = liveWallet ?: return
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
            if (liveWallet == null) return
            updated = true
        }

        override fun refreshed() { // this means it's synced
            if (isStopping || isPaused) return
            Timber.d("refreshed()")
            val wallet = liveWallet ?: return
            if (shouldMarkWalletSynchronizedAfterRefresh(wallet.getStatus().status)) {
                wallet.setSynchronized()
            }
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

    /** The wallet this service opened — never the process-global one, which may be somebody else's. */
    val wallet: Wallet?
        get() = ownedWallet


    private var errorState = false

    /** Publishes the listener only once it is attached, so no caller can observe an unattached one. */
    private fun attachListener(wallet: Wallet, startRefresh: Boolean) {
        val newListener = MyWalletListener(wallet)
        if (startRefresh) newListener.start() else newListener.attachPaused()
        listener = newListener
    }

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
                // A refused close leaves the wallet native-open, so keep owning it.
                closeOwnedWallet(aWallet)
                return walletStatus
            }
            attachListener(aWallet, startRefresh = true)
            showProgress(100)
        }
        //        showProgress(getString(R.string.status_wallet_connecting));
        showProgress(101)
        // if we try to refresh the history here we get occasional segfaults!
        // doesnt matter since we update as soon as we get a new block anyway
        Timber.d("start() done")

        val walletStatus = ownedWallet?.getFullStatus()

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
            attachListener(aWallet, startRefresh = false)
            val status = aWallet.getFullStatus()
            observer?.onWalletStarted(status)
            return status
        }
        return ownedWallet?.getFullStatus()
    }

    /**
     * Opens the wallet from local storage only — no daemon init, no refresh — so the stored
     * balance and history stay readable offline. Resume with [connectDaemon] + [resume].
     */
    fun startOffline(walletName: String?, walletPassword: String?): LocalOpenResult {
        // Only an already-offline session of the same wallet is a no-op. An online one may hold a
        // different wallet and can resume refreshing at any time — including while merely paused,
        // which is what the daemon check separates out — so the caller has to stop it first.
        if (listener != null) {
            val open = ownedWallet
            return if (isPaused && !daemonInitialized && open != null && open.name == walletName) {
                LocalOpenResult.Opened
            } else {
                LocalOpenResult.Failed(null, "another wallet session is already open")
            }
        }

        isStopping = false
        failedWalletStatus = null
        daemonInitialized = false
        // stop() leaves the last online session's readings behind; offline they are a lie.
        connectionStatus = ConnectionStatus.ConnectionStatus_Disconnected
        daemonHeight = 0
        lastDaemonStatusUpdate = 0
        // openWallet(), unlike loadWallet(), performs no daemon init and no proxy setup.
        // closeOnFailure stays false so a corrupted wallet is reported, not thrown; closing the
        // retained wallet here is still ours to do, or a retry would overwrite ownedWallet and
        // leave the native one unreachable.
        val aWallet = openWallet(walletName, walletPassword, closeOnFailure = false)
        if (aWallet == null) {
            ownedWallet?.let { closeOwnedWallet(it) }
            return LocalOpenResult.Failed(failedWalletStatus, failedWalletStatus?.errorString)
        }

        isPaused = true
        running = true
        attachListener(aWallet, startRefresh = false)
        return LocalOpenResult.Opened
    }

    /**
     * Attaches the daemon to a wallet opened by [startOffline]; on failure it stays open and paused.
     * Requires a daemon set through `WalletManager.setDaemon()` and an initialized [NetCipherHelper]
     * — the proxy is mandatory, so a missing one throws instead of connecting in the clear.
     */
    @WorkerThread
    fun connectDaemon(): DaemonConnectResult {
        // resume() refreshes the wallet this service attached its listener to, so anything else —
        // including a newer process-global wallet opened by someone else — must not report Connected.
        val myWallet = ownedWallet
        if (myWallet == null || listener == null || myWallet !== WalletManager.getInstance().wallet) {
            return DaemonConnectResult.NoWallet
        }

        initDaemon(myWallet)?.let { return DaemonConnectResult.Failed(it) }

        // init() only stores the address; getFullStatus() is what actually probes the daemon.
        val status = myWallet.getFullStatus()
        return if (status.isOk) DaemonConnectResult.Connected else failed(status)
    }

    private fun failed(status: Wallet.Status): DaemonConnectResult.Failed {
        failedWalletStatus = status
        return DaemonConnectResult.Failed(status)
    }

    /**
     * Closes [wallet], giving up ownership of it only once the native close succeeded.
     *
     * [WalletManager.close] re-manages the wallet whenever the native close fails. For a wallet
     * that is no longer the managed one that would evict whichever wallet someone else opened
     * meanwhile, so such a wallet uses identity-safe [WalletManager.closeJ] instead.
     */
    @WorkerThread
    private fun closeOwnedWallet(wallet: Wallet, saveWallet: Boolean = false): Boolean {
        val manager = WalletManager.getInstance()
        val closed = if (wallet === manager.wallet) {
            wallet.close(saveWallet)
        } else {
            manager.closeJ(wallet, saveWallet)
        }
        if (closed && wallet === ownedWallet) {
            ownedWallet = null
        }
        return closed
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
        // Never a foreign wallet while we hold our own: closing it would drop its unsaved state
        // and leak ours. Owning none, stop() keeps its "close whatever is open" meaning, which
        // callers rely on to free a wallet somebody else opened before opening the next one.
        val myWallet = ownedWallet ?: WalletManager.getInstance().wallet
        val closed = if (myWallet != null) {
            Timber.d("stop() closing")
            // JNI closeJ stores separately (with SIGSEGV protection), then
            // closes without store — which joins the refresh thread via stop()/deinit().
            val result = closeOwnedWallet(myWallet, saveWallet)
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
        val w = ownedWallet
        WalletManager.getInstance().clearManagedWalletIfCurrent(w)
        listener = null
        ownedWallet = null
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
        if (!canRefresh()) return false
        isPaused = false
        setObserver(anObserver)
        listener?.resume()
        Timber.d("resume() done")
        return true
    }

    /** A wallet opened by [startOffline] has no daemon yet — refreshing it is native UB. */
    private fun canRefresh(): Boolean = when {
        listener == null -> {
            Timber.d("resume() wallet not open")
            false
        }

        !daemonInitialized -> {
            Timber.d("resume() daemon not initialized — connectDaemon() first")
            false
        }

        else -> true
    }

    /** Resumes a paused controlled session and guarantees one refreshed callback. */
    @WorkerThread
    fun resumeAfterControlledRefresh(anObserver: Observer): Boolean {
        if (!canRefresh()) return false
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
            if (initDaemon(wallet) != null) {
                Timber.e("wallet.init failed")
                if (closeOnInitFailure) {
                    closeOwnedWallet(wallet)
                }
                // Keep an owned paused wallet available for its caller to abort, but
                // never let later setup replace the failed init status.
                return null
            }
            showProgress(90)
        }
        return wallet
    }

    /** Attaches the daemon and its proxy; returns the failing status, or null on success. */
    private fun initDaemon(wallet: Wallet): Wallet.Status? {
        daemonInitialized = false
        if (!wallet.init(0)) {
            val status = wallet.getFullStatus()
            failedWalletStatus = status
            return status
        }
        wallet.setProxy(NetCipherHelper.getProxy())
        daemonInitialized = true
        return null
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
            // Ownership starts here, not at attachListener(): a wallet retained after a failed
            // open or init is still ours to close.
            ownedWallet = wallet
            showProgress(60)
            Timber.d("wallet opened")
            val walletStatus = wallet.getStatus()
            if (!walletStatus.isOk()) {
                Timber.d("wallet status is %s", walletStatus)
                failedWalletStatus = walletStatus
                if (closeOnFailure) {
                    closeOwnedWallet(wallet)
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

internal fun shouldMarkWalletSynchronizedAfterRefresh(status: Wallet.StatusEnum): Boolean =
    status == Wallet.StatusEnum.Status_Ok

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
