package es.topophone.core.model.guide

/**
 * Una dirección IP privada o de enlace local, con su puerto si lo lleva: lo único que la interfaz web del receptor carga (S043/RF-12,
 * RF-14; RNF-T4). Valen las redes 10/8, 172.16/12, 192.168/16 y 169.254/16; no valen nombres de equipo (`reach.local`), IPv6, el bucle local
 * ni direcciones públicas, ni números con ceros a la izquierda, que son ambiguos.
 */
data class PrivateAddress(val host: String, val port: Int?) {

    /** La dirección que se carga: `http://<ip>[:<puerto>]/`; sin puerto, el 80 de HTTP. */
    val url: String get() = "http://" + host + (port?.let { ":$it" } ?: "") + "/"

    companion object {
        private const val HTTP_PREFIX = "http://"
        private const val OCTETS = 4
        private const val MAX_OCTET = 255
        private const val MAX_PORT = 65_535
        private const val SECOND_OCTET_172_FROM = 16
        private const val SECOND_OCTET_172_TO = 31

        /** La dirección escrita (`ip` o `ip:puerto`), o `null` si no es una IP privada válida. */
        fun parse(text: String): PrivateAddress? {
            val trimmed = text.trim()
            val parts = trimmed.split(':')
            if (parts.size > 2) return null
            val host = parts[0]
            if (!isPrivateIpv4(host)) return null
            val port = if (parts.size == 2) parts[1].toIntOrNull()?.takeIf { it in 1..MAX_PORT } ?: return null else null
            return PrivateAddress(host, port)
        }

        /** Si una URL que pide la página se puede seguir: HTTP, sin usuario y con una IP privada o de enlace local (RF-14). */
        fun allowsUrl(url: String): Boolean {
            if (!url.startsWith(HTTP_PREFIX)) return false
            val authority = url.removePrefix(HTTP_PREFIX).substringBefore('/').substringBefore('?').substringBefore('#')
            if ('@' in authority) return false
            return parse(authority) != null
        }

        private fun isPrivateIpv4(host: String): Boolean {
            val octets = host.split('.')
            if (octets.size != OCTETS) return false
            val values = octets.map { octet ->
                if (octet.isEmpty() || octet.any { !it.isDigit() } || (octet.length > 1 && octet.startsWith("0"))) return false
                octet.toIntOrNull()?.takeIf { it <= MAX_OCTET } ?: return false
            }
            val (first, second) = values[0] to values[1]
            return first == 10 ||
                (first == 172 && second in SECOND_OCTET_172_FROM..SECOND_OCTET_172_TO) ||
                (first == 192 && second == 168) ||
                (first == 169 && second == 254)
        }
    }
}
