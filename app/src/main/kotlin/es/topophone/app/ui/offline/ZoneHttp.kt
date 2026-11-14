package es.topophone.app.ui.offline

import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Lo que el descargador le pide al cliente HTTP del mapa: alargar la caducidad de lo que se descarga mientras dura la descarga (S037/RF-23). */
interface DownloadHttpMode {
    fun setLongCache(active: Boolean)

    companion object {
        /** Sin cliente HTTP: para los tests y las clases que no lo reciben. */
        val NONE: DownloadHttpMode = object : DownloadHttpMode {
            override fun setLongCache(active: Boolean) = Unit
        }
    }
}

/**
 * La parte del cliente HTTP del mapa que es de las zonas sin conexión (S037, plan «ZoneHttp»), con lo que midió T1:
 * - Contesta sin red los estilos de las direcciones de `topophone.invalid` ([RegionStyles]), porque MapLibre no lee estilos `file://`.
 * - Mientras se descarga una zona, pone `Cache-Control: max-age=31536000` (y quita `Expires`) a las respuestas correctas y a los 304. Sin eso
 *   MapLibre revalida con `If-None-Match` todo lo que el servidor no da con caducidad (el IGN vectorial y los WMS) cada vez que se abre el mapa, y no se
 *   cumple que una zona completa no pida a la red lo que ya tiene (RF-23). Las respuestas de fuera de una descarga no se tocan.
 */
@Singleton
class ZoneHttp @Inject constructor() : DownloadHttpMode {
    @Volatile
    private var longCache = false

    override fun setLongCache(active: Boolean) {
        longCache = active
    }

    val interceptor = Interceptor { chain ->
        val request = chain.request()
        if (RegionStyles.isOurs(request.url)) return@Interceptor styleResponse(request)
        val response = chain.proceed(request)
        if (longCache && (response.isSuccessful || response.code == NOT_MODIFIED)) {
            response.newBuilder().removeHeader("Expires").removeHeader("Cache-Control").header("Cache-Control", ONE_YEAR).build()
        } else {
            response
        }
    }

    private fun styleResponse(request: Request): Response {
        val json = RegionStyles.json(request.url)
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(if (json == null) NOT_FOUND else OK)
            .message(if (json == null) "Not Found" else "OK")
            .body((json ?: "").toResponseBody(JSON))
            .build()
    }

    private companion object {
        const val OK = 200
        const val NOT_MODIFIED = 304
        const val NOT_FOUND = 404
        const val ONE_YEAR = "max-age=31536000"
        val JSON = "application/json".toMediaType()
    }
}
