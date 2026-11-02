package es.topophone.core.survey

import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.section.SectionKind
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** El perfil de terreno que sale de un perfil transversal frente a `fixtures/ejes/toma/` (S052/RF-17, RF-19, RF-20). */
class CrossProfileBuilderTest {
    private fun section(inputs: List<CrossInput>) =
        CrossProfileBuilder.build(105.0, inputs).shouldBeInstanceOf<CrossProfileResult.Built>().section

    @Test
    fun `S052 RF-20 los puntos incluidos quedan ordenados por desplazamiento con los codigos de tools`() {
        val heights = SurveyFixtures.rows("toma/transversal-puntos.csv", "ejes").associate { it[0] to it[3].toDouble() }
        val expected = SurveyFixtures.rows("toma/esperado-transversal.csv", "ejes").filter { it[5].isNotEmpty() }.sortedBy { it[5].toInt() }

        val result = section(expected.map { CrossInput(it[2].toDouble(), heights.getValue(it[0])) })

        result.kind shouldBe SectionKind.GROUND
        result.heightKind shouldBe HeightKind.ABSOLUTE
        result.chainage shouldBe 105.0
        result.points.map { it.code } shouldBe expected.map { it[6] }
        for ((p, row) in result.points.zip(expected)) {
            p.offsetM shouldBe (row[2].toDouble() plusOrMinus 1e-12)
            p.heightM shouldBe (heights.getValue(row[0]) plusOrMinus 1e-12)
        }
    }

    @Test
    fun `S052 RF-17 el orden de medida no cambia el perfil`() {
        val a = CrossInput(5.0, 100.0)
        val b = CrossInput(-2.0, 101.0)
        val c = CrossInput(1.0, 100.5)

        section(listOf(a, b, c)) shouldBe section(listOf(c, a, b))
        section(listOf(a, b, c)).points.map { it.offsetM } shouldBe listOf(-2.0, 1.0, 5.0)
    }

    @Test
    fun `S052 RF-17 el codigo dado se respeta y los demas son los P libres`() {
        val result = section(listOf(CrossInput(-3.0, 100.0, "CUN"), CrossInput(0.0, 100.5), CrossInput(4.0, 100.0, "P1")))

        result.points.map { it.code } shouldBe listOf("CUN", "P2", "P1")
    }

    @Test
    fun `S052 RF-17 un codigo repetido o no valido se sustituye por uno generado`() {
        val result = section(
            listOf(CrossInput(-3.0, 100.0, "BD"), CrossInput(0.0, 100.5, "BD"), CrossInput(4.0, 100.0, " mal "), CrossInput(6.0, 99.0, "")),
        )

        result.points.map { it.code } shouldBe listOf("BD", "P1", "P2", "P3")
    }

    @Test
    fun `S052 RF-20 sin puntos no hay perfil`() {
        CrossProfileBuilder.build(105.0, emptyList()) shouldBe CrossProfileResult.NoPoints
    }

    @Test
    fun `S052 RF-20 un punto sin cota o dos en el mismo desplazamiento no dejan guardar el perfil`() {
        CrossProfileBuilder.build(105.0, listOf(CrossInput(0.0, 100.0), CrossInput(2.0, null))) shouldBe
            CrossProfileResult.MissingHeight(2.0)
        CrossProfileBuilder.build(105.0, listOf(CrossInput(2.0, 100.0), CrossInput(2.0, 101.0))) shouldBe CrossProfileResult.SameOffset(2.0)
    }

    @Test
    fun `S052 RF-20 el perfil que sale pasa el validador de S050`() {
        val result = section(listOf(CrossInput(-3.0, 100.0), CrossInput(0.0, 100.5, "EJE"), CrossInput(4.0, 100.0)))

        SectionValidator.validate(result, 0.0, 200.0) shouldBe null
    }
}
