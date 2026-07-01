package com.m2049r.xmrwallet.service

import androidx.annotation.VisibleForTesting
import com.google.common.net.HostAndPort
import com.m2049r.levin.util.NetCipherHelper
import com.m2049r.xmrwallet.data.Node
import com.m2049r.xmrwallet.model.WalletManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Best-effort check of whether a transaction is already known to the currently configured
 * daemon (present in its mempool or already mined).
 *
 * Used before broadcasting an offline-signed transaction, to avoid reporting a duplicate
 * submission as a fresh "sent" result. The network round trip is intentionally best-effort:
 * any I/O error, unexpected response, or slow/unreachable daemon is treated as "unknown"
 * (false), so it never blocks or delays the real broadcast beyond [TIMEOUT_MS]. The bound is
 * enforced by a dedicated OkHttp call timeout rather than a coroutine-level timeout, since a
 * coroutine timeout cannot interrupt a call that has no suspension point of its own.
 *
 * Note: this reveals interest in a specific txid to the queried daemon. Callers relying on
 * Tor for privacy get that protection here too, since requests go through the same
 * [NetCipherHelper] client used for all other daemon calls.
 */
object DaemonTransactionChecker {
    private const val TIMEOUT_MS = 3500L
    private const val GET_TRANSACTIONS_PATH = "get_transactions"

    suspend fun transactionExistsOnChain(txId: String): Boolean {
        val walletManager = WalletManager.getInstance()
        return checkTransactionExists(
            txId = txId,
            daemonAddress = walletManager.getDaemonAddress(),
            username = walletManager.getDaemonUsername(),
            password = walletManager.getDaemonPassword(),
        )
    }

    /**
     * Runs the daemon query for the given connection details, treating any failure (including
     * a request that hit [timeoutMs]) as "unknown" (false). Coroutine cancellation is rethrown
     * rather than swallowed, so cooperative cancellation still works.
     *
     * Takes explicit connection parameters (instead of reading them from [WalletManager]) so it
     * can be exercised from JVM unit tests without loading the native wallet library.
     */
    @VisibleForTesting
    internal suspend fun checkTransactionExists(
        txId: String,
        daemonAddress: String,
        username: String,
        password: String,
        timeoutMs: Long = TIMEOUT_MS,
    ): Boolean = try {
        queryTransactionExists(txId, daemonAddress, username, password, timeoutMs)
    } catch (ex: CancellationException) {
        throw ex
    } catch (ex: Exception) {
        Timber.d(ex, "transactionExistsOnChain check failed")
        false
    }

    private suspend fun queryTransactionExists(
        txId: String,
        daemonAddress: String,
        username: String,
        password: String,
        timeoutMs: Long,
    ): Boolean {
        // Node.getDefaultRpcPort() touches WalletManager, so only call it (lazily) when the
        // daemon address doesn't already specify a port.
        val parsedAddress = HostAndPort.fromString(daemonAddress)
        val hostAndPort = if (parsedAddress.hasPort()) {
            parsedAddress
        } else {
            parsedAddress.withDefaultPort(Node.getDefaultRpcPort())
        }

        val url = HttpUrl.Builder()
            .scheme("http")
            .host(hostAndPort.host)
            .port(hostAndPort.port)
            .addPathSegment(GET_TRANSACTIONS_PATH)
            .build()

        val body = JSONObject()
            .put("txs_hashes", JSONArray().put(txId))
            .put("decode_as_json", false)

        val request = NetCipherHelper.Request(url, body, username, password)
        return request.newCall(timeoutMs).awaitTransactionExists(txId)
    }

    /**
     * Awaits the call asynchronously with a genuine bound: the call's own
     * [okhttp3.OkHttpClient.Builder.callTimeout] aborts it at ~[timeoutMs] regardless of
     * coroutine cancellation, and cancelling the coroutine cancels the underlying call.
     */
    private suspend fun Call.awaitTransactionExists(txId: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!continuation.isActive) {
                        response.close()
                        return
                    }
                    try {
                        response.use { continuation.resume(parseTransactionExists(it, txId)) }
                    } catch (ex: Exception) {
                        continuation.resumeWithException(ex)
                    }
                }
            })
        }

    private fun parseTransactionExists(response: Response, txId: String): Boolean {
        if (!response.isSuccessful) return false
        val json = JSONObject(response.body?.string().orEmpty())
        val txs = json.optJSONArray("txs") ?: return false
        for (i in 0 until txs.length()) {
            if (txs.optJSONObject(i)?.optString("tx_hash").equals(txId, ignoreCase = true)) {
                return true
            }
        }
        return false
    }
}
