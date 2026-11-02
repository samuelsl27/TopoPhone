package es.topophone.core.formats.csv

import es.topophone.core.model.project.Project
import es.topophone.core.model.survey.linear.LinearSurveyKind

/**
 * Un punto de un perfil tomado, en metros y sin redondear: su PK, su desplazamiento (positivo a la derecha), la cota del terreno, la de la
 * referencia (la rasante del eje o la sección de diseño) y la diferencia. [code] es el código de sección de un perfil transversal.
 */
data class ProfileCsvRow(
    val code: String?,
    val chainageM: Double,
    val offsetM: Double,
    val groundM: Double?,
    val referenceM: Double?,
    val dHeightM: Double?,
)

/**
 * El CSV de un perfil tomado (S052/RF-15, RF-22) con las reglas de S012: CRLF, RFC 4180 para los textos, longitudes en la unidad del
 * proyecto con 4 decimales, separador y signo decimal a elección y lo que el punto no tiene, vacío. Las cabeceras van en inglés, como las
 * de S012. El longitudinal lleva el PK, el desplazamiento, la cota del terreno, la de la rasante y la diferencia; el transversal añade
 * el código de sección y compara con la sección de diseño.
 */
object ProfileCsv {
    private val LONGITUDINAL = listOf("Chainage", "Offset", "Ground elevation", "Grade elevation", "Delta elevation")
    private val CROSS = listOf("Code", "Chainage", "Offset", "Ground elevation", "Design elevation", "Delta elevation")

    fun headers(kind: LinearSurveyKind): List<String> = when (kind) {
        LinearSurveyKind.CROSS -> CROSS
        else -> LONGITUDINAL
    }

    /** Escribe el perfil de [rows] en [out]; falla si el separador y el signo decimal son los dos la coma (S012/RF-14). */
    fun write(
        kind: LinearSurveyKind,
        rows: List<ProfileCsvRow>,
        project: Project,
        separator: ColumnSeparator,
        decimal: DecimalSeparator,
        out: Appendable,
    ) {
        require(kind != LinearSurveyKind.AXIS) { "Un eje tomado no tiene perfil que exportar" }
        require(!(separator == ColumnSeparator.COMMA && decimal == DecimalSeparator.COMMA)) {
            "El separador y el signo decimal no pueden ser los dos la coma"
        }
        val sep = separator.char
        fun length(valueM: Double?) =
            valueM?.let { CsvExport.number(project.linearUnit.fromMetres(it), CsvExport.LENGTH_DECIMALS, decimal) }.orEmpty()
        out.append(headers(kind).joinToString(sep.toString()) { CsvExport.quoted(it, sep) }).append(CsvExport.LINE_END)
        for (row in rows) {
            val numbers =
                listOf(length(row.chainageM), length(row.offsetM), length(row.groundM), length(row.referenceM), length(row.dHeightM))
            val cells = if (kind == LinearSurveyKind.CROSS) listOf(row.code.orEmpty()) + numbers else numbers
            out.append(cells.joinToString(sep.toString()) { CsvExport.quoted(it, sep) }).append(CsvExport.LINE_END)
        }
    }
}
