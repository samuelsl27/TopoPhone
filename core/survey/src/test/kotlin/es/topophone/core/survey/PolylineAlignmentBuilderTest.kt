package es.topophone.core.survey

import es.topophone.core.model.alignment.AlignmentElement
import es.topophone.core.model.alignment.AlignmentIssue
import es.topophone.core.model.alignment.ElementKind
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.jupiter.api.Test

/** Una polilínea de un dibujo como eje, con sus arcos y sus cotas (S049/RF-6, RF-7, decisión D1). */
class PolylineAlignmentBuilderTest {
    private val e0 = 440000.0
    private val n0 = 4474000.0

    private fun polyline(
        e: DoubleArray,
        n: DoubleArray,
        original: BooleanArray? = null,
        z: DoubleArray? = null,
        kind: CadEntityKind = CadEntityKind.POLYLINE,
    ) = CadEntity(kind, e, n, z, null, original)

    private fun built(entity: CadEntity, nearE: Double, nearN: Double, start: Double = 0.0) =
        PolylineAlignmentBuilder.build(entity, nearE, nearN, start).shouldBeInstanceOf<PolylineResult.Built>()

    /** Una recta de 100 m al Este, un arco a la izquierda de radio 80 m y 90° con vértices cada 10°, y otra recta al Norte. */
    private fun withArc(): CadEntity {
        val r = 80.0
        val cx = e0 + 100.0
        val cy = n0 + r
        val es = arrayListOf(e0, e0 + 100.0)
        val ns = arrayListOf(n0, n0)
        val orig = arrayListOf(true, true)
        for (deg in 10..80 step 10) {
            val a = -PI / 2 + Math.toRadians(deg.toDouble())
            es += cx + r * cos(a)
            ns += cy + r * sin(a)
            orig += false
        }
        es += cx + r
        ns += cy
        orig += true
        es += cx + r
        ns += cy + 50.0
        orig += true
        // el vértice (e0+100, n0) es el original que abre el arco; los intermedios van después de él
        return polyline(es.toDoubleArray(), ns.toDoubleArray(), orig.toBooleanArray())
    }

    @Test
    fun `S049 RF-6 una polilinea de rectas da una recta por tramo desde el extremo mas cercano`() {
        val entity = polyline(doubleArrayOf(e0, e0 + 100, e0 + 100), doubleArrayOf(n0, n0, n0 + 50))

        val fromStart = built(entity, e0 - 5, n0 - 5)
        val fromEnd = built(entity, e0 + 100, n0 + 60)

        fromStart.elements.map { it.kind } shouldBe listOf(ElementKind.LINE, ElementKind.LINE)
        fromStart.elements[0].startEasting shouldBe e0
        fromEnd.elements[0].startNorthing shouldBe (n0 + 50)
        fromEnd.elements[0].startAzimuth shouldBe (Math.PI plusOrMinus 1e-12) // hacia el Sur
        fromEnd.elements.last().let { ElementGeometry.stateAt(it, it.length).easting } shouldBe (e0 plusOrMinus 1e-9)
    }

    @Test
    fun `S049 RF-6 el PK inicial se suma y los tramos se encadenan`() {
        val entity = polyline(doubleArrayOf(e0, e0 + 100, e0 + 100), doubleArrayOf(n0, n0, n0 + 50))

        val result = built(entity, e0, n0, start = 1200.0)

        result.elements[0].startChainage shouldBe 1200.0
        result.elements[1].startChainage shouldBe 1300.0
        AlignmentValidator.validate(result.elements).shouldBeNull()
    }

    @Test
    fun `S049 RF-6 los vertices no originales entre dos originales se reconstruyen como un arco exacto`() {
        val result = built(withArc(), e0 - 1, n0)

        result.elements.map { it.kind } shouldBe listOf(ElementKind.LINE, ElementKind.ARC, ElementKind.LINE)
        val arc = result.elements[1]
        arc.startCurvature shouldBe (1.0 / 80.0 plusOrMinus 1e-9) // a la izquierda
        arc.length shouldBe (80.0 * PI / 2 plusOrMinus 1e-6)
        val end = ElementGeometry.stateAt(arc, arc.length)
        end.easting shouldBe (e0 + 180.0 plusOrMinus 1e-6)
        end.northing shouldBe (n0 + 80.0 plusOrMinus 1e-6)
        AlignmentValidator.validate(result.elements).shouldBeNull()
        result.elements[2].startChainage shouldBe (100.0 + 80.0 * PI / 2 plusOrMinus 1e-6)
    }

    @Test
    fun `S049 RF-6 recorrida al reves la misma curva sale a la derecha`() {
        val entity = withArc()
        val last = entity.vertexCount - 1

        val result = built(entity, entity.easting[last], entity.northing[last] + 10)

        result.elements.map { it.kind } shouldBe listOf(ElementKind.LINE, ElementKind.ARC, ElementKind.LINE)
        result.elements[1].startCurvature shouldBe (-1.0 / 80.0 plusOrMinus 1e-9)
        val tail = result.elements.last()
        ElementGeometry.stateAt(tail, tail.length).easting shouldBe (e0 plusOrMinus 1e-6)
    }

    @Test
    fun `S049 RF-6 un trozo curvo que no es un arco, como una spline, no se convierte y dice donde empieza`() {
        // Una onda de 100 m de largo con vértices intermedios no originales.
        val es = DoubleArray(12) { e0 + it * 10.0 }
        val ns = DoubleArray(12) { n0 + 5.0 * sin(it * 0.6) }
        val orig = BooleanArray(12) { it == 0 || it == 11 }

        val issue = PolylineAlignmentBuilder.build(polyline(es, ns, orig), e0, n0).shouldBeInstanceOf<PolylineResult.Failed>().issue

        issue shouldBe AlignmentIssue.NonCircularCurve(1)
    }

    @Test
    fun `S049 RF-6 un elemento que no es una polilinea o sin longitud no se convierte`() {
        val circle = polyline(doubleArrayOf(e0, e0 + 1), doubleArrayOf(n0, n0), kind = CadEntityKind.CIRCLE)
        val point = polyline(doubleArrayOf(e0, e0), doubleArrayOf(n0, n0))

        PolylineAlignmentBuilder.build(circle, e0, n0).shouldBeInstanceOf<PolylineResult.Failed>().issue shouldBe
            AlignmentIssue.NotAPolyline
        PolylineAlignmentBuilder.build(point, e0, n0).shouldBeInstanceOf<PolylineResult.Failed>().issue shouldBe AlignmentIssue.NotAPolyline
    }

    @Test
    fun `S049 RF-7 las cotas de la polilinea dan una rasante sin acuerdos en sus vertices originales`() {
        val entity = polyline(
            doubleArrayOf(e0, e0 + 100, e0 + 100),
            doubleArrayOf(n0, n0, n0 + 50),
            z = doubleArrayOf(100.0, 102.0, 101.0),
        )

        val profile = built(entity, e0, n0, start = 10.0).profile.shouldNotBeNull()

        profile.map { it.chainage } shouldBe listOf(10.0, 110.0, 160.0)
        profile.map { it.elevation } shouldBe listOf(100.0, 102.0, 101.0)
        profile.all { it.curve == es.topophone.core.model.alignment.VerticalCurve.None } shouldBe true
    }

    @Test
    fun `S049 RF-7 en una polilinea con arco la rasante usa solo los vertices originales y el PK de la curva`() {
        val base = withArc()
        val z = DoubleArray(base.vertexCount) { 100.0 + it }
        val entity =
            CadEntity(CadEntityKind.POLYLINE, base.easting, base.northing, z, null, BooleanArray(base.vertexCount) { base.isOriginal(it) })

        val profile = built(entity, e0 - 1, n0).profile.shouldNotBeNull()

        profile.size shouldBe 4 // inicio, fin de la recta, fin del arco y fin
        profile[2].chainage shouldBe (100.0 + 80.0 * PI / 2 plusOrMinus 1e-6)
        profile[2].elevation shouldBe (100.0 + base.vertexCount - 2)
    }

    @Test
    fun `S049 RF-7 sin cotas no hay rasante`() {
        val entity = polyline(doubleArrayOf(e0, e0 + 100), doubleArrayOf(n0, n0))

        built(entity, e0, n0).profile.shouldBeNull()
    }

    @Test
    fun `S049 RF-6 un vertice repetido no da un tramo vacio`() {
        val entity = polyline(doubleArrayOf(e0, e0 + 100, e0 + 100, e0 + 100), doubleArrayOf(n0, n0, n0, n0 + 20))

        val result = built(entity, e0, n0)

        result.elements.size shouldBe 2
        result.elements.all { it: AlignmentElement -> it.length > 0 } shouldBe true
    }
}
