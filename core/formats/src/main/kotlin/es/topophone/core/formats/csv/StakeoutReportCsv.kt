package es.topophone.core.formats.csv

import es.topophone.core.model.point.StakeSource
import es.topophone.core.model.point.StakeoutRef
import es.topophone.core.model.point.StakeoutReportRow
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs

/**
 * El CSV del informe de replanteo (S019/RF-21 a RF-23, plan A5): una cabecera y una fila por punto replanteado, en el orden
 * dado, con las reglas de formato de S012: CRLF, RFC 4180 para los textos, longitudes en la unidad del proyecto con 4 decimales,
 * latitud y longitud con signo y 9 decimales, separador y signo decimal a elección y lo que el punto no tiene, vacío. Las
 * cabeceras van en inglés, como las de S012, para que otros programas las reconozcan.
 */
object StakeoutReportCsv {
    private val TAIL_HEADERS = listOf(
        "Delta northing",
        "Delta easting",
        "Horizontal distance",
        "Delta elevation",
        "Horizontal tolerance",
        "Vertical tolerance",
        "Within tolerance",
        "Last",
        "Solution",
        "Measured from",
    )

    /** Si el punto de diseño era un elemento de un dibujo: el dibujo, la capa, el tipo de elemento, el modo, el PK y el desplazamiento (S041/RF-30). */
    private val DRAWING_HEADERS = listOf("Drawing", "Layer", "Element type", "Mode", "Chainage", "Lateral offset")

    /** Si se replanteó con una superficie activa: el origen de la cota, la superficie, su cota en lo medido y el desplazamiento vertical (S042/RF-31). */
    private val SURFACE_HEADERS = listOf("Elevation source", "Surface", "Surface elevation", "Vertical offset")

    /** Las cabeceras: con un SC geográfico, la latitud y la longitud en lugar del Este y el Norte (S019/RF-22). */
    fun headers(crs: ProjectCrs): List<String> {
        val first = if (crs.isUtm) "easting" to "northing" else "latitude" to "longitude"
        return listOf("Design name", "Stakeout number", "Staked name", "Description") +
            listOf("Design ${first.first}", "Design ${first.second}", "Design elevation") +
            listOf("Measured ${first.first}", "Measured ${first.second}", "Measured elevation") +
            TAIL_HEADERS + DRAWING_HEADERS + SURFACE_HEADERS
    }

    /** Escribe el informe de [rows] en [out]; falla si el separador y el signo decimal son los dos la coma (S012/RF-14). */
    fun write(rows: List<StakeoutReportRow>, project: Project, separator: ColumnSeparator, decimal: DecimalSeparator, out: Appendable) {
        require(!(separator == ColumnSeparator.COMMA && decimal == DecimalSeparator.COMMA)) {
            "El separador y el signo decimal no pueden ser los dos la coma"
        }
        val sep = separator.char
        out.append(headers(project.crs).joinToString(sep.toString()) { CsvExport.quoted(it, sep) }).append(CsvExport.LINE_END)
        for (row in rows) {
            out.append(values(row, project, decimal).joinToString(sep.toString()) { CsvExport.quoted(it, sep) }).append(CsvExport.LINE_END)
        }
    }

    /** Las seis columnas del dibujo, vacías en un punto replanteado que viene de un punto del proyecto (S041/RF-30). */
    private fun drawingValues(ref: StakeoutRef, length: (Double?) -> String): List<String> {
        val source = ref.source as? StakeSource.CadElement ?: return List(DRAWING_HEADERS.size) { "" }
        return listOf(
            source.drawingName,
            source.layerName,
            source.kind.name,
            ref.mode?.name.orEmpty(),
            length(ref.chainageM),
            length(ref.lateralOffsetM),
        )
    }

    /** Las cuatro columnas de la superficie, vacías en un replanteo sin superficie activa (S042/RF-31). */
    private fun surfaceValues(ref: StakeoutRef, length: (Double?) -> String): List<String> = listOf(
        ref.elevationSource?.name.orEmpty(),
        ref.surfaceName.orEmpty(),
        length(ref.surfaceElevationM),
        length(ref.verticalOffsetM),
    )

    private fun values(row: StakeoutReportRow, project: Project, decimal: DecimalSeparator): List<String> {
        val unit = project.linearUnit
        fun length(valueM: Double?) = valueM?.let { CsvExport.number(unit.fromMetres(it), CsvExport.LENGTH_DECIMALS, decimal) }.orEmpty()
        fun degrees(value: Double) = CsvExport.number(value, CsvExport.DEGREE_DECIMALS, decimal)
        fun flag(value: Boolean?) = value?.toString().orEmpty()
        val ref = row.ref
        val design = ref.design
        val measured = row.point.coordinates
        val measurement = row.point.measurement
        val position = if (project.crs.isUtm) {
            listOf(length(design.easting), length(design.northing)) to listOf(length(measured.easting), length(measured.northing))
        } else {
            listOf(degrees(design.ground.latitudeDeg), degrees(design.ground.longitudeDeg)) to
                listOf(degrees(measured.ground.latitudeDeg), degrees(measured.ground.longitudeDeg))
        }
        return listOf(ref.designName, row.number.toString(), row.point.name, row.point.description) +
            position.first + length(ref.designElevationM) +
            position.second + length(measured.height) +
            listOf(
                length(ref.dNorthM),
                length(ref.dEastM),
                length(ref.dHorizontalM),
                length(ref.dHeightM),
                length(ref.toleranceHM),
                length(ref.toleranceVM),
                flag(ref.withinTolerance),
                row.last.toString(),
                measurement?.quality?.solution.orEmpty(),
                measurement?.measuredFromMs?.let(CsvExport::utc).orEmpty(),
            ) + drawingValues(ref, ::length) + surfaceValues(ref, ::length)
    }
}
