package es.topophone.core.formats.landxml

import es.topophone.core.model.alignment.AlignmentIssue
import es.topophone.core.model.alignment.ElementKind
import es.topophone.core.model.alignment.ProfileOmission
import es.topophone.core.model.alignment.VerticalCurve
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Los tramos y la rasante de un LandXML frente a `fixtures/ejes/` (S049/RF-4, RF-5, RF-10, decisión D3). */
class LandXmlAlignmentReadTest {
    private fun read(name: String, vararg indices: Int) = LandXmlAlignmentReader.read(AlignmentFixtures.stream(name), indices.toSet())

    @Test
    fun `S049 RF-4 los tramos leidos coinciden con los esperados de tools`() {
        val expected = AlignmentFixtures.rows("esperado-tramos.csv")
        val axes = read("ejes.xml", 0, 1, 2)

        axes.map { it.name } shouldBe listOf("Eje A", "Eje B", "Eje C")
        for (axis in axes) {
            axis.issue.shouldBeNull()
            val rows = expected.filter { it[0] == axis.name }
            axis.elements.size shouldBe rows.size
            for ((e, row) in axis.elements.zip(rows)) {
                e.kind shouldBe when (row[2]) {
                    "line" -> ElementKind.LINE
                    "arc" -> ElementKind.ARC
                    else -> ElementKind.CLOTHOID
                }
                e.startChainage shouldBe (row[3].toDouble() plusOrMinus 1e-9)
                e.length shouldBe (row[4].toDouble() plusOrMinus 1e-9)
                e.startCurvature shouldBe (row[5].toDouble() plusOrMinus 1e-12)
                e.endCurvature shouldBe (row[6].toDouble() plusOrMinus 1e-12)
                e.startEasting shouldBe (row[7].toDouble() plusOrMinus 1e-9)
                e.startNorthing shouldBe (row[8].toDouble() plusOrMinus 1e-9)
                Math.toDegrees(e.startAzimuth) shouldBe (row[11].toDouble() plusOrMinus 1e-9)
            }
            axis.declaredEnds.size shouldBe axis.elements.size
        }
    }

    @Test
    fun `S049 RF-4 las coordenadas de LandXML son Norte Este y se guardan como Este Norte`() {
        val b = read("ejes.xml", 1).single()

        b.elements.first().startEasting shouldBe 440500.0
        b.elements.first().startNorthing shouldBe 4474300.0
        b.startStation shouldBe 1000.5
        b.elements.first().startChainage shouldBe 1000.5
        b.declaredEnds.last()!!.first.isFinite() shouldBe true
    }

    @Test
    fun `S049 RF-4 solo se leen las alineaciones pedidas`() {
        read("ejes.xml", 2).map { it.name } shouldBe listOf("Eje C")
        read("ejes.xml").size shouldBe 0
    }

    @Test
    fun `S049 RF-4 la rasante trae sus vertices y los acuerdos parabolicos y circulares`() {
        val a = read("ejes.xml", 0).single().profile.shouldNotBeNull()

        a.map { it.chainage } shouldBe listOf(0.0, 150.0, 300.0, 420.0)
        a.map { it.elevation } shouldBe listOf(100.0, 103.0, 101.0, 102.0)
        (a[1].curve as VerticalCurve.Parabola).length shouldBe (100.0 plusOrMinus 1e-9)
        val circle = a[2].curve as VerticalCurve.Circular
        circle.radius shouldBe 2000.0
        circle.length shouldBe (43.3313674685632 plusOrMinus 1e-9)
        a[0].curve shouldBe VerticalCurve.None
        read("ejes.xml", 2).single().profile.shouldBeNull()
    }

    @Test
    fun `S049 RF-4 en pies los valores quedan en la unidad del fichero`() {
        val a = read("eje-a-pies.xml", 0).single()

        a.elements.first().length shouldBe (120.0 / 0.3048 plusOrMinus 1e-9)
        a.elements[1].endCurvature shouldBe (-0.3048 / 150.0 plusOrMinus 1e-12) // 1/R con R en pies
    }

    @Test
    fun `S049 RF-5 un tipo de tramo no admitido da el numero del tramo y como lo llama el fichero`() {
        read("eje-irregular.xml", 0).single().issue shouldBe AlignmentIssue.UnsupportedElement(1, "IrregularLine")
        read("eje-espiral-cubica.xml", 0).single().issue shouldBe AlignmentIssue.UnsupportedElement(2, "cubic")
    }

    @Test
    fun `S049 RF-5 un hueco entre tramos se lee tal cual para que lo vea el validador`() {
        val a = read("eje-hueco.xml", 0).single()

        a.issue.shouldBeNull()
        a.elements.size shouldBe 5
        a.elements[1].startNorthing - read("ejes.xml", 0).single().elements[1].startNorthing shouldBe (0.05 plusOrMinus 1e-6)
    }

    @Test
    fun `S049 D3 un eje con ecuaciones de PK no se importa`() {
        read("eje-ecuacion-pk.xml", 0).single().issue shouldBe AlignmentIssue.StationEquation
    }

    @Test
    fun `S049 D3 una rasante con un acuerdo asimetrico se omite y el eje se lee`() {
        val a = read("eje-acuerdo-asimetrico.xml", 0).single()

        a.issue.shouldBeNull()
        a.elements.size shouldBe 5
        a.profile.shouldBeNull()
        a.profileOmission shouldBe ProfileOmission.UnsupportedCurve("UnsymParaCurve")
    }

    @Test
    fun `S049 RF-10 las rasantes erroneas se leen para que las rechace el validador`() {
        val bad = read("eje-rasante-mala.xml", 0, 1, 2)

        bad.map { it.name } shouldBe listOf("Repetido", "FueraDelEje", "NoCabe")
        bad[0].profile!!.map { it.chainage } shouldBe listOf(0.0, 100.0, 100.0, 200.0)
        bad[1].profile!!.last().chainage shouldBe 250.0
        (bad[2].profile!![1].curve as VerticalCurve.Parabola).length shouldBe 36.0
    }
}
