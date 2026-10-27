package es.topophone.core.formats.alignment

import es.topophone.core.formats.csv.DecimalDetector
import es.topophone.core.formats.csv.HeaderDetector
import es.topophone.core.model.point.DelimitedRow
import es.topophone.core.model.point.ImportDecimal
import es.topophone.core.model.point.ImportNumber
import es.topophone.core.model.point.ImportSeparator
import java.text.Normalizer

/** El papel de una columna de un CSV de rasante (S049/RF-9). */
enum class ProfileRole { CHAINAGE, ELEVATION, KV, IGNORE }

/** Lo que le falta o le sobra a una asignación de columnas de la rasante (S049/RF-9). */
enum class ProfileMappingProblem { MISSING_CHAINAGE, MISSING_ELEVATION, REPEATED_ROLE }

/** Qué papel tiene cada columna del CSV, por su posición. El PK y la cota son obligatorios y el Kv es opcional (RF-9). */
data class ProfileColumns(val roles: List<ProfileRole>) {
    fun roleOf(index: Int): ProfileRole = roles.getOrNull(index) ?: ProfileRole.IGNORE

    fun indexOf(role: ProfileRole): Int? = roles.indexOf(role).takeIf { it >= 0 }

    fun withRole(index: Int, role: ProfileRole): ProfileColumns =
        ProfileColumns(roles.mapIndexed { i, current -> if (i == index) role else current })

    /** Qué impide leer con esta asignación; null si vale. Un papel no se puede repetir. */
    val problem: ProfileMappingProblem?
        get() {
            val counted = roles.filter { it != ProfileRole.IGNORE }.groupingBy { it }.eachCount()
            return when {
                counted.any { it.value > 1 } -> ProfileMappingProblem.REPEATED_ROLE
                ProfileRole.CHAINAGE !in counted -> ProfileMappingProblem.MISSING_CHAINAGE
                ProfileRole.ELEVATION !in counted -> ProfileMappingProblem.MISSING_ELEVATION
                else -> null
            }
        }
}

/** Una fila del CSV de rasante ya leída: PK y cota tal como están en el fichero (sin convertir) y el parámetro del acuerdo si lo trae. */
data class ProfileCsvRow(val line: Int, val chainage: Double, val elevation: Double, val kv: Double?)

/** Por qué no se pudo leer una fila (RF-9, RF-10): la línea del fichero y el papel de la celda. */
data class ProfileCsvError(val line: Int, val role: ProfileRole, val reason: Reason) {
    enum class Reason { MISSING, NOT_A_NUMBER }
}

/** Las filas leídas y los errores; con algún error la rasante no se importa y se dice la línea. */
class ProfileCsvResult(val rows: List<ProfileCsvRow>, val errors: List<ProfileCsvError>)

/**
 * La rasante de un CSV de PK, cota y, opcionalmente, parámetro del acuerdo vertical (S049/RF-9). Las opciones de codificación,
 * separador y signo decimal son las de S028 (`TextDecoding`, `DelimitedReader`, `DecimalDetector`): este lector solo asigna columnas y
 * convierte las celdas.
 */
object ProfileCsvReader {
    private val CHAINAGE_NAMES = setOf("pk", "p.k.", "p k", "estacion", "station", "chainage", "abscisa", "sta")
    private val ELEVATION_NAMES = setOf("cota", "z", "elev", "elevacion", "elevation", "altura", "h", "altitud")
    private val KV_NAMES = setOf("kv", "k", "parametro", "param", "parametro kv", "parameter")

    /** Es cabecera si ninguna de las celdas del PK y la cota (las dos primeras) es un número. */
    fun hasHeader(firstRow: List<String>): Boolean = HeaderDetector.isHeader(firstRow, listOf(0, 1))

    /** El signo decimal más probable; mira también la primera columna, que aquí es un número (el PK). */
    fun detectDecimal(rows: Iterable<DelimitedRow>, separator: ImportSeparator): ImportDecimal =
        DecimalDetector.detect(rows.map { DelimitedRow(it.line, listOf("") + it.cells) }, separator)

    /** La asignación por los nombres de la cabecera; sin cabecera o sin reconocerlos, PK, cota y Kv por este orden. */
    fun suggest(firstRow: List<String>, hasHeader: Boolean): ProfileColumns {
        val byPosition = listOf(ProfileRole.CHAINAGE, ProfileRole.ELEVATION, ProfileRole.KV)
        val defaults = firstRow.indices.map { byPosition.getOrElse(it) { ProfileRole.IGNORE } }
        if (!hasHeader) return ProfileColumns(defaults)
        val named = firstRow.map { cell ->
            when (normalize(cell)) {
                in CHAINAGE_NAMES -> ProfileRole.CHAINAGE
                in ELEVATION_NAMES -> ProfileRole.ELEVATION
                in KV_NAMES -> ProfileRole.KV
                else -> ProfileRole.IGNORE
            }
        }
        val usable = ProfileColumns(named).problem == null
        return ProfileColumns(if (usable) named else defaults)
    }

    /**
     * Convierte las filas de [rows] (sin la cabecera, que ya se quitó) con la asignación [columns] y el signo decimal [decimal]. Una celda
     * de Kv vacía es "sin acuerdo"; una de PK o de cota vacía, o cualquier celda que no sea un número, es un error de esa línea.
     */
    fun parse(rows: Sequence<DelimitedRow>, columns: ProfileColumns, decimal: ImportDecimal): ProfileCsvResult {
        val chainageColumn = columns.indexOf(ProfileRole.CHAINAGE)
        val elevationColumn = columns.indexOf(ProfileRole.ELEVATION)
        require(columns.problem == null && chainageColumn != null && elevationColumn != null) { "Asignación de columnas no válida" }
        val kvColumn = columns.indexOf(ProfileRole.KV)
        val parsed = mutableListOf<ProfileCsvRow>()
        val errors = mutableListOf<ProfileCsvError>()
        for (row in rows) {
            val chainage = number(row, chainageColumn, ProfileRole.CHAINAGE, decimal, errors)
            val elevation = number(row, elevationColumn, ProfileRole.ELEVATION, decimal, errors)
            var kv: Double? = null
            var kvFailed = false
            if (kvColumn != null && row.cells.getOrNull(kvColumn)?.isNotBlank() == true) {
                kv = number(row, kvColumn, ProfileRole.KV, decimal, errors)
                kvFailed = kv == null
            }
            if (chainage != null && elevation != null && !kvFailed) parsed += ProfileCsvRow(row.line, chainage, elevation, kv)
        }
        return ProfileCsvResult(parsed, errors)
    }

    private fun number(
        row: DelimitedRow,
        column: Int,
        role: ProfileRole,
        decimal: ImportDecimal,
        errors: MutableList<ProfileCsvError>,
    ): Double? {
        val cell = row.cells.getOrNull(column)
        if (cell == null || cell.isBlank()) {
            errors += ProfileCsvError(row.line, role, ProfileCsvError.Reason.MISSING)
            return null
        }
        val value = ImportNumber.parse(cell, decimal)
        if (value == null) errors += ProfileCsvError(row.line, role, ProfileCsvError.Reason.NOT_A_NUMBER)
        return value
    }

    private fun normalize(cell: String): String =
        Normalizer.normalize(cell.trim().lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
}
