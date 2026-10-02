package com.piratecash.monero.net

import com.burgstaller.okhttp.AuthenticationCacheInterceptor
import com.burgstaller.okhttp.CachingAuthenticatorDecorator
import com.burgstaller.okhttp.digest.CachingAuthenticator
import com.burgstaller.okhttp.digest.Credentials
import com.burgstaller.okhttp.digest.DigestAuthenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request as OkHttpRequest
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** HTTP client for the kit's own daemon calls (node pings, raw broadcast, tx checks). */
object MoneroHttpClient {
    private const val USER_AGENT = "Monerujo/1.0"
    private const val HTTP_TIMEOUT_CONNECT = 2500L
    private const val HTTP_TIMEOUT_READ = 5000L
    private const val HTTP_TIMEOUT_WRITE = 2500L

    /** Clearnet unless Android's Orbot helper swapped in its Tor client; clearnet takes wallet2's route ([MoneroProxy]). */
    @JvmStatic
    @Volatile
    var client: OkHttpClient = newClearnetClient()

    /** Passive per-call network observer attached to every [Request]; null leaves calls unchanged. */
    @JvmStatic
    @Volatile
    var eventListenerFactory: EventListener.Factory? = null

    @JvmStatic
    fun newClearnetClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(HTTP_TIMEOUT_CONNECT, TimeUnit.MILLISECONDS)
        .writeTimeout(HTTP_TIMEOUT_WRITE, TimeUnit.MILLISECONDS)
        .readTimeout(HTTP_TIMEOUT_READ, TimeUnit.MILLISECONDS)
        .proxySelector(WalletProxySelector)
        .build()

    /** wallet2's SOCKS proxy when set: the JVM default prefers `http.proxyHost`, which a Tor tunnel port rejects. */
    private object WalletProxySelector : ProxySelector() {
        override fun select(uri: URI): List<Proxy> {
            val proxy = MoneroProxy.current()
            if (proxy.isEmpty()) return getDefault().select(uri)
            val address = InetSocketAddress(proxy.substringBeforeLast(':'), proxy.substringAfterLast(':').toInt())
            return listOf(Proxy(Proxy.Type.SOCKS, address))
        }

        override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) =
            getDefault().connectFailed(uri, address, error)
    }

    /** A GET without [data], a JSON POST with it; digest auth when [username] is set. */
    class Request @JvmOverloads constructor(
        private val url: HttpUrl,
        private val data: JSONObject? = null,
        private val username: String? = null,
        private val password: String? = null,
    ) {
        fun enqueue(callback: Callback) = newCall(0).enqueue(callback)

        @Throws(IOException::class)
        fun execute(): Response = newCall(0).execute()

        /** Overrides the call timeout on a derived client, so the shared client keeps its own; 0 keeps the default. */
        fun newCall(callTimeoutMs: Long): Call = buildClient(callTimeoutMs).newCall(buildRequest())

        private fun buildClient(callTimeoutMs: Long): OkHttpClient {
            val base = mockClient ?: client
            val hasAuth = !username.isNullOrEmpty()
            val factory = eventListenerFactory
            if (callTimeoutMs <= 0 && !hasAuth && factory == null) return base

            val builder = base.newBuilder()
            if (callTimeoutMs > 0) builder.callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
            if (hasAuth) {
                val authCache = ConcurrentHashMap<String, CachingAuthenticator>()
                builder.authenticator(CachingAuthenticatorDecorator(DigestAuthenticator(Credentials(username, password)), authCache))
                    .addInterceptor(AuthenticationCacheInterceptor(authCache))
            }
            if (factory != null) builder.eventListenerFactory(factory)
            return builder.build()
        }

        private fun buildRequest(): OkHttpRequest {
            val builder = OkHttpRequest.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
            if (data == null) builder.get() else builder.post(data.toString().toRequestBody("application/json".toMediaType()))
            return builder.build()
        }

        companion object {
            /** Unit tests only: replaces [client] for every request. */
            @JvmField
            var mockClient: OkHttpClient? = null
        }
    }
}
