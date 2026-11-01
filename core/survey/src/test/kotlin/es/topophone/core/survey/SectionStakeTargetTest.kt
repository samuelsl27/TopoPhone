package es.topophone.core.survey

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** El punto de diseño de un replanteo por PK y código, o por qué no lo hay (S050/RF-16, RF-18, RF-21; plan A5). */
class SectionStakeTargetTest {
    private fun resolver(axis: String) = SectionResolver(
        listOfNotNull(SectionFixtures.template(axis)),
        SectionFixtures.superelevation(axis),
        if (axis == "Eje C") SectionFixtures.explicit() else emptyList(),
    )

    private fun geometry(axis: String) = AlignmentGeometry(AlignmentFixtures.alignment(axis).elements)

    private fun profile(axis: String) = AlignmentFixtures.profileOf(axis)?.let { VerticalProfileEval(it) }

    private fun target(axis: String, chainage: Double, code: String, thickness: Double = 0.0) =
        SectionStakeTarget.of(geometry(axis), profile(axis), resolver(axis), chainage, code, thickness)

    @Test
    fun `S050 RF-18 el Este el Norte y la cota de diseno de cada codigo coinciden con la referencia a 1 mm`() {
        var compared = 0
        for (e in SectionFixtures.points()) {
            val t = target(e.axis, e.chainage, e.code).shouldBeInstanceOf<SectionStakeResult.Target>()

            t.target.point.easting shouldBe (e.easting plusOrMinus 1e-6)
            t.target.point.northing shouldBe (e.northing plusOrMinus 1e-6)
            t.target.offsetM shouldBe (e.offset plusOrMinus 1e-9)
            t.target.elevationM!! shouldBe (e.elevation!! plusOrMinus 1e-6)
            t.slope!! * 100.0 shouldBe (e.slopePct plusOrMinus 1e-7)
            t.code shouldBe e.code
            compared++
        }
        (compared > 100) shouldBe true
    }

    @Test
    fun `S050 RF-18 el espesor se suma a la cota de la seccion`() {
        val without = target("Eje A", 230.0, "BD").shouldBeInstanceOf<SectionStakeResult.Target>()
        val with = target("Eje A", 230.0, "BD", thickness = 0.15).shouldBeInstanceOf<SectionStakeResult.Target>()

        with.target.elevationM!! shouldBe (without.target.elevationM!! + 0.15 plusOrMinus 1e-12)
        with.target.thicknessM shouldBe 0.15
    }

    @Test
    fun `S050 RF-21 un codigo que no existe en la seccion de ese PK no abre el replanteo`() {
        target("Eje A", 100.0, "ZZ") shouldBe SectionStakeResult.NoCode("ZZ", 100.0)
    }

    @Test
    fun `S050 RF-21 un PK sin seccion no abre el replanteo`() {
        target("Eje C", 195.0, "EJE") shouldBe SectionStakeResult.OutsideRange(195.0)
    }

    @Test
    fun `S050 RF-21 un PK entre dos secciones con codigos distintos da los que faltan`() {
        val result = target("Eje C", 125.0, "EJE").shouldBeInstanceOf<SectionStakeResult.Missing>()

        result.codes shouldBe listOf("CD", "CI")
    }

    @Test
    fun `S050 RF-21 un PK fuera del eje da el PK inicial y el final`() {
        val resolver = SectionResolver(
            listOf(SectionFixtures.template("Eje A")!!.copy(toChainage = 9_999.0)),
            null,
            emptyList(),
        )

        val result = SectionStakeTarget.of(geometry("Eje A"), profile("Eje A"), resolver, 5_000.0, "BD", 0.0)

        result shouldBe SectionStakeResult.OutsideAxis(0.0, 420.0)
    }

    @Test
    fun `S050 RF-11 sin rasante y con seccion relativa solo hay planta`() {
        val t = SectionStakeTarget.of(geometry("Eje A"), null, resolver("Eje A"), 100.0, "BD", 0.0)
            .shouldBeInstanceOf<SectionStakeResult.Target>()

        t.target.elevationM shouldBe null
    }
}
