package es.topophone.core.formats.kml

/**
 * A qué fichero del KMZ apunta un vínculo de red (`NetworkLink`) (S031/RF-2b): solo a un `.kml` del propio KMZ, con una ruta relativa al
 * `.kml` que lo contiene, sin esquema ni `..` que salga del KMZ, buscado exacto y, si no, sin distinguir mayúsculas.
 */
object KmlLinks {
    private val SCHEME = Regex("""^[A-Za-z][A-Za-z0-9+.\-]*:""")

    /** La entrada de [names] a la que apunta [href] desde la entrada [current], o `null` si no es un vínculo interno. */
    fun resolve(current: String, href: String, names: Collection<String>): String? {
        val target = href.trim().substringBefore('#')
        if (target.isEmpty() || target.startsWith("/") || target.startsWith("\\") || SCHEME.containsMatchIn(target)) return null
        val parts = ArrayList<String>()
        for (piece in current.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() } + target.split('/')) {
            when (piece) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.size - 1)
                else -> parts += piece
            }
        }
        val path = parts.joinToString("/")
        if (!path.endsWith(".kml", ignoreCase = true)) return null
        return if (path in names) path else names.firstOrNull { it.equals(path, ignoreCase = true) }
    }
}
