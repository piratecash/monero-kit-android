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
import com.m2049r.xmrwallet.model.Wallet
import com.m2049r.xmrwallet.model.Wallet.ConnectionStatus
import com.m2049r.xmrwallet.model.WalletListener
import com.m2049r.xmrwallet.model.WalletManager
import com.m2049r.xmrwallet.util.Helper
import timber.log.Timber

class WalletService(private val appContext: Context) {
    private var listener: MyWalletListener? = null

    private inner class MyWalletListener : WalletListener {
        var updated: Boolean = true

        fun start() {
            Timber.d("MyWalletListener.start()")
            val wallet: Wallet? = wallet
            checkNotNull(wallet) { "No wallet!" }
            wallet.setListener(this)
            wallet.startRefresh()
        }

        fun stop() {
            Timber.d("MyWalletListener.stop()")
            val wallet: Wallet? = wallet
            checkNotNull(wallet) { "No wallet!" }
            wallet.pauseRefresh()
            wallet.setListener(null)
        }

        // WalletListener callbacks
        override fun moneySpent(txId: String?, amount: Long) {
            Timber.d("moneySpent() %d @ %s", amount, txId)
        }

        override fun moneyReceived(txId: String?, amount: Long) {
            Timber.d("moneyReceived() %d @ %s", amount, txId)
        }

        override fun unconfirmedMoneyReceived(txId: String?, amount: Long) {
            Timber.d("unconfirmedMoneyReceived() %d @ %s", amount, txId)
        }

        private var lastBlockTime: Long = 0
        private var lastTxCount = 0

        override fun newBlock(height: Long) {
            val wallet: Wallet? = wallet
            checkNotNull(wallet) { "No wallet!" }
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
            Timber.d("updated()")
            val wallet: Wallet? = wallet
            checkNotNull(wallet) { "No wallet!" }
            updated = true
        }

        override fun refreshed() { // this means it's synced
            Timber.d("refreshed()")
            val wallet: Wallet? = wallet
            checkNotNull(wallet) { "No wallet!" }
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

        fun onWalletStored(success: Boolean)

        fun onTransactionCreated(tag: String?, pendingTransaction: PendingTransaction?)

        fun onTransactionSent(txid: String?)

        fun onSendTransactionFailed(error: String?)

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
        Timber.d("stop()")

        if(saveWallet) {
            storeWallet()
        }

        setObserver(null) // in case it was not reset already
        if (listener != null) {
            listener?.stop()
            val myWallet = wallet
            Timber.d("stop() closing")
            myWallet?.close()
            Timber.d("stop() closed")
            listener = null
        }
        running = false
    }

    fun storeWallet() {
        wallet?.store()?.let {
            observer?.onWalletStored(it)
        }
    }

    fun sweep(txTag: String) {
        val myWallet: Wallet? = wallet
        if (myWallet == null) return
        Timber.d("SWEEP TX for wallet: %s", myWallet.name)
        myWallet.disposePendingTransaction() // remove any old pending tx

        val pendingTransaction = myWallet.createSweepUnmixableTransaction()
        val status = pendingTransaction.getStatus()
        Timber.d("transaction status %s", status)
        if (status != PendingTransaction.Status.Status_Ok) {
            Timber.w(
                "Create Transaction failed: %s",
                pendingTransaction.getErrorString()
            )
        }
        if (observer != null) {
            observer?.onTransactionCreated(txTag, pendingTransaction)
        } else {
            myWallet.disposePendingTransaction()
        }
    }

    fun prepareTransaction(txTag: String, txData: TxData) {
        val myWallet: Wallet? = wallet
        if (myWallet == null) return
        Timber.d("CREATE TX for wallet: %s", myWallet.name)
        myWallet.disposePendingTransaction() // remove any old pending tx

        checkNotNull(txData)
        txData.createPocketChange(myWallet)
        val pendingTransaction = myWallet.createTransaction(txData)
        val status = pendingTransaction.status
        if (status != PendingTransaction.Status.Status_Ok) {
            Timber.w(
                "Create Transaction failed: %s",
                pendingTransaction.getErrorString()
            )
        }
        if (observer != null) {
            observer?.onTransactionCreated(txTag, pendingTransaction)
        } else {
            myWallet.disposePendingTransaction()
        }
    }

    fun sendTransaction(notes: String?) {
        val myWallet: Wallet? = wallet
        if (myWallet == null) return
        Timber.d("SEND TX for wallet: %s", myWallet.name)
        val pendingTransaction = myWallet.pendingTransaction
        requireNotNull(pendingTransaction) { "PendingTransaction is null" }
        if (pendingTransaction.getStatus() != PendingTransaction.Status.Status_Ok) {
            Timber.e("PendingTransaction is %s", pendingTransaction.getStatus())
            val error = pendingTransaction.getErrorString()
            myWallet.disposePendingTransaction() // it's broken anyway
            observer?.onSendTransactionFailed(error)
            return
        }
        val txid =
            pendingTransaction.getFirstTxId() // tx ids vanish after commit()!

        val success = pendingTransaction.commit("", true)
        if (success) {
            myWallet.disposePendingTransaction()
            observer?.onTransactionSent(txid)
            if ((notes != null) && (!notes.isEmpty())) {
                myWallet.setUserNote(txid, notes)
            }
            val rc = myWallet.store()
            Timber.d("wallet stored: %s with rc=%b", myWallet.getName(), rc)
            if (!rc) {
                Timber.w(
                    "Wallet store failed: %s",
                    myWallet.status.errorString
                )
            }
            observer?.onWalletStored(rc)
            listener?.updated = true
        } else {
            val error = pendingTransaction.getErrorString()
            myWallet.disposePendingTransaction()
            observer?.onSendTransactionFailed(error)
            return
        }

    }

    private fun loadWallet(walletName: String?, walletPassword: String?): Wallet? {
        val wallet = openWallet(walletName, walletPassword)
        if (wallet != null) {
            Timber.d("Using daemon %s", WalletManager.getInstance().getDaemonAddress())
            showProgress(55)
            wallet.init(0)
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
