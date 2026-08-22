package com.m2049r.xmrwallet.service

import androidx.annotation.VisibleForTesting
import com.m2049r.xmrwallet.model.WalletManager
import com.m2049r.xmrwallet.offline.MoneroRawTransactionError
import com.m2049r.xmrwallet.util.Helper
import kotlinx.coroutines.CancellationException
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

/**
 * Broadcasts an already signed transaction through the currently configured daemon.
 *
 * The wallet takes no part in this: the blob is relayed as is, which is what makes an
 * offline-signed transaction broadcastable from a device holding a completely different wallet.
 */
object DaemonTransactionSender {
    private const val SEND_RAW_TRANSACTION_PATH = "sendrawtransaction"

    // Same wording and order as wallet2's get_text_reason().
    private val rejectionReasons = listOf(
        "low_mixin" to "bad ring size",
        "double_spend" to "double spend",
        "invalid_input" to "invalid input",
        "invalid_output" to "invalid output",
        "too_few_outputs" to "too few outputs",
        "too_big" to "too big",
        "overspend" to "overspend",
        "fee_too_low" to "fee too low",
        "sanity_check_failed" to "tx sanity check failed",
        "not_relayed" to "tx was not relayed",
    )

    suspend fun sendRawTransaction(blob: ByteArray) {
        val walletManager = WalletManager.getInstance()
        sendRawTransaction(
            blob = blob,
            daemonAddress = walletManager.getDaemonRpcAddress(),
            username = walletManager.getDaemonUsername(),
            password = walletManager.getDaemonPassword(),
        )
    }

    /**
     * Takes explicit connection parameters (instead of reading them from [WalletManager]) so it
     * can be exercised from JVM unit tests without loading the native wallet library.
     */
    @VisibleForTesting
    internal suspend fun sendRawTransaction(
        blob: ByteArray,
        daemonAddress: String,
        username: String,
        password: String,
        timeoutMs: Long = DaemonRpc.TIMEOUT_MS,
    ) {
        val body = JSONObject()
            .put("tx_as_hex", Helper.bytesToHex(blob).lowercase())
            .put("do_not_relay", false)

        val rejection = try {
            DaemonRpc.post(
                path = SEND_RAW_TRANSACTION_PATH,
                body = body,
                daemonAddress = daemonAddress,
                username = username,
                password = password,
                timeoutMs = timeoutMs,
                parse = ::parseRejection,
            )
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            throw MoneroRawTransactionError.DaemonUnavailable(ex.message ?: "Daemon is unreachable")
        }

        if (rejection != null) throw MoneroRawTransactionError.SubmitFailed(rejection)
    }

    /** Returns the rejection reason, or null when the daemon accepted and relayed the transaction. */
    private fun parseRejection(response: Response): String? {
        if (!response.isSuccessful) throw IOException("Daemon returned HTTP ${response.code}")

        val json = JSONObject(response.body?.string().orEmpty())
        val status = json.optString("status")
        if (status == "OK" && !json.optBoolean("not_relayed")) return null

        // An unparsable transaction comes back with nothing but status = "Failed", so status is
        // the last resort rather than a formality.
        return json.optString("reason").takeIf { it.isNotBlank() }
            ?: rejectionReasons.filter { json.optBoolean(it.first) }
                .joinToString { it.second }
                .takeIf { it.isNotBlank() }
            ?: status.takeIf { it.isNotBlank() }
            ?: "Daemon rejected the transaction"
    }
}
