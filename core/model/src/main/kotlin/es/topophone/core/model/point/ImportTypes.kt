package es.topophone.core.model.point

import es.topophone.core.model.project.LinearUnit

/** La codificación del fichero que se importa: UTF-8 (con o sin BOM) o Windows-1252 (S028/RF-4). */
enum class ImportEncoding { UTF8, WINDOWS_1252 }

/**
 * El separador de columnas del fichero (S028/RF-5). Los espacios se juntan: varios seguidos valen un solo separador, porque los TXT
 * de las estaciones totales alinean las columnas con ellos.
 */
enum class ImportSeparator(val char: Char, val mergesRuns: Boolean = false) {
    COMMA(','),
    SEMICOLON(';'),
    TAB('\t'),
    SPACES(' ', mergesRuns = true),
}

/** El signo decimal de los números del fichero (S028/RF-7). */
enum class ImportDecimal(val char: Char) {
    POINT('.'),
    COMMA(','),
}

/**
 * El papel de una columna del fichero (S028/RF-10, RF-11): el nombre, la primera coordenada (el Este o la latitud), la segunda (el
 * Norte o la longitud), la altura, la descripción o ninguno.
 */
enum class ColumnRole { NAME, FIRST, SECOND, HEIGHT, DESCRIPTION, IGNORE }

/** Lo que le falta o le sobra a una asignación de columnas para poder importar (S028/RF-11). */
enum class MappingProblem { MISSING_NAME, MISSING_FIRST, MISSING_SECOND, REPEATED_ROLE }

/** Qué papel tiene cada columna del fichero, por su posición (S028/RF-10, RF-11). */
data class ColumnMapping(val roles: List<ColumnRole>) {
    /** El papel de la columna [index]; sin asignar si el fichero no tiene tantas columnas. */
    fun roleOf(index: Int): ColumnRole = roles.getOrNull(index) ?: ColumnRole.IGNORE

    /** La posición de la columna con ese papel, o null si ninguna lo tiene. */
    fun indexOf(role: ColumnRole): Int? = roles.indexOf(role).takeIf { it >= 0 }

    /** Una asignación igual con otro papel en la columna [index]; la original no cambia. */
    fun withRole(index: Int, role: ColumnRole): ColumnMapping =
        ColumnMapping(roles.mapIndexed { i, current -> if (i == index) role else current })

    /** Qué impide importar con esta asignación; null si vale. Los papeles se pueden dejar sin asignar, pero no repetir (RF-11). */
    val problem: MappingProblem?
        get() {
            val counted = roles.filter { it != ColumnRole.IGNORE }.groupingBy { it }.eachCount()
            return when {
                counted.any { it.value > 1 } -> MappingProblem.REPEATED_ROLE
                ColumnRole.NAME !in counted -> MappingProblem.MISSING_NAME
                ColumnRole.FIRST !in counted -> MappingProblem.MISSING_FIRST
                ColumnRole.SECOND !in counted -> MappingProblem.MISSING_SECOND
                else -> null
            }
        }

    val isValid: Boolean get() = problem == null

    companion object {
        private val PENZD = listOf(ColumnRole.NAME, ColumnRole.FIRST, ColumnRole.SECOND, ColumnRole.HEIGHT, ColumnRole.DESCRIPTION)
        private val PNEZD = listOf(ColumnRole.NAME, ColumnRole.SECOND, ColumnRole.FIRST, ColumnRole.HEIGHT, ColumnRole.DESCRIPTION)

        /** Nombre, Este (o latitud), Norte (o longitud), altura y descripción, para [columns] columnas; las que sobran, sin asignar. */
        fun penzd(columns: Int) = ColumnMapping(fill(PENZD, columns))

        /** Como [penzd] con el Norte (o la longitud) antes que el Este (o la latitud). */
        fun pnezd(columns: Int) = ColumnMapping(fill(PNEZD, columns))

        private fun fill(order: List<ColumnRole>, columns: Int) = List(columns.coerceAtLeast(0)) {
            order.getOrElse(it) { ColumnRole.IGNORE }
        }
    }
}

/**
 * En qué sistema vienen las coordenadas del fichero y qué altura traen (S028/RF-12, RF-13): en el SC del proyecto, con la altura
 * en su sistema de alturas, o en geográficas, con la altura de ese sistema o elipsoidal.
 */
data class ImportCoordinates(val form: EntryForm = EntryForm.PROJECT_CRS, val heightKind: HeightKind = HeightKind.PROJECT) {
    /** La altura elipsoidal solo se escribe en geográficas, y un proyecto en ED50 solo admite altitud (S011/RF-17). */
    fun isAllowed(ed50: Boolean): Boolean = when {
        heightKind == HeightKind.PROJECT -> true
        else -> form == EntryForm.GEOGRAPHIC && !ed50
    }
}

/** Qué se hace con un nombre repetido (S028/RF-17): saltarlo, darle el siguiente nombre libre o cancelar la importación. */
enum class DuplicatePolicy { SKIP, RENAME, CANCEL }

/** Todas las opciones de una importación (S028/RF-4 a RF-20). Viven mientras el asistente está abierto. */
data class ImportOptions(
    val encoding: ImportEncoding = ImportEncoding.UTF8,
    val separator: ImportSeparator = ImportSeparator.COMMA,
    val decimal: ImportDecimal = ImportDecimal.POINT,
    val hasHeader: Boolean = false,
    val mapping: ColumnMapping = ColumnMapping(emptyList()),
    val coordinates: ImportCoordinates = ImportCoordinates(),
    val unit: LinearUnit = LinearUnit.METRE,
    val zeroAsNoHeight: Boolean = false,
    val duplicates: DuplicatePolicy = DuplicatePolicy.SKIP,
) {
    /** La coma de separador y la coma decimal no se distinguirían (S028/RF-8; como S012/RF-14). */
    val decimalConflict: Boolean get() = separator == ImportSeparator.COMMA && decimal == ImportDecimal.COMMA
}

/** Por qué una fila del fichero no se puede importar (S028/RF-23). */
enum class ImportErrorReason {
    MISSING_NAME,
    NOT_A_NUMBER,
    MISSING_COLUMNS,
    OUTSIDE_CRS_AREA,
    OUTSIDE_ED50_GRID,
    OUTSIDE_GEOID,
}

/** Una fila errónea: su línea del fichero (desde 1, contando la cabecera) y su motivo (S028/RF-23, RF-24). */
data class ImportRowError(val line: Int, val reason: ImportErrorReason)

/**
 * Los recuentos de un fichero con unas opciones (S028/RF-21, RF-28): [rows] filas de datos, [valid] las que entran (con las
 * renombradas), [skipped] los repetidos que no entran, [renamed] los repetidos que entran con otro nombre y [errors] las erróneas.
 */
data class ImportCounts(val rows: Int = 0, val valid: Int = 0, val skipped: Int = 0, val renamed: Int = 0, val errors: Int = 0) {
    /** Los nombres repetidos que se han encontrado, hayan entrado renombrados o no. */
    val repeated: Int get() = skipped + renamed

    /** Cada fila entra, se salta o es errónea, y las renombradas están entre las que entran. */
    val isConsistent: Boolean get() = rows == valid + skipped + errors && renamed <= valid
}

/** Lo que pasa con una fila de la vista previa (S028/RF-21). */
enum class ImportRowStatus { VALID, REPEATED_SKIPPED, REPEATED_RENAMED, ERROR }

/**
 * Una fila de la vista previa: lo leído del fichero, sin interpretar, con su estado, su motivo si es un error y el nombre nuevo
 * si se renombra (S028/RF-21).
 */
data class ImportPreviewRow(
    val line: Int,
    val name: String,
    val first: String,
    val second: String,
    val height: String,
    val description: String,
    val status: ImportRowStatus = ImportRowStatus.VALID,
    val reason: ImportErrorReason? = null,
    val renamedTo: String? = null,
)
