package es.topophone.core.geodesy.catalog

/** Lo que el selector de SC ofrece antes de buscar: primero los favoritos y luego los últimos elegidos (S034/RF-6). */
data class CrsShortcutList(val favorites: List<CrsEntry>, val recents: List<CrsEntry>) {
    val isEmpty: Boolean get() = favorites.isEmpty() && recents.isEmpty()

    /** Los favoritos seguidos de los recientes, en el orden en que se muestran. */
    val all: List<CrsEntry> get() = favorites + recents
}

/**
 * Convierte los códigos EPSG que guarda el móvil en SC de la biblioteca (S034/RF-6, RF-7, RF-8). Un código que la biblioteca ya no trae (una
 * actualización lo quitó) no se muestra, pero tampoco se pierde: el móvil sigue guardándolo. Un SC que es favorito no se repite entre los
 * recientes.
 */
class CrsShortcuts(private val catalog: CrsCatalog) {

    fun resolve(favoriteEpsg: List<Int>, recentEpsg: List<Int>): CrsShortcutList {
        val favorites = favoriteEpsg.distinct().mapNotNull(catalog::crsByEpsg)
        val favoriteCodes = favorites.mapTo(HashSet()) { it.epsg }
        val recents = recentEpsg.distinct().filter { it !in favoriteCodes }.mapNotNull(catalog::crsByEpsg)
        return CrsShortcutList(favorites, recents)
    }
}
