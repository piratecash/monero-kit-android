package com.m2049r.xmrwallet.service

import com.google.common.net.HostAndPort
import com.m2049r.levin.util.NetCipherHelper
import com.m2049r.xmrwallet.data.Node
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Shared plumbing for the daemon's plain (non-JSON-RPC) endpoints: URL building, authentication
 * and a bounded asynchronous call. Requests go through the same [NetCipherHelper] client as all
 * other daemon calls, so callers relying on Tor keep that protection here too.
 */
internal object DaemonRpc {
    const val TIMEOUT_MS = 3500L

    suspend fun <T> post(
        path: String,
        body: JSONObject,
        daemonAddress: String,
        username: String,
        password: String,
        timeoutMs: Long,
        parse: (Response) -> T,
    ): T = NetCipherHelper.Request(url(daemonAddress, path), body, username, password)
        .newCall(timeoutMs)
        .await(parse)

    private fun url(daemonAddress: String, path: String): HttpUrl {
        // Node.getDefaultRpcPort() touches WalletManager, so only call it (lazily) when the
        // daemon address doesn't already specify a port.
        val parsedAddress = HostAndPort.fromString(daemonAddress)
        val hostAndPort = if (parsedAddress.hasPort()) {
            parsedAddress
        } else {
            parsedAddress.withDefaultPort(Node.getDefaultRpcPort())
        }

        return HttpUrl.Builder()
            .scheme("http")
            .host(hostAndPort.host)
            .port(hostAndPort.port)
            .addPathSegment(path)
            .build()
    }

    /** The bound is the call's own callTimeout, not the coroutine; cancelling also cancels the call. */
    private suspend fun <T> Call.await(parse: (Response) -> T): T =
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
                        response.use { continuation.resume(parse(it)) }
                    } catch (ex: Exception) {
                        continuation.resumeWithException(ex)
                    }
                }
            })
        }
}
