package es.topophone.core.survey

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** Lo que hay que moverse a lo largo y a través del eje frente a `fixtures/ejes/replanteo/esperado-guia.csv` (S054/RF-6). */
class AxisGuidanceTest {
    private val rows = SurveyFixtures.rows("esperado-guia.csv", "ejes/replanteo")

    private fun target(axis: String, pk: Double, offset: Double): AxisStakeResult.Target {
        val alignment = AlignmentFixtures.alignment(axis)
        return AxisStakeTarget.of(AlignmentGeometry(alignment), null, pk, offset).shouldBeInstanceOf<AxisStakeResult.Target>()
    }

    private fun move(name: String) = when (name) {
        "dentro" -> AcrossMove.INWARD
        "fuera" -> AcrossMove.OUTWARD
        "izquierda" -> AcrossMove.LEFT
        "derecha" -> AcrossMove.RIGHT
        else -> AcrossMove.NONE
    }

    @Test
    fun `S054 RF-6 adelante y a la derecha coinciden con lo esperado en recta arco y clotoide a menos de 1 mm`() {
        for (r in rows) {
            val got = AxisGuidance.of(target(r[0], r[1].toDouble(), r[2].toDouble()), r[7].toDouble(), r[8].toDouble())

            got.alongM shouldBe (r[3].toDouble() plusOrMinus 1e-6)
            got.acrossM shouldBe (r[4].toDouble() plusOrMinus 1e-6)
        }
        (rows.size > 150) shouldBe true
    }

    @Test
    fun `S054 RF-6 hacia dentro o hacia fuera segun el movimiento acerque la punta al eje o la aleje`() {
        for (r in rows) {
            AxisGuidance.of(target(r[0], r[1].toDouble(), r[2].toDouble()), r[7].toDouble(), r[8].toDouble()).across shouldBe move(r[9])
        }
    }

    @Test
    fun `S054 RF-6 con desplazamiento 0 no hay dentro ni fuera sino izquierda o derecha`() {
        val onAxis = target("Eje C", 100.0, 0.0) // recta hacia el Este: la derecha es el Sur
        val design = onAxis.point

        AxisGuidance.of(onAxis, design.easting, design.northing + 0.5).across shouldBe AcrossMove.RIGHT // punta al Norte: hay que ir al Sur
        AxisGuidance.of(onAxis, design.easting, design.northing - 0.5).across shouldBe AcrossMove.LEFT
        AxisGuidance.of(onAxis, design.easting, design.northing).across shouldBe AcrossMove.NONE
    }

    @Test
    fun `S054 RF-6 en la recta hacia el Este adelante es hacia el Este y la derecha hacia el Sur`() {
        val design = target("Eje C", 100.0, 3.5).point // 3,5 m al Sur del eje
        val guidance = AxisGuidance.of(target("Eje C", 100.0, 3.5), design.easting - 2.0, design.northing + 1.0)

        guidance.alongM shouldBe (2.0 plusOrMinus 1e-9) // la punta está 2 m al Oeste: hay que avanzar
        guidance.acrossM shouldBe (1.0 plusOrMinus 1e-9) // la punta está 1 m al Norte: el diseño queda a la derecha
        guidance.across shouldBe AcrossMove.OUTWARD // el diseño ya está a la derecha del eje: seguir a la derecha lo aleja
    }
}
