package es.topophone.core.survey

import es.topophone.core.model.alignment.section.CrossSection
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.alignment.section.SectionPoint
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** La sección de un PK: explícita interpolada o sección tipo, con los códigos que faltan y fuera de rango (S050/RF-3, RF-12; plan A2, D1, D4). */
class SectionResolverTest {
    private fun resolver(axis: String) = SectionResolver(
        templates = listOfNotNull(SectionFixtures.template(axis)),
        superelevation = SectionFixtures.superelevation(axis),
        sections = if (axis == "Eje C") SectionFixtures.explicit() else emptyList(),
    )

    @Test
    fun `S050 RF-12 cada PK de la referencia se resuelve como ok faltan o fuera`() {
        for (expected in SectionFixtures.resolutions()) {
            val result = resolver(expected.axis).at(expected.chainage)

            when (expected.state) {
                "ok" -> result.shouldBeInstanceOf<SectionResolution.Found>()
                "faltan" -> result.shouldBeInstanceOf<SectionResolution.Missing>().codes shouldBe expected.missing
                else -> result shouldBe SectionResolution.OutsideRange
            }
        }
    }

    @Test
    fun `S050 RF-12 la interpolacion entre secciones explicitas coincide con la referencia a 1e-9`() {
        var compared = 0
        for (e in SectionFixtures.points().filter { it.axis == "Eje C" }) {
            val section = resolver("Eje C").at(e.chainage).shouldBeInstanceOf<SectionResolution.Found>().section
            val p = section.points.first { it.code == e.code }

            p.offsetM shouldBe (e.offset plusOrMinus 1e-9)
            p.heightM shouldBe (e.elevation!! plusOrMinus 1e-9)
            section.heightKind shouldBe HeightKind.ABSOLUTE
            compared++
        }
        (compared > 25) shouldBe true
    }

    @Test
    fun `S050 RF-3 un PK exactamente en una seccion da esa seccion sin interpolar`() {
        val explicit = SectionFixtures.explicit()
        val found = resolver("Eje C").at(100.0).shouldBeInstanceOf<SectionResolution.Found>().section

        found shouldBe explicit.first { it.chainage == 100.0 }
    }

    @Test
    fun `S050 RF-2 fuera de las secciones explicitas manda la seccion tipo de su rango`() {
        val found = resolver("Eje A").at(230.0).shouldBeInstanceOf<SectionResolution.Found>().section

        found.heightKind shouldBe HeightKind.RELATIVE
        found.points.first { it.code == "BD" }.heightM shouldBe (-0.21 plusOrMinus 1e-12)
    }

    @Test
    fun `S050 RF-12 un eje sin secciones ni seccion tipo no tiene seccion`() {
        SectionResolver(emptyList(), null, emptyList()).at(10.0) shouldBe SectionResolution.OutsideRange
    }

    @Test
    fun `S050 RF-12 con una sola seccion explicita solo vale en su PK`() {
        val only = CrossSection(SectionKind.DESIGN, 50.0, HeightKind.ABSOLUTE, listOf(SectionPoint("EJE", 0.0, 10.0)))
        val r = SectionResolver(emptyList(), null, listOf(only))

        r.at(50.0).shouldBeInstanceOf<SectionResolution.Found>()
        r.at(51.0) shouldBe SectionResolution.OutsideRange
    }

    @Test
    fun `S050 RF-12 si manda la explicita en un PK que tambien cubre la seccion tipo`() {
        val template = SectionFixtures.template("Eje A")!!
        val explicit = listOf(0.0, 100.0).map {
            CrossSection(SectionKind.DESIGN, it, HeightKind.RELATIVE, listOf(SectionPoint("BI", -2.0, 0.0), SectionPoint("BD", 2.0, 0.0)))
        }

        val found = SectionResolver(listOf(template), null, explicit).at(50.0).shouldBeInstanceOf<SectionResolution.Found>().section

        found.points.map { it.code } shouldBe listOf("BI", "BD")
    }

    @Test
    fun `S050 RF-12 dos secciones con los mismos codigos pero distinto tipo de cota no se interpolan`() {
        val a = CrossSection(SectionKind.DESIGN, 0.0, HeightKind.RELATIVE, listOf(SectionPoint("EJE", 0.0, 0.0)))
        val b = CrossSection(SectionKind.DESIGN, 100.0, HeightKind.ABSOLUTE, listOf(SectionPoint("EJE", 0.0, 50.0)))

        val result = SectionResolver(emptyList(), null, listOf(a, b)).at(50.0).shouldBeInstanceOf<SectionResolution.Missing>()

        result.codes shouldBe emptyList()
        result.heightKindMismatch shouldBe true
    }

    @Test
    fun `S050 RF-12 los perfiles de terreno no cuentan como secciones de diseno`() {
        val ground = SectionFixtures.explicit(SectionKind.GROUND)

        SectionResolver(emptyList(), null, ground).at(50.0) shouldBe SectionResolution.OutsideRange
    }
}
