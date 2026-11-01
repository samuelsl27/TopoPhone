package es.topophone.core.survey

import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.alignment.section.SectionTemplate
import es.topophone.core.model.alignment.section.Superelevation
import es.topophone.core.model.alignment.section.SuperelevationRow
import es.topophone.core.model.alignment.section.TemplateSegment
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** La sección tipo, el peralte y el punto de rasante a los puntos de una sección (S050/RF-2, RF-9; plan A1, D2, D3). */
class SectionBuilderTest {
    private fun segment(code: String, width: Double, slope: Double, follows: Boolean = false) = TemplateSegment(code, width, slope, follows)

    private val flat = SectionTemplate(
        0.0,
        100.0,
        "EJE",
        listOf(segment("BI", 3.5, 0.02, true)),
        listOf(segment("BD", 3.5, 0.02, true)),
    )

    @Test
    fun `S050 RF-9 las diferencias de cota de todos los casos coinciden con las de la referencia`() {
        // El eje A tiene peralte en curva; el B gira alrededor del borde derecho con una sola fila de peralte.
        val tolerance = 1e-9
        var compared = 0
        for ((key, code, expected) in SectionFixtures.typeRows()) {
            val (axis, chainage) = key
            val template = SectionFixtures.template(axis) ?: continue
            val section = SectionBuilder.build(template, SectionFixtures.superelevation(axis), chainage)
            val point = section.points.first { it.code == code }
            point.offsetM shouldBe (expected.first plusOrMinus tolerance)
            point.heightM shouldBe (expected.second plusOrMinus tolerance)
            compared++
        }
        (compared > 50) shouldBe true
    }

    @Test
    fun `S050 RF-2 la seccion es relativa de diseno con los puntos de izquierda a derecha`() {
        val section = SectionBuilder.build(flat, null, 10.0)

        section.kind shouldBe SectionKind.DESIGN
        section.heightKind shouldBe HeightKind.RELATIVE
        section.chainage shouldBe 10.0
        section.points.map { it.code } shouldBe listOf("BI", "EJE", "BD")
        section.points.map { it.offsetM } shouldBe listOf(-3.5, 0.0, 3.5)
    }

    @Test
    fun `S050 RF-9 un bombeo de 2 por ciento baja 7 cm en cada borde`() {
        val section = SectionBuilder.build(flat, null, 10.0)

        section.points.map { it.heightM }.zip(listOf(-0.07, 0.0, -0.07)).forEach { (a, b) -> a shouldBe (b plusOrMinus 1e-12) }
    }

    @Test
    fun `S050 RF-9 con peralte el borde exterior baja y el interior sube`() {
        val sup = Superelevation(listOf(SuperelevationRow(0.0, -0.06, 0.06), SuperelevationRow(100.0, -0.06, 0.06)))

        val section = SectionBuilder.build(flat, sup, 50.0)

        section.points.first { it.code == "BI" }.heightM shouldBe (0.21 plusOrMinus 1e-12)
        section.points.first { it.code == "BD" }.heightM shouldBe (-0.21 plusOrMinus 1e-12)
    }

    @Test
    fun `S050 RF-9 el peralte varia linealmente entre dos filas`() {
        val sup = Superelevation(listOf(SuperelevationRow(0.0, 0.02, 0.02), SuperelevationRow(100.0, -0.06, 0.06)))

        val section = SectionBuilder.build(flat, sup, 50.0) // izquierda -2 %, derecha +4 %

        section.points.first { it.code == "BI" }.heightM shouldBe (3.5 * 0.02 plusOrMinus 1e-12)
        section.points.first { it.code == "BD" }.heightM shouldBe (-3.5 * 0.04 plusOrMinus 1e-12)
    }

    @Test
    fun `S050 RF-9 con una sola fila su pendiente se mantiene en todo el eje`() {
        val sup = Superelevation(listOf(SuperelevationRow(50.0, 0.05, 0.05)))

        for (pk in listOf(0.0, 50.0, 100.0)) {
            SectionBuilder.build(flat, sup, pk).points.first { it.code == "BD" }.heightM shouldBe (-3.5 * 0.05 plusOrMinus 1e-12)
        }
    }

    @Test
    fun `S050 RF-9 con dos o mas filas fuera de la tabla vale la pendiente propia del tramo`() {
        val sup = Superelevation(listOf(SuperelevationRow(20.0, 0.05, 0.05), SuperelevationRow(40.0, 0.06, 0.06)))

        SectionBuilder.build(flat, sup, 10.0).points.first { it.code == "BD" }.heightM shouldBe (-0.07 plusOrMinus 1e-12)
        SectionBuilder.build(flat, sup, 90.0).points.first { it.code == "BD" }.heightM shouldBe (-0.07 plusOrMinus 1e-12)
    }

    @Test
    fun `S050 RF-9 un tramo que no sigue el peralte conserva su pendiente propia`() {
        val template = flat.copy(left = listOf(segment("BI", 3.5, 0.02, true), segment("CI", 1.0, -0.5)))
        val sup = Superelevation(listOf(SuperelevationRow(0.0, -0.06, 0.06)))

        val section = SectionBuilder.build(template, sup, 10.0)

        // BI sube 0,21; CI sigue su -50 % propio: sube otros 0,5.
        section.points.first { it.code == "CI" }.heightM shouldBe (0.21 + 0.5 plusOrMinus 1e-12)
    }

    @Test
    fun `S050 RF-9 la seccion gira alrededor del punto de rasante que queda a cota cero`() {
        val template = flat.copy(gradeCode = "BD")
        val sup = Superelevation(listOf(SuperelevationRow(0.0, -0.04, 0.04)))

        val section = SectionBuilder.build(template, sup, 10.0)

        section.points.first { it.code == "BD" }.heightM shouldBe (0.0 plusOrMinus 1e-12)
        section.points.first { it.code == "EJE" }.heightM shouldBe (3.5 * 0.04 plusOrMinus 1e-12)
        section.points.first { it.code == "BI" }.heightM shouldBe (0.14 + 0.14 plusOrMinus 1e-12)
    }

    @Test
    fun `S050 RF-2 los extremos del rango estan incluidos`() {
        SectionBuilder.build(flat, null, 0.0).points.size shouldBe 3
        SectionBuilder.build(flat, null, 100.0).points.size shouldBe 3
    }
}
