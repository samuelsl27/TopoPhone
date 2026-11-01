package es.topophone.core.survey

import es.topophone.core.model.alignment.section.CrossSection
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.section.SectionIssue
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.alignment.section.SectionPoint
import es.topophone.core.model.alignment.section.SectionTemplate
import es.topophone.core.model.alignment.section.SuperelevationRow
import es.topophone.core.model.alignment.section.TemplateSegment
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Las secciones, secciones tipo y peraltes que no se guardan, con el punto y el motivo (S050/RF-7). */
class SectionValidatorTest {
    private fun section(vararg points: SectionPoint, chainage: Double = 100.0) =
        CrossSection(SectionKind.DESIGN, chainage, HeightKind.RELATIVE, points.toList())

    private val good = section(SectionPoint("BI", -3.5, -0.07), SectionPoint("EJE", 0.0, 0.0), SectionPoint("BD", 3.5, -0.07))

    private fun template(
        left: List<TemplateSegment> = listOf(TemplateSegment("BI", 3.5, 0.02, true)),
        right: List<TemplateSegment> = listOf(TemplateSegment("BD", 3.5, 0.02, true)),
        from: Double = 0.0,
        to: Double = 100.0,
        grade: String = "EJE",
    ) = SectionTemplate(from, to, grade, left, right)

    private fun check(t: SectionTemplate, others: List<SectionTemplate> = emptyList()) = SectionValidator.validate(t, 0.0, 420.0, others)

    @Test
    fun `S050 RF-7 una seccion correcta se acepta`() {
        SectionValidator.validate(good, 0.0, 420.0).shouldBeNull()
    }

    @Test
    fun `S050 RF-7 un codigo repetido da el codigo`() {
        val s = section(SectionPoint("BI", -3.5, 0.0), SectionPoint("EJE", 0.0, 0.0), SectionPoint("BI", 3.5, 0.0))

        SectionValidator.validate(s, 0.0, 420.0) shouldBe SectionIssue.RepeatedCode("BI")
    }

    @Test
    fun `S050 RF-7 los puntos desordenados dan el primero que no queda a la derecha`() {
        val s = section(SectionPoint("EJE", 0.0, 0.0), SectionPoint("BI", -3.5, 0.0), SectionPoint("BD", 3.5, 0.0))

        SectionValidator.validate(s, 0.0, 420.0) shouldBe SectionIssue.Unordered("BI")
    }

    @Test
    fun `S050 RF-7 dos puntos en el mismo desplazamiento tambien estan desordenados`() {
        val s = section(SectionPoint("A", 0.0, 0.0), SectionPoint("B", 0.0, 0.1))

        SectionValidator.validate(s, 0.0, 420.0) shouldBe SectionIssue.Unordered("B")
    }

    @Test
    fun `S050 RF-7 un PK fuera del eje no se guarda`() {
        SectionValidator.validate(good.copy(chainage = 421.0), 0.0, 420.0) shouldBe SectionIssue.ChainageOutsideAxis(421.0)
        SectionValidator.validate(good.copy(chainage = -1.0), 0.0, 420.0) shouldBe SectionIssue.ChainageOutsideAxis(-1.0)
        SectionValidator.validate(good.copy(chainage = 420.0), 0.0, 420.0).shouldBeNull()
    }

    @Test
    fun `S050 RF-7 un codigo de mas de 12 caracteres o vacio no vale`() {
        val long = "ABCDEFGHIJKLM"

        SectionValidator.validate(section(SectionPoint(long, 0.0, 0.0)), 0.0, 420.0) shouldBe SectionIssue.InvalidCode(long)
        SectionValidator.validate(section(SectionPoint("", 0.0, 0.0)), 0.0, 420.0) shouldBe SectionIssue.InvalidCode("")
    }

    @Test
    fun `S050 RF-7 una pendiente de mas de 100 por ciento en la seccion tipo da el tramo`() {
        check(template(right = listOf(TemplateSegment("BD", 3.5, 1.5, false)))) shouldBe SectionIssue.SlopeTooSteep("BD", 1.5)
        check(template(right = listOf(TemplateSegment("BD", 3.5, 1.0, false)))).shouldBeNull()
    }

    @Test
    fun `S050 RF-7 un tramo sin ancho positivo esta desordenado`() {
        check(template(left = listOf(TemplateSegment("BI", 0.0, 0.02, true)))) shouldBe SectionIssue.Unordered("BI")
    }

    @Test
    fun `S050 RF-7 la seccion tipo no repite codigos ni reutiliza el del eje`() {
        val twice = template(left = listOf(TemplateSegment("X", 1.0, 0.0, false)), right = listOf(TemplateSegment("X", 1.0, 0.0, false)))
        val axis = template(left = listOf(TemplateSegment("EJE", 1.0, 0.0, false)))

        check(twice) shouldBe SectionIssue.RepeatedCode("X")
        check(axis) shouldBe SectionIssue.RepeatedCode("EJE")
    }

    @Test
    fun `S050 RF-7 el punto de rasante tiene que ser el eje o un codigo de la seccion`() {
        check(template(grade = "BD")).shouldBeNull()
        check(template(grade = "ZZ")) shouldBe SectionIssue.UnknownGradeCode("ZZ")
    }

    @Test
    fun `S050 RF-7 el rango de la seccion tipo cabe en el eje y tiene el final tras el inicio`() {
        check(template(from = -1.0)) shouldBe SectionIssue.ChainageOutsideAxis(-1.0)
        check(template(to = 500.0)) shouldBe SectionIssue.ChainageOutsideAxis(500.0)
        check(template(from = 50.0, to = 10.0)) shouldBe SectionIssue.ReversedRange(50.0, 10.0)
    }

    @Test
    fun `S050 RF-7 dos secciones tipo no se solapan pero pueden tocarse`() {
        val other = template(from = 0.0, to = 100.0)

        check(template(from = 50.0, to = 150.0), listOf(other)) shouldBe SectionIssue.OverlappingTemplates(50.0, 150.0)
        check(template(from = 100.0, to = 200.0), listOf(other)).shouldBeNull()
    }

    @Test
    fun `S050 RF-7 un peralte con PK repetidos o fuera del eje no se guarda`() {
        val ok = listOf(SuperelevationRow(0.0, 0.02, 0.02), SuperelevationRow(100.0, -0.06, 0.06))

        SuperelevationValidator.validate(ok, 0.0, 420.0).shouldBeNull()
        SuperelevationValidator.validate(ok + SuperelevationRow(100.0, 0.0, 0.0), 0.0, 420.0) shouldBe SectionIssue.RepeatedChainage(100.0)
        SuperelevationValidator.validate(ok + SuperelevationRow(500.0, 0.0, 0.0), 0.0, 420.0) shouldBe
            SectionIssue.ChainageOutsideAxis(500.0)
    }

    @Test
    fun `S050 RF-7 una fila de peralte con pendiente de mas de 100 por ciento no se guarda`() {
        val rows = listOf(SuperelevationRow(0.0, 0.02, 1.2))

        SuperelevationValidator.validate(rows, 0.0, 420.0) shouldBe SectionIssue.SlopeTooSteep("", 1.2)
    }
}
