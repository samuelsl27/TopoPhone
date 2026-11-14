package es.topophone.core.survey.cogo

import es.topophone.core.model.cogo.LegKind
import es.topophone.core.model.cogo.TraverseLeg
import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.cogo.CogoFixtures.number
import es.topophone.core.survey.cogo.CogoFixtures.optional

/** Una poligonal de `fixtures/cogo/poligonales.csv`: lo que se escribe y los puntos que deben salir. */
class TraverseCase(
    val name: String,
    val crs: ProjectCrs,
    val mode: DistanceMode,
    val start: CogoPoint,
    val reference: CogoPoint,
    val legs: List<TraverseLeg>,
    /** Por tramo: Este, Norte y altura esperados (la altura, null si falta). */
    val expected: List<Triple<Double, Double, Double?>>,
    val rows: List<Map<String, String>>,
) {
    val scale: CogoScale get() = CogoScale.forFirst(start, crs, mode)

    val label: String get() = "$name ${crs.name} $mode"
}

object TraverseFixtures {
    val cases: List<TraverseCase> by lazy {
        CogoFixtures.rows("poligonales.csv")
            .groupBy { Triple(it.getValue("caso"), it.getValue("crs"), it.getValue("modo")) }
            .map { (key, rows) ->
                val crs = ProjectCrs.valueOf(key.second)
                val points = CogoFixtures.points(crs)
                TraverseCase(
                    name = key.first,
                    crs = crs,
                    mode = if (key.third == "ground") DistanceMode.GROUND else DistanceMode.GRID,
                    start = points.getValue(rows.first().getValue("inicio")),
                    reference = points.getValue(rows.first().getValue("referencia")),
                    legs = rows.map {
                        TraverseLeg(
                            LegKind.valueOf(it.getValue("tipo")),
                            it.number("valor_rad"),
                            it.number("distancia"),
                            it.optional("desnivel"),
                        )
                    },
                    expected = rows.map { Triple(it.number("este"), it.number("norte"), it.optional("altura")) },
                    rows = rows,
                )
            }
    }

    fun case(name: String, crs: ProjectCrs, mode: DistanceMode): TraverseCase = cases.first {
        it.name == name &&
            it.crs == crs &&
            it.mode == mode
    }
}
