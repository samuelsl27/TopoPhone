package es.topophone.core.formats.section

import es.topophone.core.formats.csv.DecimalDetector
import es.topophone.core.formats.csv.HeaderDetector
import es.topophone.core.model.alignment.section.CrossSection
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.section.SectionCode
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.alignment.section.SectionPoint
import es.topophone.core.model.alignment.section.SuperelevationRow
import es.topophone.core.model.point.DelimitedRow
import es.topophone.core.model.point.ImportDecimal
import es.topophone.core.model.point.ImportNumber
import es.topophone.core.model.point.ImportSeparator
import java.text.Normalizer

/** El papel de una columna de un CSV de secciones (S050/RF-4). */
enum class SectionRole { CHAINAGE, CODE, OFFSET, HEIGHT, KIND, IGNORE }

/** Lo que le falta o le sobra a una asignación de columnas de secciones (S050/RF-4). */
enum class SectionMappingProblem { MISSING_CHAINAGE, MISSING_OFFSET, MISSING_HEIGHT, REPEATED_ROLE }

/** Qué papel tiene cada columna del CSV. El PK, el desplazamiento y la cota son obligatorios; el código y el tipo, opcionales (RF-4). */
data class SectionColumns(val roles: List<SectionRole>) {
    fun roleOf(index: Int): SectionRole = roles.getOrNull(index) ?: SectionRole.IGNORE

    fun indexOf(role: SectionRole): Int? = roles.indexOf(role).takeIf { it >= 0 }

    fun withRole(index: Int, role: SectionRole): SectionColumns =
        SectionColumns(roles.mapIndexed { i, current -> if (i == index) role else current })

    /** Qué impide leer con esta asignación; null si vale. Un papel no se puede repetir. */
    val problem: SectionMappingProblem?
        get() {
            val counted = roles.filter { it != SectionRole.IGNORE }.groupingBy { it }.eachCount()
            return when {
                counted.any { it.value > 1 } -> SectionMappingProblem.REPEATED_ROLE
                SectionRole.CHAINAGE !in counted -> SectionMappingProblem.MISSING_CHAINAGE
                SectionRole.OFFSET !in counted -> SectionMappingProblem.MISSING_OFFSET
                SectionRole.HEIGHT !in counted -> SectionMappingProblem.MISSING_HEIGHT
                else -> null
            }
        }
}

/** Una fila ya leída: valores del fichero sin convertir; [code] es null si no hay columna o la celda está vacía. */
data class SectionCsvRow(
    val line: Int,
    val chainage: Double,
    val code: String?,
    val offset: Double,
    val height: Double,
    val kind: SectionKind,
)

/** Por qué no se pudo leer una celda (S050/RF-4, RF-6): la línea del fichero y el papel de la celda. */
data class SectionCsvError<R>(val line: Int, val role: R, val reason: Reason) {
    enum class Reason { MISSING, NOT_A_NUMBER, UNKNOWN_KIND }
}

/** Las filas leídas y los errores; con algún error no se importa y se dice la línea. */
class SectionCsvResult(val rows: List<SectionCsvRow>, val errors: List<SectionCsvError<SectionRole>>)

/**
 * Las secciones de un CSV de PK, código, desplazamiento, cota y tipo (S050/RF-4). Las opciones de codificación, separador y signo
 * decimal son las de S028; este lector solo asigna columnas, convierte las celdas y agrupa las filas en secciones.
 */
object SectionCsvReader {
    private val CHAINAGE_NAMES = setOf("pk", "p.k.", "p k", "estacion", "station", "chainage", "abscisa", "sta")
    private val CODE_NAMES = setOf("codigo", "code", "cod", "nombre", "name", "punto", "point")
    private val OFFSET_NAMES = setOf("desplazamiento", "offset", "distancia", "dist", "d", "desp")
    private val HEIGHT_NAMES = setOf("cota", "z", "elev", "elevacion", "elevation", "altura", "h", "altitud", "dz", "diferencia")
    private val KIND_NAMES = setOf("tipo", "kind", "type", "clase", "estado", "state")
    private val GROUND_NAMES = setOf("terreno", "ground", "existing", "natural", "t")
    private val DESIGN_NAMES = setOf("diseno", "design", "proposed", "proyecto", "d", "")

    /** Es cabecera si ninguna de las celdas del PK y el desplazamiento (la primera y la tercera) es un número. */
    fun hasHeader(firstRow: List<String>): Boolean = HeaderDetector.isHeader(firstRow, listOf(0, 2))

    /** El signo decimal más probable. */
    fun detectDecimal(rows: Iterable<DelimitedRow>, separator: ImportSeparator): ImportDecimal =
        DecimalDetector.detect(rows.toList(), separator)

    /** La asignación por los nombres de la cabecera; sin cabecera o sin reconocerlos, PK, código, desplazamiento, cota y tipo en ese orden. */
    fun suggest(firstRow: List<String>, hasHeader: Boolean): SectionColumns {
        val byPosition = listOf(SectionRole.CHAINAGE, SectionRole.CODE, SectionRole.OFFSET, SectionRole.HEIGHT, SectionRole.KIND)
        val defaults = firstRow.indices.map { byPosition.getOrElse(it) { SectionRole.IGNORE } }
        if (!hasHeader) return SectionColumns(defaults)
        val named = firstRow.map { cell ->
            when (normalize(cell)) {
                in CHAINAGE_NAMES -> SectionRole.CHAINAGE
                in CODE_NAMES -> SectionRole.CODE
                in OFFSET_NAMES -> SectionRole.OFFSET
                in HEIGHT_NAMES -> SectionRole.HEIGHT
                in KIND_NAMES -> SectionRole.KIND
                else -> SectionRole.IGNORE
            }
        }
        return SectionColumns(if (SectionColumns(named).problem == null) named else defaults)
    }

    /**
     * Convierte las filas de [rows] (sin la cabecera) con la asignación [columns] y el signo decimal [decimal]. Una celda de código o de
     * tipo vacía es "sin código" y "de diseño"; una de PK, desplazamiento o cota vacía, o un número mal escrito, o un tipo que no se
     * reconoce, es un error de esa línea.
     */
    fun parse(rows: Sequence<DelimitedRow>, columns: SectionColumns, decimal: ImportDecimal): SectionCsvResult {
        val chainageColumn = columns.indexOf(SectionRole.CHAINAGE)
        val offsetColumn = columns.indexOf(SectionRole.OFFSET)
        val heightColumn = columns.indexOf(SectionRole.HEIGHT)
        require(columns.problem == null && chainageColumn != null && offsetColumn != null && heightColumn != null) {
            "Asignación de columnas no válida"
        }
        val codeColumn = columns.indexOf(SectionRole.CODE)
        val kindColumn = columns.indexOf(SectionRole.KIND)
        val parsed = mutableListOf<SectionCsvRow>()
        val errors = mutableListOf<SectionCsvError<SectionRole>>()
        for (row in rows) {
            val chainage = number(row, chainageColumn, SectionRole.CHAINAGE, decimal, errors)
            val offset = number(row, offsetColumn, SectionRole.OFFSET, decimal, errors)
            val height = number(row, heightColumn, SectionRole.HEIGHT, decimal, errors)
            val code = codeColumn?.let { row.cells.getOrNull(it)?.trim() }?.takeIf { it.isNotEmpty() }
            val kind = kindOf(row, kindColumn, errors)
            if (chainage != null && offset != null && height != null && kind != null) {
                parsed += SectionCsvRow(row.line, chainage, code, offset, height, kind)
            }
        }
        return SectionCsvResult(parsed, errors)
    }

    /**
     * Las secciones de [rows]: una por tipo y PK, con los puntos por desplazamiento creciente. Los puntos sin código se llaman P1, P2…
     * por su posición (RF-4, RNF-T5). La cota es del tipo [heightKind] que el usuario eligió para el fichero.
     */
    fun toSections(rows: List<SectionCsvRow>, heightKind: HeightKind): List<CrossSection> =
        rows.groupBy { it.kind to it.chainage }.map { (key, list) ->
            val sorted = list.sortedBy { it.offset }
            val points = sorted.mapIndexed { i, r -> SectionPoint(r.code ?: SectionCode.generated(i), r.offset, r.height) }
            CrossSection(key.first, key.second, heightKind, points)
        }.sortedWith(compareBy({ it.kind }, { it.chainage }))

    private fun kindOf(row: DelimitedRow, column: Int?, errors: MutableList<SectionCsvError<SectionRole>>): SectionKind? {
        if (column == null) return SectionKind.DESIGN
        return when (normalize(row.cells.getOrNull(column).orEmpty())) {
            in GROUND_NAMES -> SectionKind.GROUND
            in DESIGN_NAMES -> SectionKind.DESIGN
            else -> {
                errors += SectionCsvError(row.line, SectionRole.KIND, SectionCsvError.Reason.UNKNOWN_KIND)
                null
            }
        }
    }

    private fun number(
        row: DelimitedRow,
        column: Int,
        role: SectionRole,
        decimal: ImportDecimal,
        errors: MutableList<SectionCsvError<SectionRole>>,
    ): Double? = parseNumber(row, column, role, decimal, errors)

    internal fun <R> parseNumber(
        row: DelimitedRow,
        column: Int,
        role: R,
        decimal: ImportDecimal,
        errors: MutableList<SectionCsvError<R>>,
    ): Double? {
        val cell = row.cells.getOrNull(column)
        if (cell == null || cell.isBlank()) {
            errors += SectionCsvError(row.line, role, SectionCsvError.Reason.MISSING)
            return null
        }
        val value = ImportNumber.parse(cell, decimal)
        if (value == null) errors += SectionCsvError(row.line, role, SectionCsvError.Reason.NOT_A_NUMBER)
        return value
    }

    internal fun normalize(cell: String): String =
        Normalizer.normalize(cell.trim().lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
}

/** El papel de una columna de un CSV de peralte (S050/RF-6). */
enum class SuperelevationRole { CHAINAGE, LEFT, RIGHT, IGNORE }

/** Qué papel tiene cada columna del CSV de peralte: las tres son obligatorias. */
data class SuperelevationColumns(val roles: List<SuperelevationRole>) {
    fun indexOf(role: SuperelevationRole): Int? = roles.indexOf(role).takeIf { it >= 0 }

    /** True si las tres columnas están asignadas, cada una una vez. */
    val isValid: Boolean
        get() {
            val counted = roles.filter { it != SuperelevationRole.IGNORE }.groupingBy { it }.eachCount()
            return counted.size == 3 && counted.all { it.value == 1 }
        }
}

class SuperelevationCsvResult(val rows: List<SuperelevationRow>, val errors: List<SectionCsvError<SuperelevationRole>>)

/** La tabla de peralte de un CSV de PK y pendientes izquierda y derecha (S050/RF-6). */
object SuperelevationCsvReader {
    private val CHAINAGE_NAMES = setOf("pk", "p.k.", "p k", "estacion", "station", "chainage", "abscisa", "sta")
    private val LEFT_NAMES = setOf("izquierda", "izq", "izq.", "left", "l", "pendiente izquierda", "pend izq")
    private val RIGHT_NAMES = setOf("derecha", "der", "der.", "right", "r", "pendiente derecha", "pend der")

    fun hasHeader(firstRow: List<String>): Boolean = HeaderDetector.isHeader(firstRow, listOf(0, 1))

    fun suggest(firstRow: List<String>, hasHeader: Boolean): SuperelevationColumns {
        val byPosition = listOf(SuperelevationRole.CHAINAGE, SuperelevationRole.LEFT, SuperelevationRole.RIGHT)
        val defaults = firstRow.indices.map { byPosition.getOrElse(it) { SuperelevationRole.IGNORE } }
        if (!hasHeader) return SuperelevationColumns(defaults)
        val named = firstRow.map { cell ->
            when (SectionCsvReader.normalize(cell)) {
                in CHAINAGE_NAMES -> SuperelevationRole.CHAINAGE
                in LEFT_NAMES -> SuperelevationRole.LEFT
                in RIGHT_NAMES -> SuperelevationRole.RIGHT
                else -> SuperelevationRole.IGNORE
            }
        }
        return SuperelevationColumns(if (SuperelevationColumns(named).isValid) named else defaults)
    }

    /** Convierte las filas. Las pendientes del fichero van en porcentaje salvo que [percent] sea false (entonces son fracciones). */
    fun parse(
        rows: Sequence<DelimitedRow>,
        columns: SuperelevationColumns,
        decimal: ImportDecimal,
        percent: Boolean = true,
    ): SuperelevationCsvResult {
        require(columns.isValid) { "Asignación de columnas no válida" }
        val chainageColumn = columns.indexOf(SuperelevationRole.CHAINAGE)!!
        val leftColumn = columns.indexOf(SuperelevationRole.LEFT)!!
        val rightColumn = columns.indexOf(SuperelevationRole.RIGHT)!!
        val scale = if (percent) 0.01 else 1.0
        val parsed = mutableListOf<SuperelevationRow>()
        val errors = mutableListOf<SectionCsvError<SuperelevationRole>>()
        for (row in rows) {
            val chainage = SectionCsvReader.parseNumber(row, chainageColumn, SuperelevationRole.CHAINAGE, decimal, errors)
            val left = SectionCsvReader.parseNumber(row, leftColumn, SuperelevationRole.LEFT, decimal, errors)
            val right = SectionCsvReader.parseNumber(row, rightColumn, SuperelevationRole.RIGHT, decimal, errors)
            if (chainage != null && left != null && right != null) parsed += SuperelevationRow(chainage, left * scale, right * scale)
        }
        return SuperelevationCsvResult(parsed, errors)
    }
}
