package es.topophone.receiver.experimental

import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Por qué no hay respuesta: nunca lleva el cuerpo ni valores del receptor (RNF-T11). */
enum class ReachFailure { NO_RESPONSE, HTTP_ERROR, REDIRECT, WRONG_HOST }

sealed interface ReachResult<out T> {
    data class Ok<T>(val value: T) : ReachResult<T>

    data class Failed(val cause: ReachFailure) : ReachResult<Nothing>
}

/**
 * Cliente HTTP del receptor (S044/RF-11, RF-15, RF-23; plan D5): los sockets salen por la red Wi-Fi que se le da
 * (nunca por la de internet), cada llamada dura como mucho [callTimeoutMs], no se sigue ninguna redirección y solo se habla con el
 * host con el que se creó.
 */
class ReachHttpClient(private val endpoint: ReachEndpoint, socketFactory: SocketFactory, callTimeoutMs: Long = CALL_TIMEOUT_MS) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .socketFactory(socketFactory)
        .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .addInterceptor(HostLock(endpoint))
        .build()

    suspend fun get(path: String): ReachResult<String> = getUrl(endpoint.url(path))

    suspend fun post(path: String, jsonBody: String): ReachResult<String> =
        execute(Request.Builder().url(endpoint.url(path)).post(jsonBody.toRequestBody(JSON)).build())

    internal suspend fun getUrl(url: HttpUrl): ReachResult<String> = execute(Request.Builder().url(url).get().build())

    private suspend fun execute(request: Request): ReachResult<String> = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resume(ReachResult.Failed(if (e is WrongHostException) ReachFailure.WRONG_HOST else ReachFailure.NO_RESPONSE))
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response.use(::read))
            }
        })
    }

    private fun read(response: Response): ReachResult<String> = when {
        response.isRedirect -> ReachResult.Failed(ReachFailure.REDIRECT)
        !response.isSuccessful -> ReachResult.Failed(ReachFailure.HTTP_ERROR)
        else -> try {
            ReachResult.Ok(response.peekBody(MAX_BODY_BYTES).string())
        } catch (_: IOException) {
            ReachResult.Failed(ReachFailure.NO_RESPONSE)
        }
    }

    private class WrongHostException : IOException("Otro host")

    /** Defensa añadida a [followRedirects]: ninguna petición sale hacia un host o un puerto que no sean los del receptor. */
    private class HostLock(private val endpoint: ReachEndpoint) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val url = chain.request().url
            if (url.host != endpoint.host || url.port != endpoint.port) throw WrongHostException()
            return chain.proceed(chain.request())
        }
    }

    companion object {
        /** Cada llamada, completa (conectar, enviar y leer), no pasa de 3 s (S044/RF-15). */
        const val CALL_TIMEOUT_MS = 3_000L
        private const val MAX_BODY_BYTES = 1_000_000L
        private val JSON = "application/json".toMediaType()
    }
}
