package es.topophone.core.survey

import es.topophone.core.model.alignment.section.CrossSection
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.alignment.section.SectionPoint
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** Un punto de la sección en un PK: planta, cota y pendiente transversal; por código y por desplazamiento (S050/RF-9 a RF-11; plan A3). */
class SectionEvalTest {
    private fun resolver(axis: String) = SectionResolver(
        listOfNotNull(SectionFixtures.template(axis)),
        SectionFixtures.superelevation(axis),
        if (axis == "Eje C") SectionFixtures.explicit() else emptyList(),
    )

    private fun geometry(axis: String) = AlignmentGeometry(AlignmentFixtures.alignment(axis).elements)

    private fun profile(axis: String) = AlignmentFixtures.profileOf(axis)?.let { VerticalProfileEval(it) }

    private fun section(axis: String, chainage: Double) = resolver(axis).at(chainage).shouldBeInstanceOf<SectionResolution.Found>().section

    @Test
    fun `S050 RF-9 el Este el Norte la cota y la pendiente de cada codigo coinciden con la referencia a 1 mm`() {
        var compared = 0
        for (e in SectionFixtures.points()) {
            val result = SectionEval.byCode(section(e.axis, e.chainage), geometry(e.axis), profile(e.axis), e.code)
                .shouldBeInstanceOf<SectionPointResult.Value>()

            result.offsetM shouldBe (e.offset plusOrMinus 1e-9)
            result.easting shouldBe (e.easting plusOrMinus 1e-6)
            result.northing shouldBe (e.northing plusOrMinus 1e-6)
            result.elevationM shouldBe (e.elevation!! plusOrMinus 1e-6)
            result.slope!! * 100.0 shouldBe (e.slopePct plusOrMinus 1e-7)
            compared++
        }
        (compared > 100) shouldBe true
    }

    @Test
    fun `S050 RF-10 la cota a un desplazamiento coincide con la referencia y mas alla de los exteriores esta fuera`() {
        var inside = 0
        var outside = 0
        for ((key, expected) in SectionFixtures.offsets()) {
            val (axis, chainage, offset) = key
            val result = SectionEval.byOffset(section(axis, chainage), geometry(axis), profile(axis), offset)

            if (expected == null) {
                result shouldBe SectionPointResult.OutsideSection
                outside++
            } else {
                result.shouldBeInstanceOf<SectionPointResult.Value>().elevationM!! shouldBe (expected plusOrMinus 1e-6)
                inside++
            }
        }
        (inside > 100 && outside > 20) shouldBe true
    }

    @Test
    fun `S050 RF-9 un codigo que no esta en la seccion se indica`() {
        SectionEval.byCode(section("Eje A", 10.0), geometry("Eje A"), profile("Eje A"), "ZZ") shouldBe SectionPointResult.NoCode
    }

    @Test
    fun `S050 RF-11 sin rasante una seccion de cotas absolutas da cota y una relativa solo la planta`() {
        val absolute = SectionEval.byCode(
            section("Eje C", 25.0),
            geometry("Eje C"),
            null,
            "EJE",
        ).shouldBeInstanceOf<SectionPointResult.Value>()
        val relative = SectionEval.byCode(
            section("Eje A", 25.0),
            geometry("Eje A"),
            null,
            "BD",
        ).shouldBeInstanceOf<SectionPointResult.Value>()

        absolute.elevationM!! shouldBe (100.65 plusOrMinus 1e-9)
        relative.elevationM.shouldBeNull()
        relative.easting.isFinite() shouldBe true
    }

    @Test
    fun `S050 RF-9 una seccion absoluta da su cota aunque el eje tenga rasante`() {
        val s =
            CrossSection(
                SectionKind.DESIGN,
                100.0,
                HeightKind.ABSOLUTE,
                listOf(SectionPoint("EJE", 0.0, 7.5), SectionPoint("BD", 3.0, 7.4)),
            )

        SectionEval.byCode(s, geometry("Eje A"), profile("Eje A"), "BD").shouldBeInstanceOf<SectionPointResult.Value>().elevationM shouldBe
            7.4
    }

    @Test
    fun `S050 RF-9 la pendiente es positiva si el terreno baja al alejarse del eje y se da en el eje con el lado derecho`() {
        val s = section("Eje A", 10.0) // bombeo 2 % y cunetas al 50 % hacia arriba
        val geometry = geometry("Eje A")
        val profile = profile("Eje A")

        SectionEval.byCode(s, geometry, profile, "BD").shouldBeInstanceOf<SectionPointResult.Value>().slope!! shouldBe
            (0.02 plusOrMinus 1e-12)
        SectionEval.byCode(s, geometry, profile, "BI").shouldBeInstanceOf<SectionPointResult.Value>().slope!! shouldBe
            (0.02 plusOrMinus 1e-12)
        SectionEval.byCode(s, geometry, profile, "CD").shouldBeInstanceOf<SectionPointResult.Value>().slope!! shouldBe
            (-0.5 plusOrMinus 1e-12)
        SectionEval.byCode(s, geometry, profile, "EJE").shouldBeInstanceOf<SectionPointResult.Value>().slope!! shouldBe
            (0.02 plusOrMinus 1e-12)
    }

    @Test
    fun `S050 RF-10 un desplazamiento en un punto de la seccion da el punto y entre dos puntos interpola`() {
        val s = section("Eje C", 0.0)
        val g = geometry("Eje C")

        // Entre BD (6, 100,10) y CD (8, 99,80): a 7 m, 99,95.
        SectionEval.byOffset(s, g, null, 7.0).shouldBeInstanceOf<SectionPointResult.Value>().elevationM!! shouldBe (99.95 plusOrMinus 1e-12)
        SectionEval.byOffset(s, g, null, 6.0).shouldBeInstanceOf<SectionPointResult.Value>().elevationM!! shouldBe
            (100.10 plusOrMinus 1e-12)
    }

    @Test
    fun `S050 RF-9 un PK fuera del eje no tiene planta`() {
        val s = CrossSection(SectionKind.DESIGN, 99999.0, HeightKind.ABSOLUTE, listOf(SectionPoint("EJE", 0.0, 0.0)))

        SectionEval.byCode(s, geometry("Eje A"), null, "EJE") shouldBe SectionPointResult.OutsideAxis
    }
}
