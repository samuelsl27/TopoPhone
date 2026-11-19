package es.topophone.receiver.experimental

import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * El directo por un WebSocket de OkHttp: `/socket.io/?EIO=3&transport=websocket` (S044/RF-16; plan D7). Los sockets salen por la red que
 * dé [socketFactory] (la Wi-Fi del receptor), no hay ping propio de OkHttp (lo manda Engine.IO) y la lectura no caduca: caduca el ping.
 * **Riesgo:** la referencia conecta por *polling* y sube a WebSocket; conectar directo lo admite Engine.IO v3, pero se confirma en el campo.
 */
class OkHttpLiveTransport(private val endpoint: ReachEndpoint, socketFactory: SocketFactory) : LiveTransport {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .socketFactory(socketFactory)
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    override fun connect(listener: LiveListener): LiveConnection {
        val url = endpoint.url("/socket.io/").newBuilder()
            .addQueryParameter("EIO", "3")
            .addQueryParameter("transport", "websocket")
            .build()
        val socket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) = listener.onFrame(text)

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(NORMAL_CLOSURE, null)
                    listener.onEnded()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onEnded()

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = listener.onEnded()
            },
        )
        return object : LiveConnection {
            override fun send(text: String) {
                socket.send(text)
            }

            override fun close() {
                socket.close(NORMAL_CLOSURE, null)
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 3_000L
        const val NORMAL_CLOSURE = 1000
    }
}
