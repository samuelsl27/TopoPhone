package es.topophone.core.survey.geometry

import es.topophone.core.survey.geometry.GeometryFixtures.number
import es.topophone.core.survey.geometry.GeometryFixtures.optional
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Longitudes, azimut, desnivel y pendiente en cuadrícula frente a pyproj (S026/RF-1, RF-2, RF-3, RF-7). */
class GeometryCalculatorGridTest {

    private val tolM = 1e-6
    private val tolRad = 1e-9

    private fun info(case: String): GeometryInfo = (
        GeometryCalculator.of(
            GeometryFixtures.vertices(case),
            GeometryFixtures.closed(case),
            SegmentScale.Grid,
        ) as GeometryResult.Info
        ).info

    private val projected = listOf("recta", "quebrada", "vertical", "sin_cota", "sin_elip", "concavo", "cruces", "parcela")

    @Test
    fun `S026 RF-2 cada segmento coincide con la referencia`() {
        for (case in projected) {
            val result = info(case)
            val expected = GeometryFixtures.segments(case, "grid")
            result.segments.size shouldBe expected.size
            result.segments.zip(expected).forEach { (s, e) ->
                s.index shouldBe e.getValue("indice").toInt()
                s.from shouldBe e.getValue("desde").toInt()
                s.to shouldBe e.getValue("hasta").toInt()
                s.chainage shouldBe (e.number("pk") plusOrMinus tolM)
                s.length2D shouldBe (e.number("longitud_2d") plusOrMinus tolM)
                s.azimuthRad shouldBe (e.number("azimut_rad") plusOrMinus tolRad)
                s.length3D?.let { it shouldBe (e.number("longitud_3d") plusOrMinus tolM) } ?: (e.optional("longitud_3d") shouldBe null)
                s.deltaHeight?.let { it shouldBe (e.number("desnivel") plusOrMinus tolM) } ?: (e.optional("desnivel") shouldBe null)
                s.slopePercent?.let { it shouldBe (e.number("pendiente_pct") plusOrMinus 1e-7) }
                    ?: (e.optional("pendiente_pct") shouldBe null)
            }
        }
    }

    @Test
    fun `S026 RF-1 la longitud 2D y la 3D totales coinciden con la referencia`() {
        for (case in projected) {
            val total = GeometryFixtures.total(case, "grid")
            val result = info(case)
            result.length2D shouldBe (total.number("longitud_2d") plusOrMinus tolM)
            result.length3D?.let { it shouldBe (total.number("longitud_3d") plusOrMinus tolM) }
                ?: (total.optional("longitud_3d") shouldBe null)
        }
    }

    @Test
    fun `S026 RF-1 cuenta vertices y segmentos y un poligono tiene el segmento de cierre`() {
        info("quebrada").vertexCount shouldBe 6
        info("quebrada").segmentCount shouldBe 5
        info("concavo").vertexCount shouldBe 6
        info("concavo").segmentCount shouldBe 6
        info("concavo").segments.last().to shouldBe 0
    }

    @Test
    fun `S026 RF-3 sin altura en un vertice no hay 3D total ni desnivel de los segmentos afectados`() {
        val result = info("sin_cota")
        result.length3D shouldBe null
        result.missingHeightSegments shouldBe listOf(0, 1)
        result.segments[0].length3D shouldBe null
        result.segments[0].slopePercent shouldBe null
        (result.segments[2].length3D != null) shouldBe true
        result.length2D shouldBe (GeometryFixtures.total("sin_cota", "grid").number("longitud_2d") plusOrMinus tolM)
    }

    @Test
    fun `S026 RF-2 un segmento vertical no tiene pendiente`() {
        val vertical = info("vertical").segments[1]
        vertical.length2D shouldBe 0.0
        vertical.deltaHeight!! shouldBe (10.0 plusOrMinus tolM)
        vertical.slopePercent shouldBe null
    }

    @Test
    fun `S026 RF-7 menos de dos vertices no se calcula`() {
        GeometryCalculator.of(emptyList(), false, SegmentScale.Grid) shouldBe GeometryResult.TooFewVertices
        GeometryCalculator.of(GeometryFixtures.vertices("recta").take(1), false, SegmentScale.Grid) shouldBe GeometryResult.TooFewVertices
        GeometryCalculator.of(GeometryFixtures.vertices("recta"), true, SegmentScale.Grid) shouldBe GeometryResult.TooFewVertices
    }

    @Test
    fun `S026 RF-1 una linea abierta no tiene perimetro ni area`() {
        info("quebrada").perimeter shouldBe null
        info("quebrada").area shouldBe null
    }
}
