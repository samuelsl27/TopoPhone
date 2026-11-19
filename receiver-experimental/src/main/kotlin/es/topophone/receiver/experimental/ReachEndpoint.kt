package es.topophone.receiver.experimental

import es.topophone.core.model.guide.PrivateAddress
import okhttp3.HttpUrl

/**
 * Dónde habla el receptor: una IP privada o de enlace local y el puerto 80 (S044/RF-23, plan D5).
 * No hay forma pública de crear uno con otra dirección: [parse] devuelve `null` si no es privada.
 */
class ReachEndpoint internal constructor(val host: String, val port: Int) {

    /** La URL de una ruta del receptor, sin escribir la dirección completa a mano (el esquema lo pone el constructor de OkHttp). */
    fun url(path: String): HttpUrl = HttpUrl.Builder()
        .scheme(SCHEME)
        .host(host)
        .port(port)
        .addPathSegments(path.trimStart('/'))
        .build()

    override fun equals(other: Any?): Boolean = other is ReachEndpoint && other.host == host && other.port == port

    override fun hashCode(): Int = 31 * host.hashCode() + port

    override fun toString(): String = "ReachEndpoint($host:$port)"

    companion object {
        const val PORT = 80
        private const val SCHEME = "http"

        /** La dirección escrita (`ip` o `ip:puerto`) si es privada; el puerto del receptor es siempre el 80, también si se escribió otro. */
        fun parse(text: String): ReachEndpoint? = PrivateAddress.parse(text)?.let { ReachEndpoint(it.host, PORT) }
    }
}
