package es.topophone.core.formats.localization

import es.topophone.core.model.format.AngleFormat
import es.topophone.core.model.format.FactorFormat
import es.topophone.core.model.localization.Localization
import es.topophone.core.model.localization.VerticalKind
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * El informe de una localización en CSV (S039/RF-25): una sección de parámetros (`clave,valor`), una línea en blanco y una fila por par con su
 * posición en el SC sin localizar, las coordenadas conocidas, las componentes que usa y sus residuos. Las longitudes van en la unidad del proyecto,
 * con el punto como signo decimal y sin la configuración regional (RNF-T9); el fichero es UTF-8 con saltos `\r\n`.
 */
object LocalizationReportCsv {
    private const val LINE_END = "\r\n"
    private const val LENGTH_DECIMALS = 4
    private const val PPM_DECIMALS = 3
    private const val PPM = 1.0e6

    /** Escribe el informe de [localization], con los residuos y los parámetros que tenga calculados, en [out]. */
    fun write(localization: Localization, project: Project, out: Appendable) {
        val unit = project.linearUnit
        fun length(valueM: Double?) = valueM?.let { number(unit.fromMetres(it), LENGTH_DECIMALS) }.orEmpty()
        val params = localization.params
        val lines = mutableListOf<List<String>>()
        lines += listOf("Project", project.name)
        lines += listOf("Units", unitCode(unit))
        lines += listOf("Status", localization.status.name)
        lines += listOf("Origin", localization.origin.name)
        localization.name?.let { lines += listOf("Name", it) }
        lines += listOf("Scale fixed to 1", localization.fixScale.toString())
        lines += listOf("Residual limit", length(localization.residualLimitM))
        lines += listOf("Scale limit", number(localization.scaleLimit, 6))
        if (params != null) {
            if (params.hasHorizontal) {
                lines += listOf("Translation easting (centroid)", length(params.translationE))
                lines += listOf("Translation northing (centroid)", length(params.translationN))
                lines += listOf("Rotation (rad)", number(params.rotationRad, 12))
                lines += listOf("Rotation", AngleFormat.convergence(params.rotationRad, project.angularUnit, Locale.ROOT))
                lines += listOf("Scale", FactorFormat.factor(params.scale, Locale.ROOT))
                lines += listOf("Scale (ppm)", number(params.scalePpm, PPM_DECIMALS))
            }
            lines += listOf("Vertical", params.verticalKind.name)
            if (params.verticalKind != VerticalKind.NONE) lines += listOf("Vertical offset", length(params.dh))
            if (params.verticalKind == VerticalKind.PLANE) lines += listOf("Vertical slope (ppm)", number(params.slopePpm, PPM_DECIMALS))
        }
        lines += listOf("RMS horizontal", length(localization.rmsHorizontal))
        lines += listOf("RMS vertical", length(localization.rmsVertical))
        lines.forEach { out.append(row(it)).append(LINE_END) }
        out.append(LINE_END)
        out.append(row(PAIR_HEADERS)).append(LINE_END)
        for (pair in localization.pairs) {
            val residual = localization.residuals[pair.id]
            val horizontal = if (residual?.east != null && residual.north != null) Math.hypot(residual.east!!, residual.north!!) else null
            out.append(
                row(
                    listOf(
                        pair.pointName,
                        length(pair.scE),
                        length(pair.scN),
                        length(pair.scH),
                        length(pair.knownE),
                        length(pair.knownN),
                        length(pair.knownH),
                        pair.useHorizontal.toString(),
                        pair.useVertical.toString(),
                        pair.confirmed.toString(),
                        length(residual?.east),
                        length(residual?.north),
                        length(horizontal),
                        length(residual?.vertical),
                    ),
                ),
            ).append(LINE_END)
        }
    }

    private val PAIR_HEADERS = listOf(
        "Point", "CRS easting", "CRS northing", "CRS height", "Known easting", "Known northing", "Known height",
        "Use horizontal", "Use vertical", "Confirmed", "Residual easting", "Residual northing", "Residual horizontal", "Residual vertical",
    )

    private fun row(values: List<String>): String = values.joinToString(",") { quoted(it) }

    private fun quoted(text: String): String =
        if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + text.replace("\"", "\"\"") + "\"" else text

    private fun unitCode(unit: LinearUnit): String = when (unit) {
        LinearUnit.METRE -> "m"
        LinearUnit.INTERNATIONAL_FOOT -> "ft"
        LinearUnit.US_SURVEY_FOOT -> "US ft"
    }

    private fun number(value: Double, decimals: Int): String {
        val rounded = BigDecimal(value).setScale(decimals, RoundingMode.HALF_UP)
        val normalized = if (rounded.signum() == 0) BigDecimal.ZERO.setScale(decimals) else rounded
        return normalized.toPlainString()
    }
}
