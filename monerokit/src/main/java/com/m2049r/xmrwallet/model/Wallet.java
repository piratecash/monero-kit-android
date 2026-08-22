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

package com.m2049r.xmrwallet.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.m2049r.xmrwallet.data.Subaddress;
import com.m2049r.xmrwallet.data.TxData;
import com.piratecash.monero.signer.ColdKeyImageSyncResult;
import com.piratecash.monero.signer.HardwareKeyImageRefreshResult;
import com.piratecash.monero.signer.HardwareWalletErrorCode;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.Value;
import timber.log.Timber;

public class Wallet {
    final static public long SWEEP_ALL = Long.MAX_VALUE;

    static {
        System.loadLibrary("monerujo");
    }

    static public class Status {
        Status(int status, String errorString) {
            this(status, errorString, 0);
        }

        Status(int status, String errorString, int hardwareWalletErrorCode) {
            this.status = StatusEnum.values()[status];
            this.errorString = errorString;
            this.hardwareWalletErrorCode = hardwareWalletErrorCode;
        }

        final private StatusEnum status;
        final private String errorString;
        final private int hardwareWalletErrorCode;
        @Nullable
        private ConnectionStatus connectionStatus; // optional

        public StatusEnum getStatus() {
            return status;
        }

        public String getErrorString() {
            return errorString;
        }

        @Nullable
        public HardwareWalletErrorCode getHardwareWalletError() {
            return HardwareWalletErrorCode.fromCodeOrNull(hardwareWalletErrorCode);
        }

        public void setConnectionStatus(@Nullable ConnectionStatus connectionStatus) {
            this.connectionStatus = connectionStatus;
        }

        @Nullable
        public ConnectionStatus getConnectionStatus() {
            return connectionStatus;
        }

        public boolean isOk() {
            return (getStatus() == StatusEnum.Status_Ok) && ((getConnectionStatus() == null) || (getConnectionStatus() == ConnectionStatus.ConnectionStatus_Connected));
        }

        @Override
        @NonNull
        public String toString() {
            return "Wallet.Status: " + status + "/" + errorString + "/" +
                    connectionStatus + "/" + getHardwareWalletError();
        }
    }

    private int accountIndex = 0;

    public int getAccountIndex() {
        return accountIndex;
    }

    public void setAccountIndex(int accountIndex) {
        Timber.d("setAccountIndex(%d)", accountIndex);
        this.accountIndex = accountIndex;
        getHistory().setAccountFor(this);
    }

    public String getName() {
        return new File(getPath()).getName();
    }

    private long handle = 0;
    private long listenerHandle = 0;
    private final WalletNativeCallGate nativeCallGate = new WalletNativeCallGate();

    Wallet(long handle) {
        this.handle = handle;
    }

    Wallet(long handle, int accountIndex) {
        this.handle = handle;
        this.accountIndex = accountIndex;
    }

    @RequiredArgsConstructor
    @Getter
    public enum Device {
        Undefined(0, 0),
        Software(50, 200),
        Ledger(5, 20),
        Trezor(5, 20),
        Sidekick(5, 20);
        private final int accountLookahead;
        private final int subaddressLookahead;
    }

    public enum StatusEnum {
        Status_Ok, Status_Error, Status_Critical
    }

    public enum ConnectionStatus {
        ConnectionStatus_Disconnected, ConnectionStatus_Connected, ConnectionStatus_WrongVersion
    }

    public native String getSeed(String offset);

    public native String getSeedLanguage();

    public native void setSeedLanguage(String language);

    public Status getStatus() {
        return statusWithErrorString();
    }

    public Status getFullStatus() {
        Status walletStatus = statusWithErrorString();
        walletStatus.setConnectionStatus(getConnectionStatus());
        return walletStatus;
    }

    private native Status statusWithErrorString();

    public native synchronized boolean setPassword(String password);

    public String getAddress() {
        return getAddress(accountIndex);
    }

    public String getAddress(int accountIndex) {
        return getAddressJ(accountIndex, 0);
    }

    public String getSubaddress(int addressIndex) {
        return getAddressJ(accountIndex, addressIndex);
    }

    public String getSubaddress(int accountIndex, int addressIndex) {
        return getAddressJ(accountIndex, addressIndex);
    }

    private native String getAddressJ(int accountIndex, int addressIndex);

    public void deviceShowAddress(int accountIndex, int addressIndex, String paymentId) {
        if (accountIndex < 0 || addressIndex < 0) {
            throw new IllegalArgumentException("Account and address indices must be non-negative");
        }
        deviceShowAddressJ(accountIndex, addressIndex, paymentId);
    }

    private native void deviceShowAddressJ(int accountIndex, int addressIndex, String paymentId);

    public Subaddress getSubaddressObject(int accountIndex, int subAddressIndex) {
        return new Subaddress(accountIndex, subAddressIndex, getSubaddress(subAddressIndex), getSubaddressLabel(subAddressIndex));
    }

    public Subaddress getSubaddressObject(int subAddressIndex) {
        Subaddress subaddress = getSubaddressObject(accountIndex, subAddressIndex);
        long amount = 0;
        for (TransactionInfo info : getHistory().getAll()) {
            if ((info.addressIndex == subAddressIndex) && (info.direction == TransactionInfo.Direction.Direction_In)) {
                amount += info.amount;
            }
        }
        subaddress.setAmount(amount);
        return subaddress;
    }

    public native String getPath();

    public NetworkType getNetworkType() {
        return NetworkType.fromInteger(nettype());
    }

    public native int nettype();

//TODO virtual void hardForkInfo(uint8_t &version, uint64_t &earliest_height) const = 0;
//TODO virtual bool useForkRules(uint8_t version, int64_t early_blocks) const = 0;

    public native String getIntegratedAddress(String payment_id);

    public native String getSecretViewKey();

    public native String getSecretSpendKey();

    public boolean store() {
        return store("");
    }

    public native synchronized boolean store(String path);

    private native int storeSafeJ();

    public int storeSafe() {
        return storeSafeJ();
    }

    private native int storeWithKeysSafeJ();

    /**
     * Persists the encrypted keys file before the cache.  The status values are
     * identical to {@link #storeSafe()}; only zero commits the operation.
     */
    public int storeWithKeysSafe() {
        return storeWithKeysSafeJ();
    }

    public boolean close(boolean store) {
        return WalletManager.getInstance().close(this, store);
    }

    public boolean close() {
        return close(false);
    }

    boolean closeNative(BooleanSupplier action) {
        return nativeCallGate.close(() -> {
            disposePendingTransaction();
            return action.getAsBoolean();
        });
    }

    public native String getFilename();

    //    virtual std::string keysFilename() const = 0;
    public boolean init(long upper_transaction_size_limit) {
        return initJ(WalletManager.getInstance().getDaemonAddress(), upper_transaction_size_limit, WalletManager.getInstance().getDaemonUsername(), WalletManager.getInstance().getDaemonPassword());
    }

    private native boolean initJ(String daemon_address, long upper_transaction_size_limit, String daemon_username, String daemon_password);

//    virtual bool createWatchOnly(const std::string &path, const std::string &password, const std::string &language) const = 0;
//    virtual void setRefreshFromBlockHeight(uint64_t refresh_from_block_height) = 0;

    public native void setRestoreHeight(long height);

    public native long getRestoreHeight();

    //    virtual void setRecoveringFromSeed(bool recoveringFromSeed) = 0;
//    virtual bool connectToDaemon() = 0;

    public ConnectionStatus getConnectionStatus() {
        int s = getConnectionStatusJ();
        return ConnectionStatus.values()[s];
    }

    private native int getConnectionStatusJ();

//TODO virtual void setTrustedDaemon(bool arg) = 0;
//TODO virtual bool trustedDaemon() const = 0;

    public native boolean setProxy(String address);

    public long getBalance() {
        return getBalance(accountIndex);
    }

    public long getBalance(int accountIndex) {
        return nativeCallGate.read(() -> getBalanceJ(accountIndex));
    }

    private native long getBalanceJ(int accountIndex);

    public long getBalanceAll() {
        return nativeCallGate.read(this::getBalanceAllJ);
    }

    private native long getBalanceAllJ();

    public long getUnlockedBalance() {
        return getUnlockedBalance(accountIndex);
    }

    public long getUnlockedBalanceAll() {
        return nativeCallGate.read(this::getUnlockedBalanceAllJ);
    }

    private native long getUnlockedBalanceAllJ();

    public long getUnlockedBalance(int accountIndex) {
        return nativeCallGate.read(() -> getUnlockedBalanceJ(accountIndex));
    }

    private native long getUnlockedBalanceJ(int accountIndex);

    public native boolean isWatchOnly();

    public native long getBlockChainHeight();

    public native long getApproximateBlockChainHeight();

    public native long getDaemonBlockChainHeight();

    public native long getDaemonBlockChainTargetHeight();

    boolean synced = false;

    public boolean isSynchronized() {
        return synced;
    }

    public void setSynchronized() {
        this.synced = true;
    }

    public static native String getDisplayAmount(long amount);

    public static native long getAmountFromString(String amount);

    public static native long getAmountFromDouble(double amount);

    public static native String generatePaymentId();

    public static native boolean isPaymentIdValid(String payment_id);

    public static boolean isAddressValid(String address) {
        return isAddressValid(address, WalletManager.getInstance().getNetworkType().getValue());
    }

    public static native boolean isAddressValid(String address, int networkType);

    public static native String getPaymentIdFromAddress(String address, int networkType);

    public static native long getMaximumAllowedAmount();

    public native void startRefresh();

    public native void pauseRefresh();

    public native boolean pauseRefreshAndDrain();

    public native boolean refresh();

    public native void refreshAsync();

    public native void rescanBlockchainAsyncJ();

    public void rescanBlockchainAsync() {
        synced = false;
        rescanBlockchainAsyncJ();
    }

    private native void rescanBlockchainAsyncPreserveKeyImagesJ();

    public void rescanBlockchainAsyncPreserveKeyImages() {
        synced = false;
        rescanBlockchainAsyncPreserveKeyImagesJ();
    }

    public native boolean hasUnknownKeyImages();

    public ColdKeyImageSyncResult coldKeyImageSync() {
        long[] result = coldKeyImageSyncJ();
        if (result == null || result.length != 4) {
            throw new IllegalStateException("Invalid cold key image sync result");
        }
        return new ColdKeyImageSyncResult(result[0], result[1], result[2], result[3] != 0);
    }

    private native long[] coldKeyImageSyncJ();

    public HardwareKeyImageRefreshResult refreshWithHardwareKeyImages(
            HardwareKeyImageRefreshResult.Request request) {
        if (request == null) throw new IllegalArgumentException("Refresh request is required");
        long[] result = refreshWithHardwareKeyImagesJ(request.getMode().getNativeValue(), request.getRestoreHeight());
        return HardwareKeyImageRefreshResult.fromNative(result);
    }

    private native long[] refreshWithHardwareKeyImagesJ(int mode, long restoreHeight);

//TODO virtual void setAutoRefreshInterval(int millis) = 0;
//TODO virtual int autoRefreshInterval() const = 0;


    private PendingTransaction pendingTransaction = null;

    public PendingTransaction getPendingTransaction() {
        return pendingTransaction;
    }

    public void disposePendingTransaction() {
        if (pendingTransaction != null) {
            disposeTransaction(pendingTransaction);
            pendingTransaction = null;
        }
    }

    private native long createTransactionMultDest(String[] destinations, String payment_id, long[] amounts, int mixin_count, int priority, int accountIndex, int[] subaddresses);

    public PendingTransaction createTransaction(TxData txData) {
        disposePendingTransaction();
        int _priority = txData.getPriority().getValue();
        final boolean sweepAll = txData.getAmount() == SWEEP_ALL;
        Timber.d("TxData: %s", txData);
        long txHandle = (sweepAll ? createSweepTransaction(txData.getDestination(), "", txData.getMixin(), _priority, accountIndex) :
                createTransactionMultDest(txData.getDestinations(), "", txData.getAmounts(), txData.getMixin(), _priority, accountIndex, txData.getSubaddresses()));
        pendingTransaction = new PendingTransaction(txHandle);
        pendingTransaction.setPocketChange(txData.getPocketChangeAmount());
        return pendingTransaction;
    }

    private native long createTransactionJ(String dst_addr, String payment_id, long amount, int mixin_count, int priority, int accountIndex);

    private native long createSweepTransaction(String dst_addr, String payment_id, int mixin_count, int priority, int accountIndex);


    public PendingTransaction createSweepUnmixableTransaction() {
        disposePendingTransaction();
        long txHandle = createSweepUnmixableTransactionJ();
        pendingTransaction = new PendingTransaction(txHandle);
        return pendingTransaction;
    }

    private native long createSweepUnmixableTransactionJ();

    public UnsignedTransaction loadUnsignedTx(String unsignedFileName) {
        long unsignedTxHandle = loadUnsignedTxJ(unsignedFileName);
        if (unsignedTxHandle == 0) return null;
        return new UnsignedTransaction(unsignedTxHandle);
    }

    private native long loadUnsignedTxJ(String unsignedFileName);

    public native boolean submitTransaction(String fileName);

    public native void disposeTransaction(PendingTransaction pendingTransaction);

    public long estimateTransactionFee(TxData txData) {
        return estimateTransactionFee(txData.getDestinations(), txData.getAmounts(), txData.getPriority().getValue());
    }

    private native long estimateTransactionFee(String[] destinations, long[] amounts, int priority);

    //virtual bool exportKeyImages(const std::string &filename) = 0;
//virtual bool importKeyImages(const std::string &filename) = 0;


//virtual TransactionHistory * history() const = 0;

    private TransactionHistory history = null;

    public TransactionHistory getHistory() {
        if (history == null) {
            history = new TransactionHistory(getHistoryJ(), accountIndex);
        }
        return history;
    }

    private native long getHistoryJ();

    public void refreshHistory() {
        getHistory().refreshWithNotes(this);
    }

//virtual AddressBook * addressBook() const = 0;

    public List<CoinsInfo> getCoinsInfos(boolean unspentOnly) {
        return getCoins().getAll(accountIndex, unspentOnly);
    }

    private Coins coins = null;

    private Coins getCoins() {
        if (coins == null) {
            coins = new Coins(getCoinsJ());
        }
        return coins;
    }

    private native long getCoinsJ();

//virtual void setListener(WalletListener *) = 0;

    private native long setListenerJ(WalletListener listener);

    public void setListener(WalletListener listener) {
        this.listenerHandle = setListenerJ(listener);
    }

    public native int getDefaultMixin();

    public native void setDefaultMixin(int mixin);

    public native boolean setUserNote(String txid, String note);

    public native String getUserNote(String txid);

    public native String getTxKey(String txid);

//virtual std::string signMessage(const std::string &message) = 0;
//virtual bool verifySignedMessage(const std::string &message, const std::string &addres, const std::string &signature) const = 0;

//virtual bool parse_uri(const std::string &uri, std::string &address, std::string &payment_id, uint64_t &tvAmount, std::string &tx_description, std::string &recipient_name, std::vector<std::string> &unknown_parameters, std::string &error) = 0;
//virtual bool rescanSpent() = 0;

    private static final String NEW_ACCOUNT_NAME = "Untitled account"; // src/wallet/wallet2.cpp:941

    public void addAccount() {
        addAccount(NEW_ACCOUNT_NAME);
    }

    public native void addAccount(String label);

    public String getAccountLabel() {
        return getAccountLabel(accountIndex);
    }

    public String getAccountLabel(int accountIndex) {
        String label = getSubaddressLabel(accountIndex, 0);
        if (label.equals(NEW_ACCOUNT_NAME)) {
            String address = getAddress(accountIndex);
            int len = address.length();
            label = address.substring(0, 6) + "\u2026" + address.substring(len - 6, len);
        }
        return label;
    }

    public String getSubaddressLabel(int addressIndex) {
        return getSubaddressLabel(accountIndex, addressIndex);
    }

    public native String getSubaddressLabel(int accountIndex, int addressIndex);

    public void setAccountLabel(String label) {
        setAccountLabel(accountIndex, label);
    }

    public void setAccountLabel(int accountIndex, String label) {
        setSubaddressLabel(accountIndex, 0, label);
    }

    public void setSubaddressLabel(int addressIndex, String label) {
        setSubaddressLabel(accountIndex, addressIndex, label);
        refreshHistory();
    }

    public native void setSubaddressLabel(int accountIndex, int addressIndex, String label);

    public native int getNumAccounts();

    public int getNumSubaddresses() {
        return getNumSubaddresses(accountIndex);
    }

    public native int getNumSubaddresses(int accountIndex);

    public String getNewSubaddress() {
        return getNewSubaddress(accountIndex);
    }

    public String getNewSubaddress(int accountIndex) {
        String timeStamp = new SimpleDateFormat("yyyy-MM-dd-HH:mm:ss", Locale.US).format(new Date());
        addSubaddress(accountIndex, timeStamp);
        String subaddress = getLastSubaddress(accountIndex);
        Timber.d("%d: %s", getNumSubaddresses(accountIndex) - 1, subaddress);
        return subaddress;
    }

    public native void addSubaddress(int accountIndex, String label);

    public String getLastSubaddress(int accountIndex) {
        return getSubaddress(accountIndex, getNumSubaddresses(accountIndex) - 1);
    }

    public Device getDeviceType() {
        int device = getDeviceTypeJ();
        return Device.values()[device + 1]; // mapping is monero+1=android
    }

    private native int getDeviceTypeJ();

    @Getter
    @Setter
    PocketChangeSetting pocketChangeSetting = PocketChangeSetting.of(false, 0);

    @Value(staticConstructor = "of")
    static public class PocketChangeSetting {
        boolean enabled;
        long amount;

        public String toPrefString() {
            return Long.toString((enabled ? 1 : -1) * amount);
        }

        static public PocketChangeSetting from(String prefString) {
            long value = Long.parseLong(prefString);
            return of(value > 0, Math.abs(value));
        }
    }
}
