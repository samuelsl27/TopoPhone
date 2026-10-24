package es.topophone.core.model.point

import kotlin.math.abs

/**
 * Una fila del informe de replanteo (S019/RF-21): un punto replanteado con su número de replanteo (1 para `_stkd`, 2 para
 * `_stkd2`…) y si es el último de su punto de diseño.
 */
data class StakeoutReportRow(val point: Point, val ref: StakeoutRef, val number: Int, val last: Boolean)

/**
 * El resumen del informe (S019/RF-17, RF-26): solo cuenta el último replanteo de cada punto de diseño. Los replanteados antes
 * de S019 están [notEvaluated] (ni dentro ni fuera) pero entran en las desviaciones que tienen. Las desviaciones son nulas si
 * no hay ninguna que contar.
 *
 * @property designPoints puntos de diseño con algún replanteo en el informe.
 * @property horizontalMaxM la mayor distancia horizontal al diseño y su media; [verticalMaxM] y [verticalMeanM] son del valor
 *   absoluto de la diferencia de cota, solo de los [verticalCount] replanteos con cota.
 */
data class StakeoutSummary(
    val designPoints: Int,
    val within: Int,
    val outside: Int,
    val notEvaluated: Int,
    val horizontalMaxM: Double?,
    val horizontalMeanM: Double?,
    val verticalCount: Int,
    val verticalMaxM: Double?,
    val verticalMeanM: Double?,
)

/**
 * El informe de replanteo de un proyecto o de una selección (S019/A4): las filas, ordenadas por el nombre del punto de diseño en
 * orden natural y por número de replanteo, y su [summary]. Es lógica pura: quien lo pide entrega los puntos del proyecto.
 */
data class StakeoutReport(val rows: List<StakeoutReportRow>, val summary: StakeoutSummary) {
    /** No hay ningún punto replanteado que informar (S019/RF-20). */
    val isEmpty: Boolean get() = rows.isEmpty()

    companion object {
        /**
         * El informe de los puntos replanteados de [points]. Con [selection], solo esos puntos (RF-18; un id que no es un punto
         * replanteado se ignora). Los puntos de la papelera no entran nunca (RF-19). El "último" de cada punto de diseño es el de
         * mayor fecha de creación entre los que entran.
         */
        fun of(points: List<Point>, selection: Set<String>? = null): StakeoutReport {
            val included = points.filter { it.staked != null && it.deletedAtMs == null && (selection == null || it.id in selection) }
            val byDesign = included.groupBy { it.staked!!.designId }
            val rows = byDesign.values.flatMap { group ->
                val ranked = group.sortedWith(compareBy<Point> { it.createdAtMs }.thenBy { it.name }.thenBy { it.id })
                val numbered = ranked.mapIndexed { index, point -> point to (StakeoutNames.number(point.name) ?: (index + 1)) }
                val latest = numbered.maxWith(compareBy<Pair<Point, Int>> { it.first.createdAtMs }.thenBy { it.second })
                numbered.map { (point, number) -> StakeoutReportRow(point, point.staked!!, number, last = point === latest.first) }
            }.sortedWith(ROW_ORDER)
            return StakeoutReport(rows, summarize(rows.filter { it.last }))
        }

        private val ROW_ORDER: Comparator<StakeoutReportRow> = Comparator
            .comparing<StakeoutReportRow, String>({ it.ref.designName }, NaturalOrder)
            .thenComparing({ it.ref.designId }, Comparator.naturalOrder())
            .thenComparingInt { it.number }
            .thenComparingLong { it.point.createdAtMs }

        private fun summarize(lasts: List<StakeoutReportRow>): StakeoutSummary {
            val horizontal = lasts.map { it.ref.dHorizontalM }
            val vertical = lasts.mapNotNull { row -> row.ref.dHeightM?.let { abs(it) } }
            return StakeoutSummary(
                designPoints = lasts.size,
                within = lasts.count { it.ref.withinTolerance == true },
                outside = lasts.count { it.ref.withinTolerance == false },
                notEvaluated = lasts.count { it.ref.notEvaluated },
                horizontalMaxM = horizontal.maxOrNull(),
                horizontalMeanM = horizontal.takeIf { it.isNotEmpty() }?.average(),
                verticalCount = vertical.size,
                verticalMaxM = vertical.maxOrNull(),
                verticalMeanM = vertical.takeIf { it.isNotEmpty() }?.average(),
            )
        }
    }
}
