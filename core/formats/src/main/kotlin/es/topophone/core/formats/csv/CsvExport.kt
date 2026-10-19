package es.topophone.core.formats.csv

import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Las columnas de "Todas las columnas", en su orden (S012/RF-6). [header] va en inglés, como en la app de referencia,
 * para que otros programas lo reconozcan; no depende del idioma de la app.
 */
enum class ExportColumn(val header: String) {
    NAME("Name"),
    DESCRIPTION("Description"),
    ORIGIN("Origin"),
    EASTING("Easting"),
    NORTHING("Northing"),
    ELEVATION("Elevation"),
    LATITUDE("Latitude"),
    LONGITUDE("Longitude"),
    ELLIPSOIDAL_HEIGHT("Ellipsoidal height"),
    CRS("CRS"),
    HEIGHT_SYSTEM("Height system"),
    UNITS("Units"),
    POLE_HEIGHT("Pole height"),
    SOLUTION("Solution"),
    SATELLITES("Satellites"),
    HDOP("HDOP"),
    CORRECTION_AGE("Correction age"),
    HORIZONTAL_ACCURACY("Horizontal accuracy"),
    VERTICAL_ACCURACY("Vertical accuracy"),
    MODE("Mode"),
    EPOCHS_USED("Epochs used"),
    EPOCHS_DISCARDED("Epochs discarded"),
    REPLAY("Replay"),
    OUT_OF_TOLERANCE("Out of tolerance"),
    MEASURED_FROM("Measured from"),
    MEASURED_TO("Measured to"),
    EDITED("Edited"),
    COPY_OF("Copy of"),
}

/** Formato de exportación (S012/RF-5): las columnas del personalizado van en [CsvOptions.customColumns]. */
enum class ExportFormat {
    ALL,
    PENZD,
    PNEZD,
    CUSTOM,
}

/** Separador de columnas (S012/RF-13); el espacio no se ofrece porque las descripciones llevan espacios. */
enum class ColumnSeparator(val char: Char) {
    COMMA(','),
    SEMICOLON(';'),
    TAB('\t'),
}

/** Signo decimal de los números del fichero (S012/RF-13; RNF-T9). */
enum class DecimalSeparator(val char: Char) {
    POINT('.'),
    COMMA(','),
}

/** Cómo se escribe el fichero; por defecto, todas las columnas con coma y punto (S012/RF-13). */
data class CsvOptions(
    val format: ExportFormat = ExportFormat.ALL,
    val separator: ColumnSeparator = ColumnSeparator.COMMA,
    val decimal: DecimalSeparator = DecimalSeparator.POINT,
    val customColumns: Set<ExportColumn> = ExportColumn.entries.toSet(),
) {
    /** Coma de separador y de decimal no se distinguirían (S012/RF-14). */
    val valid: Boolean get() = !(separator == ColumnSeparator.COMMA && decimal == DecimalSeparator.COMMA)

    /** Las columnas que se escriben, en su orden (S012/RF-6 a RF-8). */
    val columns: List<ExportColumn>
        get() = when (format) {
            ExportFormat.ALL -> ExportColumn.entries
            ExportFormat.PENZD -> listOf(
                ExportColumn.NAME,
                ExportColumn.EASTING,
                ExportColumn.NORTHING,
                ExportColumn.ELEVATION,
                ExportColumn.DESCRIPTION,
            )
            ExportFormat.PNEZD -> listOf(
                ExportColumn.NAME,
                ExportColumn.NORTHING,
                ExportColumn.EASTING,
                ExportColumn.ELEVATION,
                ExportColumn.DESCRIPTION,
            )
            ExportFormat.CUSTOM -> ExportColumn.entries.filter { it in customColumns }
        }
}

/**
 * CSV de los puntos de un proyecto (S012/RF-5 a RF-16), por streaming: una cabecera y una fila por punto en el orden
 * dado, con CRLF y el RFC 4180 para los textos. Longitudes en la unidad del proyecto con 4 decimales; latitud y
 * longitud del datum de los cálculos, con signo y 9 decimales; lo que el punto no tiene, vacío.
 */
object CsvExport {
    private const val LENGTH_DECIMALS = 4
    private const val DEGREE_DECIMALS = 9
    private const val ONE_DECIMAL = 1
    private const val LIST_SEPARATOR = "|"
    private const val LINE_END = "\r\n"

    /** S012/RF-10: sin Este ni Norte, PENZD y PNEZD no tienen sentido. */
    fun formatsFor(crs: ProjectCrs): List<ExportFormat> =
        if (crs.isUtm) ExportFormat.entries else listOf(ExportFormat.ALL, ExportFormat.CUSTOM)

    fun write(points: Sequence<Point>, project: Project, options: CsvOptions, out: Appendable) {
        require(options.valid) { "El separador y el signo decimal no pueden ser los dos la coma" }
        val columns = options.columns
        val separator = options.separator.char
        out.append(columns.joinToString(separator.toString()) { quoted(it.header, separator) }).append(LINE_END)
        for (point in points) {
            columns.forEachIndexed { index, column ->
                if (index > 0) out.append(separator)
                out.append(quoted(value(point, column, project, options.decimal), separator))
            }
            out.append(LINE_END)
        }
    }

    private fun value(point: Point, column: ExportColumn, project: Project, decimal: DecimalSeparator): String {
        val unit = project.linearUnit
        fun length(valueM: Double?) = valueM?.let { number(unit.fromMetres(it), LENGTH_DECIMALS, decimal) }.orEmpty()
        val coordinates = point.coordinates
        val measurement = point.measurement
        val quality = measurement?.quality
        return when (column) {
            ExportColumn.NAME -> point.name
            ExportColumn.DESCRIPTION -> point.description
            ExportColumn.ORIGIN -> if (point.origin == PointOrigin.MEASURED) "Measured" else "Manual"
            ExportColumn.EASTING -> length(coordinates.easting)
            ExportColumn.NORTHING -> length(coordinates.northing)
            ExportColumn.ELEVATION -> length(coordinates.height)
            ExportColumn.LATITUDE -> number(coordinates.ground.latitudeDeg, DEGREE_DECIMALS, decimal)
            ExportColumn.LONGITUDE -> number(coordinates.ground.longitudeDeg, DEGREE_DECIMALS, decimal)
            ExportColumn.ELLIPSOIDAL_HEIGHT -> length(coordinates.ground.ellipsoidalHeightM)
            ExportColumn.CRS -> "EPSG:${project.crs.epsg}"
            ExportColumn.HEIGHT_SYSTEM -> if (project.heightSystem == HeightSystem.ORTHOMETRIC) "EGM08-REDNAP" else "Ellipsoidal"
            ExportColumn.UNITS -> unitCode(unit)
            ExportColumn.POLE_HEIGHT -> length(measurement?.poleHeightM)
            ExportColumn.SOLUTION -> quality?.solution.orEmpty()
            ExportColumn.SATELLITES -> quality?.satellites?.toString().orEmpty()
            ExportColumn.HDOP -> quality?.hdop?.let { number(it, ONE_DECIMAL, decimal) }.orEmpty()
            ExportColumn.CORRECTION_AGE -> quality?.correctionAgeS?.let { number(it, ONE_DECIMAL, decimal) }.orEmpty()
            ExportColumn.HORIZONTAL_ACCURACY -> length(quality?.horizontalM)
            ExportColumn.VERTICAL_ACCURACY -> length(quality?.verticalM)
            ExportColumn.MODE -> quality?.mode?.let { if (it == MeasurementMode.INSTANT) "Instant" else "Averaged" }.orEmpty()
            ExportColumn.EPOCHS_USED -> quality?.epochsUsed?.toString().orEmpty()
            ExportColumn.EPOCHS_DISCARDED -> quality?.epochsDiscarded?.toString().orEmpty()
            ExportColumn.REPLAY -> quality?.fromReplay?.toString().orEmpty()
            ExportColumn.OUT_OF_TOLERANCE -> measurement?.outOfTolerance?.sorted()?.joinToString(LIST_SEPARATOR) { it.name }.orEmpty()
            ExportColumn.MEASURED_FROM -> measurement?.measuredFromMs?.let(::utc).orEmpty()
            ExportColumn.MEASURED_TO -> measurement?.measuredToMs?.let(::utc).orEmpty()
            ExportColumn.EDITED -> point.edited.toString()
            ExportColumn.COPY_OF -> point.copyOf.orEmpty()
        }
    }

    private fun unitCode(unit: LinearUnit): String = when (unit) {
        LinearUnit.METRE -> "m"
        LinearUnit.INTERNATIONAL_FOOT -> "ft"
        LinearUnit.US_SURVEY_FOOT -> "US ft"
    }

    /** Sin notación científica ni signo en el cero (`-0.0000`), con el signo decimal elegido (RNF-T9). */
    private fun number(value: Double, decimals: Int, decimal: DecimalSeparator): String {
        val rounded = BigDecimal(value).setScale(decimals, RoundingMode.HALF_UP)
        val normalized = if (rounded.signum() == 0) BigDecimal.ZERO.setScale(decimals) else rounded
        val text = normalized.toPlainString()
        return if (decimal == DecimalSeparator.POINT) text else text.replace('.', decimal.char)
    }

    /** Fecha y hora en UTC al segundo, ISO 8601 con Z (RNF-T10). */
    private fun utc(epochMs: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMs).truncatedTo(ChronoUnit.SECONDS))

    /** RFC 4180: entre comillas si lleva el separador, comillas o saltos de línea, con las comillas duplicadas (S012/RF-15). */
    private fun quoted(text: String, separator: Char): String =
        if (text.any { it == separator || it == '"' || it == '\n' || it == '\r' }) "\"" + text.replace("\"", "\"\"") + "\"" else text
}
