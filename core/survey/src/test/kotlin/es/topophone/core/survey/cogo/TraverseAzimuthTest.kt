package es.topophone.core.survey.cogo

import es.topophone.core.model.cogo.LegKind
import es.topophone.core.model.cogo.TraverseLeg
import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.cogo.CogoFixtures.LENGTH_TOLERANCE_M
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.cos
import kotlin.math.sin
import org.junit.jupiter.api.Test

/** Poligonal con tramos por azimut y distancia, frente a un cálculo aparte (S038/RF-10, RF-11, RF-5). */
class TraverseAzimuthTest {

    private val grid = CogoScale.forFirst(CogoPoint(0.0, 0.0), ProjectCrs.ETRS89_GEOGRAPHIC, DistanceMode.GRID)

    @Test
    fun `S038 RF-10 las poligonales por azimut de la referencia recuperan sus puntos en todos los sistemas y modos`() {
        val cases = TraverseFixtures.cases.filter { case -> case.legs.all { it.kind == LegKind.AZIMUTH } }
        cases.size shouldBe 3 * 2 * 3 // azimutes, sin_desnivel y no_cierra, en tres sistemas y dos modos

        for (case in cases) {
            val result = Traverse.compute(case.start, case.reference, case.legs, case.scale)

            withClue(case.label) {
                result.stoppedAt.shouldBeNull()
                result.steps.size shouldBe case.legs.size
                result.steps.zip(case.expected).forEach { (step, expected) ->
                    step.easting shouldBe (expected.first plusOrMinus LENGTH_TOLERANCE_M)
                    step.northing shouldBe (expected.second plusOrMinus LENGTH_TOLERANCE_M)
                }
            }
        }
    }

    @Test
    fun `S038 RF-10 un tramo por azimut de 90 grados va hacia el este y uno de 0 hacia el norte`() {
        val legs = listOf(
            TraverseLeg(LegKind.AZIMUTH, Math.PI / 2, 100.0, null),
            TraverseLeg(LegKind.AZIMUTH, 0.0, 50.0, null),
        )

        val result = Traverse.compute(CogoPoint(1000.0, 2000.0), null, legs, grid)

        result.steps[0].easting shouldBe (1100.0 plusOrMinus 1e-9)
        result.steps[0].northing shouldBe (2000.0 plusOrMinus 1e-9)
        result.steps[1].easting shouldBe (1100.0 plusOrMinus 1e-9)
        result.steps[1].northing shouldBe (2050.0 plusOrMinus 1e-9)
        result.steps.map { it.gridDistance } shouldBe listOf(100.0, 50.0)
        result.totalGridLength shouldBe 150.0
    }

    @Test
    fun `S038 RF-11 cada punto se calcula en vivo desde el anterior sin necesitar punto de referencia`() {
        val az = 0.6
        val result = Traverse.compute(CogoPoint(10.0, 20.0), null, listOf(TraverseLeg(LegKind.AZIMUTH, az, 40.0, null)), grid)

        result.steps.single().easting shouldBe (10.0 + 40.0 * sin(az) plusOrMinus 1e-12)
        result.steps.single().northing shouldBe (20.0 + 40.0 * cos(az) plusOrMinus 1e-12)
        result.steps.single().azimuthRad shouldBe az
    }

    @Test
    fun `S038 RF-5 en terreno la distancia escrita se pasa a cuadricula con el factor del primer punto`() {
        val case = TraverseFixtures.case("azimutes", ProjectCrs.ETRS89_UTM30, DistanceMode.GROUND)
        val factor = case.scale.factor

        val result = Traverse.compute(case.start, case.reference, case.legs, case.scale)

        result.steps.first().gridDistance shouldBe (case.legs.first().distanceM!! * factor plusOrMinus 1e-9)
        (factor < 1.0) shouldBe true
    }

    @Test
    fun `S038 RF-10 una poligonal sin tramos no da puntos y no se para`() {
        val result = Traverse.compute(CogoPoint(1.0, 2.0), null, emptyList(), grid)

        result.steps shouldBe emptyList()
        result.stoppedAt.shouldBeNull()
        result.totalGridLength shouldBe 0.0
    }
}
