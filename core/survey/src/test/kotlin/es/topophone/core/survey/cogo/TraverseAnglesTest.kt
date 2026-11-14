package es.topophone.core.survey.cogo

import es.topophone.core.model.cogo.LegKind
import es.topophone.core.model.cogo.TraverseLeg
import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.cogo.CogoFixtures.LENGTH_TOLERANCE_M
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.jupiter.api.Test

/** Poligonal con ángulos y deflexiones, y la visual atrás indefinida (S038/RF-10, RF-31). */
class TraverseAnglesTest {

    private val grid = CogoScale.forFirst(CogoPoint(0.0, 0.0), ProjectCrs.ETRS89_GEOGRAPHIC, DistanceMode.GRID)
    private val start = CogoPoint(0.0, 0.0)

    /** Una referencia al sur del inicio: la visual atrás del primer tramo tiene azimut 180°. */
    private val south = CogoPoint(0.0, -100.0)

    private fun leg(kind: LegKind, deg: Double?, distance: Double?) = TraverseLeg(kind, deg?.let(Math::toRadians), distance, null)

    @Test
    fun `S038 RF-10 las poligonales con angulos y deflexiones de la referencia recuperan sus puntos`() {
        val cases = TraverseFixtures.cases.filter { case -> case.legs.any { it.kind != LegKind.AZIMUTH } }
        cases.map { it.name }.toSet() shouldBe setOf(
            "angulo_derecha",
            "angulo_izquierda",
            "deflexion",
            "mezcla",
            "sin_altura_inicio",
            "aleatoria_1",
            "aleatoria_2",
            "aleatoria_3",
        )

        for (case in cases) {
            val result = Traverse.compute(case.start, case.reference, case.legs, case.scale)

            withClue(case.label) {
                result.stoppedAt shouldBe null
                result.steps.size shouldBe case.legs.size
                result.steps.zip(case.expected).forEach { (step, expected) ->
                    step.easting shouldBe (expected.first plusOrMinus LENGTH_TOLERANCE_M)
                    step.northing shouldBe (expected.second plusOrMinus LENGTH_TOLERANCE_M)
                }
            }
        }
    }

    @Test
    fun `S038 RF-10 mirando atras al sur un angulo a la derecha de 90 va al oeste y a la izquierda al este`() {
        val right = Traverse.compute(start, south, listOf(leg(LegKind.ANGLE_RIGHT, 90.0, 50.0)), grid).steps.single()
        val left = Traverse.compute(start, south, listOf(leg(LegKind.ANGLE_LEFT, 90.0, 50.0)), grid).steps.single()

        right.easting shouldBe (-50.0 plusOrMinus 1e-9)
        right.northing shouldBe (0.0 plusOrMinus 1e-9)
        right.azimuthRad shouldBe (3 * PI / 2 plusOrMinus 1e-12)
        left.easting shouldBe (50.0 plusOrMinus 1e-9)
        left.northing shouldBe (0.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S038 RF-10 la deflexion se mide desde la prolongacion de la visual atras a un lado u otro`() {
        // La prolongación de la referencia (al sur) al inicio apunta al norte: 30° a la derecha va al nordeste y a la izquierda al noroeste.
        val right = Traverse.compute(start, south, listOf(leg(LegKind.DEFLECTION_RIGHT, 30.0, 100.0)), grid).steps.single()
        val left = Traverse.compute(start, south, listOf(leg(LegKind.DEFLECTION_LEFT, 30.0, 100.0)), grid).steps.single()

        right.easting shouldBe (100.0 * sin(PI / 6) plusOrMinus 1e-9)
        right.northing shouldBe (100.0 * cos(PI / 6) plusOrMinus 1e-9)
        left.easting shouldBe (-100.0 * sin(PI / 6) plusOrMinus 1e-9)
        left.northing shouldBe (100.0 * cos(PI / 6) plusOrMinus 1e-9)
    }

    @Test
    fun `S038 RF-10 el segundo tramo toma como visual atras el tramo anterior`() {
        // Primer tramo al este (azimut 90°); mirando atrás al oeste, 90° a la derecha es el norte.
        val legs = listOf(TraverseLeg(LegKind.AZIMUTH, PI / 2, 100.0, null), leg(LegKind.ANGLE_RIGHT, 90.0, 40.0))

        val second = Traverse.compute(start, null, legs, grid).steps[1]

        second.easting shouldBe (100.0 plusOrMinus 1e-9)
        second.northing shouldBe (40.0 plusOrMinus 1e-9)
        // Una deflexión de 0° sigue recta por la prolongación del tramo anterior; una de 180° vuelve por donde vino.
        val straight = Traverse.compute(start, null, listOf(legs[0], leg(LegKind.DEFLECTION_RIGHT, 0.0, 40.0)), grid).steps[1]
        val back = Traverse.compute(start, null, listOf(legs[0], leg(LegKind.DEFLECTION_LEFT, 180.0, 40.0)), grid).steps[1]
        straight.easting shouldBe (140.0 plusOrMinus 1e-9)
        back.easting shouldBe (60.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S038 RF-31 con la referencia en el inicio el tramo por angulo no se calcula ni los siguientes`() {
        val legs = listOf(
            leg(LegKind.ANGLE_RIGHT, 90.0, 50.0),
            TraverseLeg(LegKind.AZIMUTH, 0.0, 10.0, null),
        )

        val result = Traverse.compute(start, CogoPoint(0.0, 0.0), legs, grid)

        result.steps shouldBe emptyList()
        result.stoppedAt shouldBe 1
        result.stopReason shouldBe TraverseStop.BACKSIGHT_UNDEFINED
    }

    @Test
    fun `S038 RF-31 un tramo anterior de longitud 0 deja sin visual atras al tramo por angulo o deflexion`() {
        val legs = listOf(
            TraverseLeg(LegKind.AZIMUTH, 1.0, 0.0, null),
            leg(LegKind.DEFLECTION_RIGHT, 20.0, 30.0),
            TraverseLeg(LegKind.AZIMUTH, 0.0, 10.0, null),
        )

        val result = Traverse.compute(start, south, legs, grid)

        result.steps.map { it.easting } shouldBe listOf(0.0) // el de longitud 0 sí se calcula: es el propio punto de inicio
        result.stoppedAt shouldBe 2
        result.stopReason shouldBe TraverseStop.BACKSIGHT_UNDEFINED
    }

    @Test
    fun `S038 RF-31 un tramo por azimut no necesita visual atras ni siquiera tras uno de longitud 0`() {
        val legs = listOf(TraverseLeg(LegKind.AZIMUTH, 1.0, 0.0, null), TraverseLeg(LegKind.AZIMUTH, PI / 2, 25.0, null))

        val result = Traverse.compute(start, null, legs, grid)

        result.stoppedAt shouldBe null
        result.steps.last().easting shouldBe (25.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S038 RF-10 sin punto de referencia un tramo por angulo no se calcula y se dice por que`() {
        val result = Traverse.compute(start, null, listOf(leg(LegKind.ANGLE_LEFT, 45.0, 10.0)), grid)

        result.steps shouldBe emptyList()
        result.stoppedAt shouldBe 1
        result.stopReason shouldBe TraverseStop.NO_REFERENCE
    }

    @Test
    fun `S038 RF-10 un tramo a medio escribir para el calculo en ese tramo y deja los anteriores`() {
        val legs =
            listOf(
                TraverseLeg(LegKind.AZIMUTH, 0.0, 10.0, null),
                leg(LegKind.ANGLE_RIGHT, null, 5.0),
                TraverseLeg(LegKind.AZIMUTH, 0.0, 1.0, null),
            )

        val result = Traverse.compute(start, south, legs, grid)

        result.steps.size shouldBe 1
        result.stoppedAt shouldBe 2
        result.stopReason shouldBe TraverseStop.INCOMPLETE_LEG
        result.totalGridLength shouldBe 10.0
    }
}
