package es.topophone.data.wms

import es.topophone.core.model.wms.WmsLimits
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** La respuesta a un GET: el código HTTP y el cuerpo, que solo se puede leer dentro de [WmsHttp.get]. */
class WmsHttpResponse(val code: Int, val body: InputStream)

/** La petición tardó más de [WmsLimits.TIMEOUT_S] s (S036/RF-8, RF-39). */
class WmsTimeoutException(cause: Throwable? = null) : IOException("El servicio tardó más de ${WmsLimits.TIMEOUT_S} s en responder", cause)

/**
 * El acceso a la red de los servicios WMS: el GetCapabilities y las consultas de información (S036/RF-1, RF-36). Es una interfaz para
 * probar con respuestas grabadas; no pasa por el cliente del mapa, así que un servicio que falla al añadirlo no enciende el aviso «Sin
 * conexión» del mapa (S009/RF-15).
 */
interface WmsHttp {
    /**
     * Pide [url] y llama a [read] con la respuesta, que se cierra al volver. Lanza [WmsTimeoutException] si pasa de [WmsLimits.TIMEOUT_S] s
     * y [IOException] si no hay conexión o la dirección no vale.
     */
    suspend fun <T> get(url: String, read: (WmsHttpResponse) -> T): T
}

/** [WmsHttp] con OkHttp (ADR-13). */
class OkHttpWmsHttp(private val client: OkHttpClient) : WmsHttp {
    override suspend fun <T> get(url: String, read: (WmsHttpResponse) -> T): T = withContext(Dispatchers.IO) {
        val request = try {
            Request.Builder().url(url).header("Accept", "text/xml, application/xml, text/plain, */*").build()
        } catch (error: IllegalArgumentException) {
            throw IOException("La dirección no es válida", error)
        }
        val call = client.newCall(request)
        // Si se cancela la corrutina se corta la petición, que si no seguiría bloqueada en la red.
        val registration = currentCoroutineContext().job.invokeOnCompletion { if (it != null) call.cancel() }
        try {
            call.execute().use { response ->
                val body = response.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
                read(WmsHttpResponse(response.code, body))
            }
        } catch (error: InterruptedIOException) {
            throw WmsTimeoutException(error) // SocketTimeoutException y el tiempo total de la llamada
        } finally {
            registration.dispose()
        }
    }
}

object WmsHttpClient {
    private const val CONNECT_TIMEOUT_S = 10L

    /**
     * El cliente de los servicios WMS: 20 s en total por petición, con [userAgent] como identificación de la app (S036, condiciones de uso
     * de los servicios) y sin seguir una redirección que cambie de HTTPS a HTTP, para que una dirección HTTPS nunca baje a HTTP (RF-10).
     */
    fun create(userAgent: String): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(WmsLimits.TIMEOUT_S.toLong(), TimeUnit.SECONDS)
        .callTimeout(WmsLimits.TIMEOUT_S.toLong(), TimeUnit.SECONDS)
        .followSslRedirects(false)
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build()) }
        .build()
}
