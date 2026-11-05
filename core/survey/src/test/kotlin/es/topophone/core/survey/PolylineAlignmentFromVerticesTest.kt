package es.topophone.core.survey

import es.topophone.core.model.alignment.AlignmentIssue
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** Un eje desde los vértices de una línea del proyecto, con alturas en solo algunos de ellos (S027/RF-17). */
class PolylineAlignmentFromVerticesTest {

    private val east = doubleArrayOf(0.0, 30.0, 60.0, 90.0)
    private val north = doubleArrayOf(0.0, 40.0, 80.0, 120.0)

    private fun built(result: PolylineResult) = result.shouldBeInstanceOf<PolylineResult.Built>()

    @Test
    fun `S027 RF-17 desde los vertices da los mismos tramos y la misma rasante que desde un elemento del dibujo`() {
        val z = doubleArrayOf(10.0, 12.0, 15.0, 20.0)
        val fromEntity = built(PolylineAlignmentBuilder.build(CadEntity(CadEntityKind.POLYLINE, east, north, z, null), 0.0, 0.0))

        val fromVertices = built(PolylineAlignmentBuilder.buildFromVertices(east, north, z, 0.0, 0.0))

        fromVertices.elements.map { it.length } shouldBe fromEntity.elements.map { it.length }
        fromVertices.profile.shouldNotBeNull().map { it.chainage to it.elevation } shouldBe
            fromEntity.profile.shouldNotBeNull().map { it.chainage to it.elevation }
    }

    @Test
    fun `S027 RF-17 los vertices son tramos rectos desde el primero con el PK inicial dado`() {
        val result = built(PolylineAlignmentBuilder.buildFromVertices(east, north, null, 0.0, 0.0, startChainage = 100.0))

        result.elements.size shouldBe 3
        result.elements.map { it.length } shouldBe listOf(50.0, 50.0, 50.0)
        result.elements.first().startChainage shouldBe (100.0 plusOrMinus 1e-9)
        result.profile.shouldBeNull()
    }

    @Test
    fun `S027 RF-17 el inicio del eje es el extremo mas cercano al punto dado`() {
        val result = built(PolylineAlignmentBuilder.buildFromVertices(east, north, doubleArrayOf(1.0, 2.0, 3.0, 4.0), 95.0, 120.0))

        result.profile.shouldNotBeNull().map { it.elevation } shouldBe listOf(4.0, 3.0, 2.0, 1.0)
    }

    @Test
    fun `S027 RF-17 con alturas en solo algunos vertices la rasante usa solo esos, en su PK, sin acuerdos`() {
        val z = doubleArrayOf(Double.NaN, 10.0, Double.NaN, 20.0)

        val result = built(PolylineAlignmentBuilder.buildFromVertices(east, north, z, 0.0, 0.0))

        result.elements.size shouldBe 3
        val profile = result.profile.shouldNotBeNull()
        profile.map { it.chainage } shouldBe listOf(50.0, 150.0)
        profile.map { it.elevation } shouldBe listOf(10.0, 20.0)
    }

    @Test
    fun `S027 RF-17 con una sola altura no hay rasante`() {
        val z = doubleArrayOf(Double.NaN, 10.0, Double.NaN, Double.NaN)

        built(PolylineAlignmentBuilder.buildFromVertices(east, north, z, 0.0, 0.0)).profile.shouldBeNull()
    }

    @Test
    fun `S027 RF-17 sin dos vertices distintos no hay eje`() {
        val result = PolylineAlignmentBuilder.buildFromVertices(doubleArrayOf(5.0, 5.0), doubleArrayOf(7.0, 7.0), null, 0.0, 0.0)

        result.shouldBeInstanceOf<PolylineResult.Failed>().issue shouldBe AlignmentIssue.NotAPolyline
    }
}
