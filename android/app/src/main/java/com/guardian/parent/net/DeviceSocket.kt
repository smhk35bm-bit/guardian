package com.guardian.parent.net

import com.guardian.parent.core.Config
import com.guardian.parent.core.Logger
import com.guardian.parent.data.LocalStore
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The persistent control channel between the child device and the server.
 *
 * It is a plain WebSocket authenticated with the device token
 * (`/ws?token=<deviceToken>`), the very same token the HTTP API already uses —
 * no extra credential is introduced.
 *
 * The socket has two directions:
 *  - **text** frames carry control messages (`live.start`, `live.stop`…),
 *  - **binary** frames carry live JPEG frames, prefixed by a small header so
 *    the server can route them without decoding the image.
 *
 * The connection survives the mobile network: it reconnects with an
 * exponential backoff and never queues frames, so a 4G hiccup loses at most
 * one picture instead of building up memory.
 */
class DeviceSocket(
    private val store: LocalStore,
    private val onControl: (JSONObject) -> Unit,
    private val onState: (connected: Boolean) -> Unit = {}
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(Config.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // WebSocket: no read timeout
        .writeTimeout(Config.WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "guardian-socket").apply { isDaemon = true }
    }

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var stopped: Boolean = false

    @Volatile
    private var connected: Boolean = false

    private val attempt = AtomicInteger(0)
    private val sequence = AtomicInteger(0)
    private var reconnectTask: ScheduledFuture<*>? = null

    fun isConnected(): Boolean = connected

    /** Opens the socket, or schedules a retry when the device is not paired. */
    fun connect() {
        stopped = false
        openNow()
    }

    fun close() {
        stopped = true
        connected = false
        reconnectTask?.cancel(false)
        reconnectTask = null
        try {
            socket?.close(1000, "service stopped")
        } catch (t: Throwable) {
            Logger.w("Unable to close the device socket", t)
        }
        socket = null
        scheduler.shutdownNow()
    }

    /** Sends a JSON control/ack message. Returns `false` when offline. */
    fun sendText(message: JSONObject): Boolean {
        val current = socket ?: return false
        if (!connected) return false
        return try {
            current.send(message.toString())
        } catch (t: Throwable) {
            Logger.w("Unable to send a control message", t)
            false
        }
    }

    /**
     * Sends one compressed frame.
     *
     * The header is:
     * `[version:1][source:1][flags:2][seq:4][length:4]` (big endian), followed
     * by the JPEG bytes. The server prepends the device id before relaying it
     * to the parent, so the device never has to trust its own numbering.
     */
    fun sendFrame(source: Int, jpeg: ByteArray): Boolean {
        val current = socket ?: return false
        if (!connected) return false
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        header.put(PROTOCOL_VERSION)
        header.put(source.toByte())
        header.putShort(0)
        header.putInt(sequence.incrementAndGet())
        header.putInt(jpeg.size)
        val frame = ByteBuffer.allocate(HEADER_SIZE + jpeg.size)
            .order(ByteOrder.BIG_ENDIAN)
        frame.put(header.array())
        frame.put(jpeg)
        return try {
            current.send(frame.array().toByteString())
        } catch (t: Throwable) {
            Logger.w("Unable to send a live frame", t)
            false
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun openNow() {
        if (stopped) return
        val url = socketUrl()
        if (url == null) {
            Logger.w("The device socket cannot start: the device is not paired")
            scheduleReconnect()
            return
        }

        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                attempt.set(0)
                Logger.i("Device socket connected")
                onState(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = try {
                    JSONObject(text)
                } catch (t: Throwable) {
                    return
                }
                try {
                    onControl(json)
                } catch (t: Throwable) {
                    Logger.w("The live control handler failed", t)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                onState(false)
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                Logger.w("Device socket failed: ${t.message}")
                onState(false)
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (stopped) return
        val tries = attempt.incrementAndGet()
        val delay = (Config.LIVE_RECONNECT_MIN_MS * (1L shl minOf(tries, 5)))
            .coerceAtMost(Config.LIVE_RECONNECT_MAX_MS)
        reconnectTask?.cancel(false)
        reconnectTask = try {
            scheduler.schedule({ openNow() }, delay, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            null
        }
        Logger.d("Device socket reconnect in ${delay}ms")
    }

    /**
     * `https://host/api` → `wss://host/ws?token=…`
     * `http://host:8080/api` → `ws://host:8080/ws?token=…`
     */
    private fun socketUrl(): String? {
        val base = store.serverUrl?.trim().orEmpty()
        val token = store.deviceToken?.trim().orEmpty()
        if (base.isEmpty() || token.isEmpty()) return null

        var root = base.trimEnd('/')
        if (root.endsWith("/api", ignoreCase = true)) {
            root = root.dropLast(4).trimEnd('/')
        }
        root = when {
            root.startsWith("https://", ignoreCase = true) ->
                "wss://" + root.substring(8)
            root.startsWith("http://", ignoreCase = true) ->
                "ws://" + root.substring(7)
            else -> return null
        }
        val encoded = URLEncoder.encode(token, "UTF-8")
        return "$root/ws?token=$encoded"
    }

    companion object {
        const val PROTOCOL_VERSION: Byte = 1
        const val HEADER_SIZE = 12
    }
}
