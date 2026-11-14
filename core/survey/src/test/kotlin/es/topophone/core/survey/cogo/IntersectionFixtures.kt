package es.topophone.core.survey.cogo

import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.cogo.CogoFixtures.number

/** Un caso de `fixtures/cogo/intersecciones.csv`: lo que se escribe y las soluciones (o el motivo) que deben salir. */
class IntersectionCase(
    val name: String,
    val kind: String,
    val crs: ProjectCrs,
    val mode: DistanceMode,
    val first: CogoPoint,
    val second: CogoPoint,
    val value1: Double,
    val value2: Double,
    private val rows: List<Map<String, String>>,
) {
    val scale: CogoScale get() = CogoScale.forFirst(first, crs, mode)

    val label: String get() = "$name ${crs.name} $mode"

    val count: Int get() = rows.first().getValue("n_soluciones").toInt()

    /** El motivo de que no haya solución, o vacío. */
    val reason: String get() = rows.first().getValue("motivo")

    /** Etiqueta, Este, Norte y las dos distancias de cada solución, en el orden del fichero. */
    val solutions: List<ExpectedSolution>
        get() = if (count == 0) {
            emptyList()
        } else {
            rows.map { ExpectedSolution(it.getValue("etiqueta"), it.number("este"), it.number("norte"), it.number("t1"), it.number("t2")) }
        }
}

class ExpectedSolution(val label: String, val easting: Double, val northing: Double, val t1: Double, val t2: Double)

object IntersectionFixtures {
    private val all: List<IntersectionCase> by lazy {
        CogoFixtures.rows("intersecciones.csv")
            .groupBy { Triple(it.getValue("caso"), it.getValue("crs"), it.getValue("modo")) }
            .map { (key, rows) ->
                val crs = ProjectCrs.valueOf(key.second)
                val points = CogoFixtures.points(crs)
                val head = rows.first()
                IntersectionCase(
                    name = key.first,
                    kind = head.getValue("tipo"),
                    crs = crs,
                    mode = if (key.third == "ground") DistanceMode.GROUND else DistanceMode.GRID,
                    first = points.getValue(head.getValue("punto_1")),
                    second = points.getValue(head.getValue("punto_2")),
                    value1 = head.number("valor_1"),
                    value2 = head.number("valor_2"),
                    rows = rows,
                )
            }
    }

    fun cases(kind: String): List<IntersectionCase> = all.filter { it.kind == kind }
}
