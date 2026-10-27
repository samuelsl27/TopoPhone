package es.topophone.core.survey

import es.topophone.core.model.alignment.ElementKind
import es.topophone.core.model.alignment.VerticalCurve
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** El eje escrito por vértices con radio y clotoides frente a `fixtures/ejes/` (S049/RF-8, constitución nº 2). */
class PiAlignmentBuilderTest {
    private fun fixtureVertices() = SurveyFixtures.rows("pi-vertices.csv", "ejes").map {
        PiVertex(it[1].toDouble(), it[2].toDouble(), it[3].toDouble(), it[4].toDouble())
    }

    private fun built(vertices: List<PiVertex>, start: Double = 0.0) =
        PiAlignmentBuilder.build(vertices, start).shouldBeInstanceOf<PiResult.Built>().elements

    @Test
    fun `S049 RF-8 los tramos de un eje por vertices coinciden con los de tools a menos de 1 mm`() {
        val expected = SurveyFixtures.rows("esperado-pi.csv", "ejes")

        val elements = built(fixtureVertices(), 250.0)

        elements.size shouldBe expected.size
        for ((e, row) in elements.zip(expected)) {
            e.kind shouldBe when (row[1]) {
                "line" -> ElementKind.LINE
                "arc" -> ElementKind.ARC
                else -> ElementKind.CLOTHOID
            }
            e.startChainage shouldBe (row[2].toDouble() plusOrMinus 1e-6)
            e.length shouldBe (row[3].toDouble() plusOrMinus 1e-6)
            e.startCurvature shouldBe (row[4].toDouble() plusOrMinus 1e-12)
            e.endCurvature shouldBe (row[5].toDouble() plusOrMinus 1e-12)
            e.startEasting shouldBe (row[6].toDouble() plusOrMinus 1e-6)
            e.startNorthing shouldBe (row[7].toDouble() plusOrMinus 1e-6)
            val end = ElementGeometry.stateAt(e, e.length)
            end.easting shouldBe (row[8].toDouble() plusOrMinus 1e-6)
            end.northing shouldBe (row[9].toDouble() plusOrMinus 1e-6)
        }
    }

    @Test
    fun `S049 RF-8 el eje por vertices se encadena y acaba en el ultimo vertice`() {
        val vertices = fixtureVertices()
        val elements = built(vertices)

        AlignmentValidator.validate(elements).shouldBeNull()
        val last = elements.last()
        val end = ElementGeometry.stateAt(last, last.length)
        end.easting shouldBe (vertices.last().easting plusOrMinus 1e-6)
        end.northing shouldBe (vertices.last().northing plusOrMinus 1e-6)
    }

    @Test
    fun `S049 RF-8 sin radio los vertices son esquinas y solo hay rectas`() {
        val elements = built(listOf(PiVertex(0.0, 0.0), PiVertex(100.0, 0.0), PiVertex(100.0, 100.0)))

        elements.map { it.kind } shouldBe listOf(ElementKind.LINE, ElementKind.LINE)
        elements[1].startEasting shouldBe 100.0
    }

    @Test
    fun `S049 RF-8 una curva simple sin clotoides tiene una tangente de R por tan de medio angulo`() {
        // Esquina de 90° a la derecha con R = 50: tangente 50, arco de 50·π/2 y rectas de 50 m.
        val elements = built(listOf(PiVertex(0.0, 0.0), PiVertex(100.0, 0.0, radius = 50.0), PiVertex(100.0, -100.0)))

        elements.map { it.kind } shouldBe listOf(ElementKind.LINE, ElementKind.ARC, ElementKind.LINE)
        elements[0].length shouldBe (50.0 plusOrMinus 1e-9)
        elements[1].length shouldBe (50.0 * Math.PI / 2 plusOrMinus 1e-9)
        elements[1].startCurvature shouldBe (-1.0 / 50.0 plusOrMinus 1e-15)
        elements[2].length shouldBe (50.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S049 RF-8 unos vertices que no permiten el eje dan su motivo`() {
        PiAlignmentBuilder.build(listOf(PiVertex(0.0, 0.0))) shouldBe PiResult.Failed(PiIssue.TooFewVertices)
        PiAlignmentBuilder.build(listOf(PiVertex(0.0, 0.0), PiVertex(0.0, 0.0), PiVertex(5.0, 5.0))) shouldBe
            PiResult.Failed(PiIssue.CoincidentVertices(2))
        PiAlignmentBuilder.build(listOf(PiVertex(0.0, 0.0), PiVertex(100.0, 0.0, radius = -5.0), PiVertex(100.0, 100.0))) shouldBe
            PiResult.Failed(PiIssue.InvalidParameter(2))
        // Dos curvas de R = 50 en esquinas de 90° separadas 60 m: las tangentes suman 100 m.
        PiAlignmentBuilder.build(
            listOf(PiVertex(0.0, 0.0), PiVertex(100.0, 0.0, 50.0), PiVertex(100.0, 60.0, 50.0), PiVertex(200.0, 60.0)),
        ).shouldBeInstanceOf<PiResult.Failed>().issue.shouldBeInstanceOf<PiIssue.TangentsOverlap>()
        // Esquina de 20° con clotoides de 100 m y R = 50: las clotoides giran 1 rad cada una.
        PiAlignmentBuilder.build(
            listOf(PiVertex(0.0, 0.0), PiVertex(100.0, 0.0, 50.0, 100.0), PiVertex(200.0, 36.0)),
        ) shouldBe PiResult.Failed(PiIssue.SpiralTooLong(2))
    }

    @Test
    fun `S049 RF-8 el PK inicial se suma al de cada tramo`() {
        val elements = built(listOf(PiVertex(0.0, 0.0), PiVertex(100.0, 0.0)), 1000.5)

        elements.single().startChainage shouldBe 1000.5
    }

    @Test
    fun `S049 RF-8 el Kv de un vertice da una parabola de Kv por el cambio de pendiente`() {
        val vertices = ProfileKvBuilder.build(
            listOf(KvVertex(300.0, 8.0), KvVertex(0.0, 10.0), KvVertex(100.0, 12.0, kv = 2000.0), KvVertex(200.0, 12.0, kv = 0.0)),
        )

        vertices.map { it.chainage } shouldBe listOf(0.0, 100.0, 200.0, 300.0)
        // g1 = 0,02 y g2 = 0: Δi = 0,02 y la parábola mide 40 m.
        (vertices[1].curve as VerticalCurve.Parabola).length shouldBe (40.0 plusOrMinus 1e-12)
        vertices[2].curve shouldBe VerticalCurve.None
    }

    @Test
    fun `S049 RF-8 un Kv en un extremo o con PK repetidos se deja para que el validador lo rechace`() {
        val ends = ProfileKvBuilder.build(listOf(KvVertex(0.0, 10.0, kv = 500.0), KvVertex(100.0, 12.0)))
        val repeated = ProfileKvBuilder.build(
            listOf(KvVertex(0.0, 10.0), KvVertex(50.0, 11.0, kv = 500.0), KvVertex(50.0, 12.0), KvVertex(100.0, 13.0)),
        )

        ends[0].curve shouldBe VerticalCurve.Parabola(500.0)
        repeated[1].curve shouldBe VerticalCurve.Parabola(500.0)
        ProfileValidator.validate(ends, 0.0, 100.0).shouldNotBeNullIssue()
    }

    private fun Any?.shouldNotBeNullIssue() {
        (this != null) shouldBe true
    }
}
