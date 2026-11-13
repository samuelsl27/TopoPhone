package es.topophone.core.model.wms

import java.util.Locale

/** Por qué no se admite lo que el usuario escribió como dirección de un servicio (S036/RF-1, RF-10). */
enum class WmsAddressProblem { EMPTY, NOT_A_URL, UNSUPPORTED_SCHEME, USER_INFO, PUBLIC_HTTP }

sealed interface WmsAddressResult {
    data class Valid(val address: WmsAddress) : WmsAddressResult

    data class Invalid(val problem: WmsAddressProblem) : WmsAddressResult
}

/**
 * La dirección de un servicio WMS ya normalizada (S036/RF-1, RF-5, RF-10, RF-15).
 *
 * [base] es `esquema://servidor[:puerto]/camino` sin parámetros, con el esquema y el servidor en minúsculas, sin el puerto de omisión y sin
 * el fragmento. [parameters] son los de la dirección que no son de una petición WMS, en su orden y con sus mayúsculas (`map=…`): se
 * conservan en cada petición. Los parámetros estándar (`SERVICE`, `REQUEST`, `VERSION`, `LAYERS`, `BBOX`…) se quitan, para poder pegar una
 * petición entera y porque las peticiones los ponen ellas. [key] es lo que distingue un servicio de otro en la biblioteca: dos direcciones
 * equivalentes tienen la misma.
 */
class WmsAddress private constructor(val base: String, val parameters: List<Pair<String, String>>) {
    val isHttps: Boolean get() = base.startsWith("https://")

    /** La dirección como se guarda: la base y sus parámetros en su orden. [parse] de este texto da la misma dirección (S036/RF-4). */
    val text: String =
        base + parameters.joinToString("&", prefix = if (parameters.isEmpty()) "" else "?") { (name, value) ->
            if (value.isEmpty()) name else "$name=$value"
        }

    val key: String =
        base + parameters.sortedWith(compareBy({ it.first.lowercase(Locale.ROOT) }, { it.second }))
            .joinToString("&", prefix = if (parameters.isEmpty()) "" else "?") { "${it.first}=${it.second}" }

    companion object {
        private const val HTTP = "http"
        private const val HTTPS = "https"
        private const val HTTP_PORT = 80
        private const val HTTPS_PORT = 443
        private const val MAX_PORT = 65_535

        /** Los parámetros que fija cada petición WMS; si vienen en la dirección, se quitan. */
        private val STANDARD_PARAMETERS = setOf(
            "service", "request", "version", "layers", "styles", "format", "bbox", "width", "height", "crs", "srs", "transparent",
            "bgcolor", "exceptions", "query_layers", "info_format", "feature_count", "i", "j", "x", "y", "updatesequence",
        )
        private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home", ".internal", ".localdomain", ".home.arpa", ".intranet")

        fun parse(input: String): WmsAddressResult {
            val text = input.trim()
            if (text.isEmpty()) return invalid(WmsAddressProblem.EMPTY)
            val schemeEnd = text.indexOf("://")
            val scheme: String
            val rest: String
            if (schemeEnd > 0 && text.substring(0, schemeEnd).all { it.isLetter() }) {
                scheme = text.substring(0, schemeEnd).lowercase(Locale.ROOT)
                rest = text.substring(schemeEnd + 3)
            } else if (schemeEnd == 0) {
                return invalid(WmsAddressProblem.NOT_A_URL)
            } else {
                scheme = HTTPS
                rest = text
            }
            if (scheme != HTTP && scheme != HTTPS) return invalid(WmsAddressProblem.UNSUPPORTED_SCHEME)
            val noFragment = rest.substringBefore('#')
            val authorityEnd = noFragment.indexOfFirst { it == '/' || it == '?' }.let { if (it < 0) noFragment.length else it }
            val authority = noFragment.substring(0, authorityEnd)
            val tail = noFragment.substring(authorityEnd)
            if ('@' in authority) return invalid(WmsAddressProblem.USER_INFO)
            val host: String
            val port: Int?
            val hostAndPort = splitHostAndPort(authority) ?: return invalid(WmsAddressProblem.NOT_A_URL)
            host = hostAndPort.first
            port = hostAndPort.second
            if (scheme == HTTP && !isPrivateHost(host)) return invalid(WmsAddressProblem.PUBLIC_HTTP)
            val path = tail.substringBefore('?')
            val query = if ('?' in tail) tail.substringAfter('?') else ""
            val shownHost = if (':' in host) "[$host]" else host
            val defaultPort = if (scheme == HTTPS) HTTPS_PORT else HTTP_PORT
            val shownPort = if (port == null || port == defaultPort) "" else ":$port"
            return WmsAddressResult.Valid(WmsAddress("$scheme://$shownHost$shownPort$path", parameters(query)))
        }

        private fun invalid(problem: WmsAddressProblem) = WmsAddressResult.Invalid(problem)

        private fun parameters(query: String): List<Pair<String, String>> = query.split('&')
            .filter { it.isNotEmpty() }
            .map { it.substringBefore('=') to if ('=' in it) it.substringAfter('=') else "" }
            .filter { it.first.isNotEmpty() && it.first.lowercase(Locale.ROOT) !in STANDARD_PARAMETERS }

        /** El servidor en minúsculas y el puerto, o null si lo escrito no es un servidor válido. */
        private fun splitHostAndPort(authority: String): Pair<String, Int?>? {
            val host: String
            val portText: String?
            if (authority.startsWith('[')) {
                val end = authority.indexOf(']')
                if (end < 0) return null
                host = authority.substring(1, end)
                val after = authority.substring(end + 1)
                portText = if (after.isEmpty()) null else after.takeIf { it.startsWith(':') }?.substring(1) ?: return null
                if (host.isEmpty() || !host.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) return null
            } else {
                if (authority.count { it == ':' } > 1) return null
                host = authority.substringBefore(':')
                portText = if (':' in authority) authority.substringAfter(':') else null
                if (host.isEmpty() || !host.all { it.isLetterOrDigit() || it == '-' || it == '.' || it == '_' }) return null
            }
            val port = portText?.let { text -> text.toIntOrNull()?.takeIf { text.all(Char::isDigit) && it in 1..MAX_PORT } ?: return null }
            return host.lowercase(Locale.ROOT) to port
        }

        /**
         * Si [host] está en una red privada o de enlace local (RF-10): `localhost`, las direcciones 10/8, 127/8, 169.254/16, 172.16/12 y
         * 192.168/16, las IPv6 `::1`, `fc00::/7` y `fe80::/10`, los nombres de una sola palabra y los de `.local`, `.lan`, `.home` y parecidos.
         */
        private fun isPrivateHost(host: String): Boolean {
            if (host == "localhost") return true
            if (':' in host) return host == "::1" || isUniqueLocal(host) || isLinkLocal(host)
            val octets = host.split('.').map { it.toIntOrNull() }
            if (octets.size == 4 && octets.all { it != null && it in 0..255 }) {
                val (a, b) = octets[0]!! to octets[1]!!
                return a == 10 || a == 127 || (a == 169 && b == 254) || (a == 172 && b in 16..31) || (a == 192 && b == 168)
            }
            return '.' !in host || LOCAL_SUFFIXES.any { host.endsWith(it) }
        }

        private fun isUniqueLocal(host: String) = host.startsWith("fc") || host.startsWith("fd")

        private fun isLinkLocal(host: String) = listOf("fe8", "fe9", "fea", "feb").any { host.startsWith(it) }
    }
}
