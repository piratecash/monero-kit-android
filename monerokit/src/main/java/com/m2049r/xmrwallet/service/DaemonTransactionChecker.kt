package com.m2049r.xmrwallet.service

import androidx.annotation.VisibleForTesting
import com.m2049r.xmrwallet.model.WalletManager
import kotlinx.coroutines.CancellationException
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Best-effort check of which transactions are already known to the currently configured
 * daemon (present in its mempool or already mined).
 *
 * Used before broadcasting an offline-signed transaction, to avoid reporting a duplicate
 * submission as a fresh "sent" result. The whole set is asked for in a single round trip,
 * which is intentionally best-effort: any I/O error, unexpected response, or slow/unreachable
 * daemon is treated as "none known", so it never blocks or delays the real broadcast beyond
 * [DaemonRpc.TIMEOUT_MS] regardless of how many transactions are queried.
 *
 * Note: this reveals interest in those txids to the queried daemon.
 */
object DaemonTransactionChecker {
    private const val GET_TRANSACTIONS_PATH = "get_transactions"

    suspend fun knownTransactions(txIds: List<String>): Set<String> {
        val walletManager = WalletManager.getInstance()
        return checkKnownTransactions(
            txIds = txIds,
            daemonAddress = walletManager.getDaemonRpcAddress(),
            username = walletManager.getDaemonUsername(),
            password = walletManager.getDaemonPassword(),
        )
    }

    /**
     * Runs the daemon query for the given connection details, treating any failure (including
     * a request that hit [timeoutMs]) as "none known". Coroutine cancellation is rethrown
     * rather than swallowed, so cooperative cancellation still works.
     *
     * Takes explicit connection parameters (instead of reading them from [WalletManager]) so it
     * can be exercised from JVM unit tests without loading the native wallet library.
     */
    @VisibleForTesting
    internal suspend fun checkKnownTransactions(
        txIds: List<String>,
        daemonAddress: String,
        username: String,
        password: String,
        timeoutMs: Long = DaemonRpc.TIMEOUT_MS,
    ): Set<String> = try {
        queryKnownTransactions(txIds, daemonAddress, username, password, timeoutMs)
    } catch (ex: CancellationException) {
        throw ex
    } catch (ex: Exception) {
        Timber.d(ex, "knownTransactions check failed")
        emptySet()
    }

    private suspend fun queryKnownTransactions(
        txIds: List<String>,
        daemonAddress: String,
        username: String,
        password: String,
        timeoutMs: Long,
    ): Set<String> = DaemonRpc.post(
        path = GET_TRANSACTIONS_PATH,
        body = JSONObject()
            .put("txs_hashes", JSONArray(txIds))
            .put("decode_as_json", false),
        daemonAddress = daemonAddress,
        username = username,
        password = password,
        timeoutMs = timeoutMs,
    ) { parseKnownTransactions(it, txIds) }

    /** Returns the queried ids as spelled by the caller, so callers can match them directly. */
    private fun parseKnownTransactions(response: Response, txIds: List<String>): Set<String> {
        if (!response.isSuccessful) return emptySet()
        val json = JSONObject(response.body?.string().orEmpty())
        val txs = json.optJSONArray("txs") ?: return emptySet()
        val reported = buildSet {
            for (i in 0 until txs.length()) {
                txs.optJSONObject(i)?.optString("tx_hash")?.let { add(it.lowercase()) }
            }
        }
        return txIds.filterTo(mutableSetOf()) { it.lowercase() in reported }
    }
}
