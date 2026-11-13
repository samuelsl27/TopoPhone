package es.topophone.core.formats.photo

import java.text.Normalizer
import java.util.Locale

/** Un punto con las fotos que se le ponen nombre: su nombre y cuántas son (S056/RF-5, S031/RF-28). */
class PhotoNameRef(val pointName: String, val photoCount: Int)

/**
 * El nombre de cada foto en un ZIP de fotos o en un KMZ: `<punto>_<n>.jpg`, con el nombre del punto saneado y `<n>` la posición de la foto en
 * su punto, desde 1 (S056/RF-5, S031/RF-28). El nombre del punto solo lleva letras sin acentos, cifras, `_` y `-` (lo demás pasa a `_`) y
 * `punto` si está en blanco; si un nombre ya existe —sin distinguir mayúsculas, como en Windows— se distingue con `_2`, `_3`… al final del nombre
 * del punto, sin repetir ninguno de los nombres de fotos ya dados.
 */
object PhotoArchiveNames {
    const val EXTENSION = "jpg"
    private const val EMPTY_NAME = "punto"

    /** El nombre de [pointName] limpio para un fichero: sin acentos, con `_` en lo que no es letra, cifra o `-`, y `punto` si está en blanco. */
    fun sanitize(pointName: String): String {
        if (pointName.isBlank()) return EMPTY_NAME
        val plain = Normalizer.normalize(pointName.trim(), Normalizer.Form.NFD)
        val out = StringBuilder(plain.length)
        for (c in plain) {
            when {
                c.code in COMBINING_MARKS -> Unit
                c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-' -> out.append(c)
                else -> out.append('_')
            }
        }
        return out.toString()
    }

    /** Los nombres de las fotos de cada punto de [points], en el mismo orden, sin repetir ninguno. */
    fun of(points: List<PhotoNameRef>): List<List<String>> {
        val taken = HashSet<String>()
        return points.map { ref ->
            val base = sanitize(ref.pointName)
            var candidate = base
            var n = 1
            while (true) {
                val names = (1..ref.photoCount).map { "${candidate}_$it.$EXTENSION" }
                if (names.none { it.lowercase(Locale.ROOT) in taken }) {
                    names.forEach { taken += it.lowercase(Locale.ROOT) }
                    break
                }
                candidate = "${base}_${++n}"
            }
            (1..ref.photoCount).map { "${candidate}_$it.$EXTENSION" }
        }
    }

    /** Las marcas combinables que deja la descomposición NFD tras una letra con acento o una eñe. */
    private val COMBINING_MARKS = 0x0300..0x036F
}
