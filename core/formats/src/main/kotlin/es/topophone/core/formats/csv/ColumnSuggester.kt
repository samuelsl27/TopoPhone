package es.topophone.core.formats.csv

import es.topophone.core.model.point.ColumnMapping
import es.topophone.core.model.point.ColumnRole
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.ImportCoordinates
import java.text.Normalizer

/**
 * La asignación de columnas que se propone al abrir el fichero (S028/RF-10): por los nombres de la cabecera, también los de las
 * columnas del CSV de S012, y si no hay cabecera o no se reconocen las coordenadas, por el orden PENZD.
 */
object ColumnSuggester {
    private val names = setOf("name", "nombre", "point", "punto", "pointname", "nombrepunto", "pointid", "id", "numero", "num")
    private val east = setOf("easting", "east", "este", "x", "e")
    private val north = setOf("northing", "north", "norte", "y", "n")
    private val latitude = setOf("latitude", "latitud", "lat")
    private val longitude = setOf("longitude", "longitud", "lon", "long", "lng")
    private val height = setOf("elevation", "elev", "height", "altura", "altitud", "alt", "cota", "z", "h", "orthometricheight")
    private val ellipsoidal = setOf("ellipsoidalheight", "alturaelipsoidal", "ellipsoidal", "elipsoidal", "ellheight")
    private val description =
        setOf("description", "descripcion", "desc", "code", "codigo", "notas", "nota", "notes", "comment", "comentario")

    private val inBrackets = Regex("""\([^)]*\)|\[[^]]*]""")
    private val notAlphanumeric = Regex("""[^a-z0-9]""")

    /**
     * La asignación para [columns] columnas. [header] es la primera fila si se toma por cabecera, o null. Una cabecera solo se
     * acepta si da las dos coordenadas del sistema elegido en [coordinates]; sin nombre se acepta igualmente, y la asignación
     * dirá entonces que le falta (RF-11).
     */
    fun suggest(header: List<String>?, columns: Int, coordinates: ImportCoordinates): ColumnMapping {
        val fallback = ColumnMapping.penzd(columns)
        if (header == null || columns <= 0) return fallback
        val keys = List(columns) { normalize(header.getOrNull(it).orEmpty()) }
        val geographic = coordinates.form == EntryForm.GEOGRAPHIC
        val heightSets = if (geographic && coordinates.heightKind == HeightKind.ELLIPSOIDAL) listOf(ellipsoidal, height) else listOf(height)

        val roles = MutableList(columns) { ColumnRole.IGNORE }
        fun assign(role: ColumnRole, vararg sets: Set<String>) {
            if (role in roles) return
            val index = sets.firstNotNullOfOrNull { set -> keys.indices.firstOrNull { roles[it] == ColumnRole.IGNORE && keys[it] in set } }
            if (index != null) roles[index] = role
        }
        assign(ColumnRole.NAME, names)
        assign(ColumnRole.FIRST, if (geographic) latitude else east)
        assign(ColumnRole.SECOND, if (geographic) longitude else north)
        assign(ColumnRole.HEIGHT, *heightSets.toTypedArray())
        assign(ColumnRole.DESCRIPTION, description)

        return if (ColumnRole.FIRST in roles && ColumnRole.SECOND in roles) ColumnMapping(roles) else fallback
    }

    /** Minúsculas, sin acentos, sin lo que va entre paréntesis o corchetes (las unidades) y sin espacios ni signos. */
    private fun normalize(cell: String): String {
        val plain = Normalizer.normalize(cell.lowercase(), Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "")
        return plain.replace(inBrackets, "").replace(notAlphanumeric, "")
    }
}
