package es.topophone.core.survey.cogo

import es.topophone.core.model.cogo.LegKind
import es.topophone.core.model.cogo.TraverseLeg
import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.cogo.CogoFixtures.LENGTH_TOLERANCE_M
import es.topophone.core.survey.cogo.CogoFixtures.number
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** El error de cierre de una poligonal, sin corregir ningún punto (S038/RF-12). */
class TraverseClosureTest {

    private val grid = CogoScale.forFirst(CogoPoint(0.0, 0.0), ProjectCrs.ETRS89_GEOGRAPHIC, DistanceMode.GRID)

    @Test
    fun `S038 RF-12 el error en Este y Norte, el lineal y la precision coinciden con la referencia`() {
        val rows = CogoFixtures.rows("cierres.csv")
        rows.size shouldBe 3 * 2 * 11 // las 8 fijas y las 3 aleatorias son anillos cerrados

        for (row in rows) {
            val case = TraverseFixtures.case(
                row.getValue("caso"),
                CogoFixtures.crs(row),
                if (row.getValue("modo") ==
                    "ground"
                ) {
                    DistanceMode.GROUND
                } else {
                    DistanceMode.GRID
                },
            )
            val closing = CogoFixtures.points(case.crs).getValue(row.getValue("punto_cierre"))

            val result = Traverse.compute(case.start, case.reference, case.legs, case.scale, closing)
            val closure = result.closure!!

            withClue(case.label) {
                closure.dEasting shouldBe (row.number("error_este") plusOrMinus LENGTH_TOLERANCE_M)
                closure.dNorthing shouldBe (row.number("error_norte") plusOrMinus LENGTH_TOLERANCE_M)
                closure.linear shouldBe (row.number("error_lineal") plusOrMinus LENGTH_TOLERANCE_M)
                closure.totalLength shouldBe (row.number("longitud_total") plusOrMinus LENGTH_TOLERANCE_M)
                val precision = row.getValue("precision")
                if (precision.isEmpty()) {
                    closure.ratio.shouldBeNull() // cierra: no hay 1:N
                } else {
                    closure.ratio!! shouldBe (precision.toDouble() plusOrMinus (precision.toDouble() * 1e-4))
                }
            }
        }
    }

    @Test
    fun `S038 RF-12 una poligonal que no cierra se queda a 5 cm y no se corrige ningun punto`() {
        val case = TraverseFixtures.case("no_cierra", ProjectCrs.ETRS89_UTM30, DistanceMode.GRID)

        val result = Traverse.compute(case.start, case.reference, case.legs, case.scale, case.start)

        result.closure!!.linear shouldBe (0.05 plusOrMinus 1e-6)
        result.steps.last().easting shouldBe (case.expected.last().first plusOrMinus LENGTH_TOLERANCE_M)
        result.steps.size shouldBe case.legs.size
    }

    @Test
    fun `S038 RF-12 el error se mide del punto de cierre al ultimo punto calculado`() {
        val legs = listOf(TraverseLeg(LegKind.AZIMUTH, Math.PI / 2, 100.0, null), TraverseLeg(LegKind.AZIMUTH, 0.0, 50.0, null))

        val closure = Traverse.compute(CogoPoint(0.0, 0.0), null, legs, grid, CogoPoint(103.0, 46.0)).closure!!

        closure.dEasting shouldBe (-3.0 plusOrMinus 1e-9) // último (100, 50) menos cierre (103, 46)
        closure.dNorthing shouldBe (4.0 plusOrMinus 1e-9)
        closure.linear shouldBe (5.0 plusOrMinus 1e-9)
        closure.totalLength shouldBe 150.0
        closure.ratio!! shouldBe (30.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S038 RF-12 sin punto de cierre, o con la poligonal parada o sin tramos, no hay error de cierre`() {
        val legs = listOf(TraverseLeg(LegKind.AZIMUTH, 0.0, 10.0, null))
        val incomplete = listOf(legs[0], TraverseLeg(LegKind.AZIMUTH, null, 10.0, null))

        Traverse.compute(CogoPoint(0.0, 0.0), null, legs, grid).closure.shouldBeNull()
        Traverse.compute(CogoPoint(0.0, 0.0), null, incomplete, grid, CogoPoint(0.0, 10.0)).closure.shouldBeNull()
        Traverse.compute(CogoPoint(0.0, 0.0), null, emptyList(), grid, CogoPoint(0.0, 10.0)).closure.shouldBeNull()
    }
}
