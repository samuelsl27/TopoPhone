package es.topophone.core.formats.shp

/** Un fichero que el usuario eligió en el selector: su nombre, sus primeros bytes y su tamaño (S030/RF-1, RF-2). */
class ShpPick(val name: String, val head: ByteArray, val sizeBytes: Long) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
}

/** Por qué no se abre la pantalla de importación de un Shapefile (S030/RF-3, RF-4, RF-6). */
enum class ShpRejection {
    /** No hay ficheros, o el único está vacío. */
    EMPTY,

    /** Más de un ZIP, o un ZIP junto a otros ficheros (RF-4). */
    MIXED_SELECTION,

    /** Un fichero que no es un ZIP ni un `.shp` (RF-3). */
    NOT_SHAPEFILE,

    /** Ficheros de un Shapefile pero ninguno es el `.shp` (RF-3). */
    NO_SHP,

    /** Los ficheros suman más de 100 MB (RF-6). */
    TOO_LARGE,
}

/** Qué hacer con lo que eligió el usuario (S030/RF-2 a RF-6). */
sealed interface ShpPickResult {
    /** Un solo ZIP, que hay que abrir y recorrer. */
    data class SingleZip(val pick: ShpPick) : ShpPickResult

    /** Ficheros sueltos, de los que al menos uno es un `.shp`. */
    data class Loose(val picks: List<ShpPick>) : ShpPickResult

    data class Rejected(val reason: ShpRejection) : ShpPickResult
}

/** Un fichero suelto o la entrada de un ZIP: su ruta, sus primeros bytes y su tamaño. */
class ShpEntry(val path: String, val head: ByteArray, val sizeBytes: Long) {
    val name: String get() = path.substringAfterLast('/')
    val directory: String get() = if ('/' in path) path.substringBeforeLast('/') else ""
    val stem: String get() = if ('.' in name) name.substringBeforeLast('.') else name
    val extension: String get() = if ('.' in name) name.substringAfterLast('.').lowercase() else ""
}

/** Una capa: su `.shp` y los ficheros que se asocian a él por nombre (S030/RF-2). */
class ShpLayerFiles(
    /** El nombre del fichero sin extensión y, si otra capa se llama igual, con su carpeta (RF-7). */
    val name: String,
    val shp: ShpEntry,
    val shx: ShpEntry? = null,
    val dbf: ShpEntry? = null,
    val prj: ShpEntry? = null,
    val cpg: ShpEntry? = null,
)

/** Las capas de un conjunto de ficheros, los que sobran y los nombres que no se pueden usar (S030/RF-2, RF-5). */
class ShpGrouping(
    val layers: List<ShpLayerFiles>,
    /** Los `.shx`, `.dbf`, `.prj` y `.cpg` sin un `.shp` de su mismo nombre, por su nombre (RF-5). */
    val orphans: List<String>,
    /** Las entradas con una ruta absoluta o que sale de la carpeta, que no se leen (zip-slip). */
    val dropped: List<String>,
)

/**
 * De lo que elige el usuario a las capas de un Shapefile (S030/RF-2 a RF-7), sin tocar el disco: [check] decide si se puede seguir con
 * lo elegido y [group] reparte los ficheros en capas por su nombre, sin distinguir mayúsculas y, en un ZIP, dentro de la misma carpeta.
 */
object ShpSelection {
    private val SIDE_EXTENSIONS = setOf("shx", "dbf", "prj", "cpg")
    private val WINDOWS_DRIVE = Regex("^[A-Za-z]:")

    /** Lo que se hace con [picks], los ficheros que eligió el usuario: abrir el ZIP, mirar los sueltos o rechazarlo con su motivo. */
    fun check(picks: List<ShpPick>): ShpPickResult {
        if (picks.isEmpty() || picks.all { it.sizeBytes == 0L }) return ShpPickResult.Rejected(ShpRejection.EMPTY)
        val zips = picks.filter { ShpFileKind.isZip(it.head) }
        if (zips.size > 1 || (zips.isNotEmpty() && picks.size > 1)) return ShpPickResult.Rejected(ShpRejection.MIXED_SELECTION)
        if (picks.sumOf { it.sizeBytes } > ShpFileKind.MAX_PICKED_BYTES) return ShpPickResult.Rejected(ShpRejection.TOO_LARGE)
        if (zips.size == 1) return ShpPickResult.SingleZip(zips.single())
        if (picks.none { isShp(ShpEntry(it.name, it.head, it.sizeBytes)) }) {
            return ShpPickResult.Rejected(
                if (picks.all {
                        it.extension in SIDE_EXTENSIONS
                    }
                ) {
                    ShpRejection.NO_SHP
                } else {
                    ShpRejection.NOT_SHAPEFILE
                },
            )
        }
        return ShpPickResult.Loose(picks)
    }

    /** Las capas de [entries]: cada `.shp` por su contenido con los ficheros de su nombre, y los huérfanos y los descartados. */
    fun group(entries: List<ShpEntry>): ShpGrouping {
        val dropped = ArrayList<String>()
        val kept = ArrayList<ShpEntry>()
        for (entry in entries) {
            val path = entry.path.replace('\\', '/')
            when {
                unsafe(path) -> dropped += entry.path
                ignorable(path) -> Unit
                path == entry.path -> kept += entry
                else -> kept += ShpEntry(path, entry.head, entry.sizeBytes)
            }
        }
        val shps = LinkedHashMap<String, ShpEntry>()
        for (entry in kept) if (isShp(entry)) shps.getOrPut(key(entry)) { entry }
        val sameStem = shps.values.groupingBy { it.stem.lowercase() }.eachCount()
        val layers = shps.map { (key, shp) ->
            fun side(extension: String) = kept.firstOrNull { it.extension == extension && key(it) == key }
            val name = if (sameStem.getValue(shp.stem.lowercase()) > 1) pathWithoutExtension(shp) else shp.stem
            ShpLayerFiles(name, shp, side("shx"), side("dbf"), side("prj"), side("cpg"))
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        val orphans = kept.filter { it.extension in SIDE_EXTENSIONS && key(it) !in shps }.map { it.name }.sorted()
        return ShpGrouping(layers, orphans, dropped.sorted())
    }

    /** El `.shp` se reconoce por su cabecera; el `.shx`, que la tiene igual, por su extensión. */
    private fun isShp(entry: ShpEntry): Boolean =
        entry.extension !in SIDE_EXTENSIONS && ShpFileKind.isShapeHeader(entry.head, entry.sizeBytes)

    private fun key(entry: ShpEntry) = "${entry.directory.lowercase()}/${entry.stem.lowercase()}"

    private fun pathWithoutExtension(entry: ShpEntry) = if (entry.directory.isEmpty()) entry.stem else "${entry.directory}/${entry.stem}"

    /** Una ruta absoluta, con letra de unidad o que sube de carpeta: no se escribe fuera de la carpeta temporal. */
    private fun unsafe(path: String): Boolean = path.startsWith("/") || WINDOWS_DRIVE.containsMatchIn(path) || ".." in path.split('/')

    /** Lo que añaden los ZIP de macOS: carpetas `__MACOSX` y ficheros `._nombre`. */
    private fun ignorable(path: String): Boolean = path.split('/').any { it == "__MACOSX" || it.startsWith("._") }
}
