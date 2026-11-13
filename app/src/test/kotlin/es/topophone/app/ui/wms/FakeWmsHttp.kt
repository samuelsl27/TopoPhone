package es.topophone.app.ui.wms

import es.topophone.data.wms.WmsHttp
import es.topophone.data.wms.WmsHttpResponse
import java.io.ByteArrayInputStream
import java.util.Collections

/** Una respuesta con su código HTTP; un [ByteArray] suelto es un 200. */
class FakeReply(val code: Int, val body: ByteArray)

/**
 * Un servicio que contesta lo que se le diga y apunta qué direcciones se le pidieron. [answer] devuelve un [ByteArray] (200), un
 * [FakeReply] o un [Throwable] para que la petición falle, y puede suspender para probar peticiones a la vez.
 */
class FakeWmsHttp(var answer: suspend (String) -> Any) : WmsHttp {
    val urls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override suspend fun <T> get(url: String, read: (WmsHttpResponse) -> T): T {
        urls += url
        return when (val reply = answer(url)) {
            is Throwable -> throw reply
            is ByteArray -> read(WmsHttpResponse(HTTP_OK, ByteArrayInputStream(reply)))
            is FakeReply -> read(WmsHttpResponse(reply.code, ByteArrayInputStream(reply.body)))
            else -> error("Respuesta de prueba desconocida")
        }
    }

    private companion object {
        const val HTTP_OK = 200
    }
}
