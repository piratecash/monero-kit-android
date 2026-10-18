package com.piratecash.monero

import android.content.Context
import com.m2049r.xmrwallet.model.WalletManager
import com.m2049r.xmrwallet.util.CrazyPassEncoder
import com.m2049r.xmrwallet.util.Helper
import com.m2049r.xmrwallet.util.KeyStoreHelper
import com.piratecash.monero.log.MoneroLog
import java.io.File

/** Android wallet storage: `<filesDir>/wallets`, the location every existing wallet lives in. */
object MoneroWalletFiles {
    private const val TAG = "MoneroKit:Helper"
    private const val WALLET_DIR = "wallets"

    @JvmStatic
    fun root(context: Context): File = Helper.getStorage(context.filesDir, WALLET_DIR)

    @JvmStatic
    fun file(context: Context, walletName: String): File {
        val file = File(root(context), walletName)
        MoneroLog.d(TAG, "wallet=%s size= %d", file.absolutePath, file.length())
        return file
    }

    @JvmStatic
    fun useCrazyPass(context: Context): Boolean = !File(root(context), Helper.NOCRAZYPASS_FLAGFILE).exists()

    /**
     * Finds the real wallet password for [password]: the password itself, a reformatted CrAzYpass,
     * the CrAzYpass derived from it, or one of the two broken CrAzYpass variants.
     */
    @JvmStatic
    fun getWalletPassword(context: Context, walletName: String, password: String): String? {
        val walletPath = File(root(context), "$walletName.keys").absolutePath
        val walletManager = WalletManager.getInstance()
        return sequence {
            yield(password)
            CrazyPassEncoder.reformat(password)?.let { yield(it) }
            yield(KeyStoreHelper.getCrazyPass(context, password))
            KeyStoreHelper.getBrokenCrazyPass(context, password, 2)?.let { yield(it) }
            KeyStoreHelper.getBrokenCrazyPass(context, password, 1)?.let { yield(it) }
        }.firstOrNull { walletManager.verifyWalletPasswordOnly(walletPath, it) }
    }
}
