package es.topophone.core.survey

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Los puntos singulares de un eje frente a `fixtures/ejes/replanteo/esperado-singulares.csv` (S054/RF-4). */
class KeyStationsTest {
    private val expected = SurveyFixtures.rows("esperado-singulares.csv", "ejes/replanteo")

    private fun stationsOf(axis: String) = AlignmentFixtures.alignment(axis).let { KeyStations.of(it.elements, it.profile) }

    @Test
    fun `S054 RF-4 los puntos singulares son los esperados en cada eje`() {
        for (axis in AlignmentFixtures.axes) {
            val want = expected.filter { it[0] == axis }
            val got = stationsOf(axis)

            got.size shouldBe want.size
            got.zip(want).forEach { (station, row) ->
                station.chainageM shouldBe (row[1].toDouble() plusOrMinus 1e-9)
                station.reasons.map { it.name.lowercase() }.toSet() shouldBe
                    row[2].split("+").map { if (it == "tramo") "plan" else "profile" }.toSet()
            }
        }
    }

    @Test
    fun `S054 RF-4 el cambio de clase de tramo es singular y la union de dos clotoides no`() {
        stationsOf("Eje A").map { it.chainageM } shouldBe listOf(0.0, 120.0, 150.0, 180.0, 280.0, 300.0, 340.0, 420.0)
        stationsOf("Eje B").none { Math.abs(it.chainageM - 1180.5) < 1e-6 } shouldBe true
    }

    @Test
    fun `S054 RF-4 un eje sin rasante ni cambio de clase no tiene puntos singulares`() {
        stationsOf("Eje C") shouldBe emptyList()
    }

    @Test
    fun `S054 RF-4 cada punto singular dice si viene de la planta o de la rasante`() {
        val plan = stationsOf("Eje A").first { it.chainageM == 120.0 }

        plan.reasons shouldBe setOf(KeyStationReason.PLAN)
        stationsOf("Eje A").first { it.chainageM == 150.0 }.reasons shouldBe setOf(KeyStationReason.PROFILE)
    }
}
