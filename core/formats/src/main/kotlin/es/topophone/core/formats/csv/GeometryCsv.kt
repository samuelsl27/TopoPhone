package es.topophone.core.formats.csv

import es.topophone.core.model.format.AngleFormat
import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.LinearUnit
import java.util.Locale

/** Una fila de la tabla de segmentos, en metros, radianes y porcentaje, sin redondear (S026/RF-2, RF-8). */
data class GeometryCsvSegment(
    val number: Int,
    val fromName: String,
    val toName: String,
    val chainageM: Double,
    val length2DM: Double,
    val length3DM: Double?,
    val azimuthRad: Double,
    val deltaHeightM: Double?,
    val slopePercent: Double?,
)

/** Los totales de la información; el perímetro y el área solo existen en un polígono (S026/RF-1, RF-8). */
data class GeometryCsvTotals(val length2DM: Double, val length3DM: Double?, val perimeterM: Double?, val areaM2: Double?)

/**
 * La tabla de segmentos de la información geométrica en CSV (S026/RF-8): UTF-8, CRLF, coma como separador y punto decimal siempre
 * (RNF-T9), con las unidades del proyecto y la distancia activa en la cabecera. Una fila por segmento y, al final, el total y, si es
 * un polígono, el perímetro y el área, con su valor en la columna de la longitud 2D. Las cabeceras van en inglés, como las de S012.
 */
object GeometryCsv {
    private const val SEPARATOR = ','
    private const val LENGTH_DECIMALS = 4
    private const val ANGLE_DECIMALS = 7

    fun write(
        segments: List<GeometryCsvSegment>,
        totals: GeometryCsvTotals,
        mode: DistanceMode,
        linearUnit: LinearUnit,
        angularUnit: AngularUnit,
        out: Appendable,
    ) {
        val length = unit(linearUnit)
        val distance = "$length ${if (mode == DistanceMode.GROUND) "ground" else "grid"}"
        val headers = listOf(
            "Segment",
            "From",
            "To",
            "Chainage ($distance)",
            "Length 2D ($distance)",
            "Length 3D ($distance)",
            "Azimuth (${angleLabel(angularUnit)})",
            "Delta height ($length)",
            "Slope (%)",
        )
        row(out, headers)
        fun metres(valueM: Double?) = valueM?.let { number(linearUnit.fromMetres(it), LENGTH_DECIMALS) }.orEmpty()
        for (s in segments) {
            row(
                out,
                listOf(
                    s.number.toString(),
                    s.fromName,
                    s.toName,
                    metres(s.chainageM),
                    metres(s.length2DM),
                    metres(s.length3DM),
                    azimuth(s.azimuthRad, angularUnit),
                    metres(s.deltaHeightM),
                    s.slopePercent?.let { number(it, LENGTH_DECIMALS) }.orEmpty(),
                ),
            )
        }
        row(out, listOf("Total", "", "", "", metres(totals.length2DM), metres(totals.length3DM), "", "", ""))
        totals.perimeterM?.let { row(out, listOf("Perimeter", "", "", "", metres(it), "", "", "", "")) }
        totals.areaM2?.let {
            val area = it / (linearUnit.metres * linearUnit.metres)
            row(out, listOf("Area (${unit(linearUnit)}2)", "", "", "", number(area, LENGTH_DECIMALS), "", "", "", ""))
        }
    }

    private fun row(out: Appendable, cells: List<String>) {
        out.append(cells.joinToString(SEPARATOR.toString()) { CsvExport.quoted(it, SEPARATOR) }).append(CsvExport.LINE_END)
    }

    private fun number(value: Double, decimals: Int) = CsvExport.number(value, decimals, DecimalSeparator.POINT)

    private fun unit(unit: LinearUnit) = when (unit) {
        LinearUnit.METRE -> "m"
        LinearUnit.INTERNATIONAL_FOOT -> "ft"
        LinearUnit.US_SURVEY_FOOT -> "usft"
    }

    private fun angleLabel(unit: AngularUnit) = when (unit) {
        AngularUnit.DECIMAL_DEGREES -> "deg"
        AngularUnit.DMS -> "dms"
        AngularUnit.GON -> "gon"
    }

    private fun azimuth(rad: Double, unit: AngularUnit): String = when (unit) {
        AngularUnit.DECIMAL_DEGREES -> number(Math.toDegrees(rad), ANGLE_DECIMALS)
        AngularUnit.GON -> number(Math.toDegrees(rad) * 200.0 / 180.0, ANGLE_DECIMALS)
        AngularUnit.DMS -> AngleFormat.direction(rad, AngularUnit.DMS, Locale.ROOT)
    }
}
