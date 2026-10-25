package es.topophone.core.formats.csv

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Nombre propuesto para el fichero exportado (S012/RF-19): `<proyecto>_<formato>_<AAAAMMDD-HHMM>.csv` (o con otra [extension], como el
 * informe de errores de una importación, S028/RF-24), con la hora
 * local del móvil. Lo que no vale en un nombre de fichero (`\ / : * ? " < > |` y los caracteres de control) pasa a
 * `_`; el proyecto se corta en 100 caracteres sin partir ninguno.
 */
object ExportFileName {
    private const val MAX_PROJECT_CODE_POINTS = 100
    private const val FALLBACK = "TopoPhone"
    private val INVALID = Regex("""[\\/:*?"<>|\p{Cntrl}]""")
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

    fun of(projectName: String, formatLabel: String, at: Instant, zone: ZoneId, extension: String = "csv"): String =
        "${projectPart(projectName)}_${clean(formatLabel)}_${stamp(at, zone)}.$extension"

    /** El proyecto tal como va en un nombre de fichero; también lo usan los nombres de la copia (S013/RF-4). */
    internal fun projectPart(projectName: String): String = clean(projectName).let { name ->
        if (name.codePointCount(0, name.length) <= MAX_PROJECT_CODE_POINTS) {
            name
        } else {
            name.substring(0, name.offsetByCodePoints(0, MAX_PROJECT_CODE_POINTS))
        }
    }.ifEmpty { FALLBACK }

    /** `AAAAMMDD-HHMM` en la hora local del móvil. */
    internal fun stamp(at: Instant, zone: ZoneId): String = STAMP.format(at.atZone(zone))

    /** Sin caracteres no válidos ni espacios o puntos en los extremos (Windows no los admite al final). */
    private fun clean(text: String): String = text.replace(INVALID, "_").trim { it == ' ' || it == '.' }
}
