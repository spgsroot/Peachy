package io.github.spgsroot.buttplug.connection

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

internal fun webSocketRequest(url: String): Request {
    val request = try {
        Request.Builder().url(url.trim()).get().build()
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid Buttplug WebSocket address")
    }
    val host = request.url.host
    val loopbackCleartext = request.url.scheme == "http" &&
        (host == "localhost" || host == "::1" ||
            (host.startsWith("127.") && host.split('.').let { parts ->
                parts.size == 4 && parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
            }))
    require(request.url.scheme == "https" || loopbackCleartext) {
        "Only local ws:// or certificate-validated wss:// connections are allowed"
    }
    return request
}

/** OkHttp WebSocket transport with the platform's default TLS and hostname verification. */
class WebSocketTransport : ButtplugTransport {

    private var webSocket: WebSocket? = null
    @Volatile private var _isConnected: Boolean = false
    private var listener: TransportListener? = null

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // no read timeout for persistent connection
            .followRedirects(false)
            .build()
    }

    override val isConnected: Boolean get() = _isConnected

    override fun connect(url: String, listener: TransportListener) {
        this.listener = listener
        client.newWebSocket(webSocketRequest(url), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                this@WebSocketTransport.webSocket = webSocket
                _isConnected = true
                listener.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                listener.onMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _isConnected = false
                this@WebSocketTransport.webSocket = null
                listener.onDisconnected(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _isConnected = false
                this@WebSocketTransport.webSocket = null
                listener.onError(t)
            }
        })
    }

    override fun send(message: String) {
        webSocket?.send(message) ?: throw IllegalStateException("WebSocket not connected")
    }

    override fun disconnect() {
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _isConnected = false
    }
}
