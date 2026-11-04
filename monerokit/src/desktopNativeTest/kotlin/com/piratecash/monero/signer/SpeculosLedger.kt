package com.piratecash.monero.signer

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** A speculos running the Ledger Monero app: `SPECULOS_APDU_PORT` (required) and `SPECULOS_API_URL`. */
internal object Speculos {
    val apduPort: Int? = System.getenv("SPECULOS_APDU_PORT")?.toIntOrNull()
    val apiUrl: String = System.getenv("SPECULOS_API_URL") ?: "http://127.0.0.1:5000"
    val http: HttpClient = HttpClient.newHttpClient()
}

/**
 * The speculos APDU socket behind the kit's Ledger channel. Like a production channel it reports a lost
 * transport as [HardwareWalletErrorCode.Disconnected]; [beforeExchange] sees each 1-based APDU number
 * and may throw to simulate pulling the cable.
 */
internal class SpeculosApduChannel(
    private val beforeExchange: (number: Int, apdu: ByteArray) -> Unit = { _, _ -> },
) : ExternalSignerApduChannel {
    val count = AtomicInteger()

    @Volatile
    private var socket: Socket? = null

    override fun exchange(apdu: ByteArray): ByteArray {
        beforeExchange(count.incrementAndGet(), apdu)
        try {
            val open = socket ?: Socket("127.0.0.1", requireNotNull(Speculos.apduPort)).also { socket = it }
            DataOutputStream(open.getOutputStream()).run {
                writeInt(apdu.size)
                write(apdu)
                flush()
            }
            val input = DataInputStream(open.getInputStream())
            return ByteArray(input.readInt() + 2).also(input::readFully)
        } catch (error: IOException) {
            throw ExternalSignerException(HardwareWalletErrorCode.Disconnected, "Speculos connection lost", error)
        }
    }

    override fun cancel() {
        socket?.close()
    }

    /** A restarted speculos needs a new socket; the registration stays. */
    fun reconnect() {
        socket?.close()
        socket = null
    }
}

/** Walks the Monero app's prompts to their approve (or reject) step and records every screen. */
internal class SpeculosScreenWalker(private val approve: Boolean) {
    val screens: MutableList<String> = CopyOnWriteArrayList()

    @Volatile
    private var running = true
    private val worker = thread(isDaemon = true) {
        while (running) {
            Thread.sleep(STEP_MILLIS)
            val texts = runCatching { screenTexts() }.getOrNull().orEmpty()
            if (texts.isEmpty()) continue
            val screen = texts.joinToString(" | ")
            if (screens.lastOrNull() != screen) screens += screen
            when {
                HOME in texts.first() || texts.any { it.startsWith("Processing") } -> Unit
                texts.any { it in if (approve) APPROVE else REJECT } -> press("both")
                else -> press("right")
            }
        }
    }

    fun stop() {
        running = false
        worker.join()
    }

    private fun screenTexts(): List<String> {
        val request = HttpRequest.newBuilder(URI("${Speculos.apiUrl}/events?currentscreenonly=true")).GET().build()
        val body = Speculos.http.send(request, HttpResponse.BodyHandlers.ofString()).body()
        return TEXT.findAll(body).map { it.groupValues[1] }.toList()
    }

    private fun press(button: String) {
        val request = HttpRequest.newBuilder(URI("${Speculos.apiUrl}/button/$button"))
            .POST(HttpRequest.BodyPublishers.ofString("""{"action":"press-and-release"}"""))
            .header("Content-Type", "application/json")
            .build()
        runCatching { Speculos.http.send(request, HttpResponse.BodyHandlers.discarding()) }
    }

    private companion object {
        const val STEP_MILLIS = 250L
        const val HOME = "XMR / "
        val APPROVE = setOf("Accept", "Yes", "Ok", "Sign transaction")
        val REJECT = setOf("Reject", "No!")
        val TEXT = Regex("\"text\": \"([^\"]*)\"")
    }
}

/** Registers [channel] as the kit's Ledger channel while a walker answers the device prompts. */
internal fun <T> withSpeculosLedger(
    approve: Boolean = true,
    channel: SpeculosApduChannel = SpeculosApduChannel(),
    block: (SpeculosScreenWalker) -> T,
): T {
    val registration = ExternalSigner.registerApdu(channel)
    val walker = SpeculosScreenWalker(approve)
    try {
        return block(walker)
    } finally {
        walker.stop()
        registration.release()
    }
}
