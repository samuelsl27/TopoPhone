package es.topophone.core.survey

import es.topophone.core.model.cad.CadEntityKind
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Los vértices de "Por segmentos" según el tipo de línea (S041/RF-6 a RF-9, RF-33, RF-34). */
class LineVerticesTest {

    private fun line(n: Int, closed: Boolean = false, original: BooleanArray? = null, z: Boolean = false) = StakeLine.create(
        DoubleArray(n) { it * 10.0 },
        DoubleArray(n) { if (it % 2 == 0) 0.0 else 5.0 },
        if (z) DoubleArray(n) { 100.0 + it } else null,
        closed,
        original,
    )!!

    @Test
    fun `S041 RF-8 una polilinea ofrece todos sus vertices con su numero y su PK`() {
        val v = LineVertices.of(line(4, z = true), CadEntityKind.POLYLINE)

        v.size shouldBe 4
        v.entries.map { it.number } shouldBe listOf(1, 2, 3, 4)
        v.entries.map { it.vertex } shouldBe listOf(0, 1, 2, 3)
        v.entries[0].chainageM shouldBe 0.0
        v.entries[1].chainageM shouldBe (Math.hypot(10.0, 5.0))
        v.entries[2].point shouldBe PlanePoint(20.0, 0.0)
        v.entries[3].heightM shouldBe 103.0
        v.needsReimport shouldBe false
    }

    @Test
    fun `S041 RF-33 de una polilinea con tramos curvos solo se ofrecen los vertices originales del DXF`() {
        val marks = booleanArrayOf(true, false, false, true, true)

        val v = LineVertices.of(line(5, original = marks), CadEntityKind.POLYLINE)

        v.entries.map { it.vertex } shouldBe listOf(0, 3, 4)
        v.entries.map { it.number } shouldBe listOf(1, 2, 3)
        v.entries[1].chainageM shouldBe line(5).chainageOf(3)
        v.needsReimport shouldBe false
    }

    @Test
    fun `S041 RF-9 de un arco o una spline solo se ofrecen sus dos extremos`() {
        val marks = BooleanArray(8) { it == 0 || it == 7 }

        listOf(CadEntityKind.ARC, CadEntityKind.SPLINE).forEach { kind ->
            val v = LineVertices.of(line(8, original = marks), kind)

            v.entries.map { it.vertex } shouldBe listOf(0, 7)
            v.needsReimport shouldBe false
        }
    }

    @Test
    fun `S041 RF-9 de un circulo solo se ofrece su punto de partida`() {
        val v = LineVertices.of(line(8, closed = true, original = BooleanArray(8) { it == 0 }), CadEntityKind.CIRCLE)

        v.entries.map { it.vertex } shouldBe listOf(0)
        v.needsReimport shouldBe false
    }

    @Test
    fun `S041 RF-34 un arco de un dibujo anterior ofrece todos sus vertices y avisa de que hay que volver a importar`() {
        listOf(CadEntityKind.ARC, CadEntityKind.SPLINE, CadEntityKind.CIRCLE).forEach { kind ->
            val v = LineVertices.of(line(8, closed = kind == CadEntityKind.CIRCLE), kind)

            v.size shouldBe 8
            v.needsReimport shouldBe true
        }
    }

    @Test
    fun `S041 RF-34 un arco de dos vertices o una polilinea sin marcas no avisan de nada`() {
        LineVertices.of(line(2), CadEntityKind.ARC).needsReimport shouldBe false
        LineVertices.of(line(6), CadEntityKind.POLYLINE).needsReimport shouldBe false
        LineVertices.of(line(2), CadEntityKind.LINE).needsReimport shouldBe false
    }

    @Test
    fun `S041 RF-7 Siguiente y Anterior no pasan de los extremos de una linea abierta`() {
        val v = LineVertices.of(line(4), CadEntityKind.POLYLINE)

        v.next(0) shouldBe 1
        v.next(2) shouldBe 3
        v.next(3) shouldBe 3
        v.previous(3) shouldBe 2
        v.previous(0) shouldBe 0
    }

    @Test
    fun `S041 RF-7 en una linea cerrada tras el ultimo vertice vuelve al primero`() {
        val v = LineVertices.of(line(4, closed = true), CadEntityKind.POLYLINE)

        v.next(3) shouldBe 0
        v.next(0) shouldBe 1
        v.previous(0) shouldBe 0
    }

    @Test
    fun `S041 RF-7 en un circulo Siguiente se queda en el unico vertice`() {
        val v = LineVertices.of(line(8, closed = true, original = BooleanArray(8) { it == 0 }), CadEntityKind.CIRCLE)

        v.next(0) shouldBe 0
        v.previous(0) shouldBe 0
    }

    @Test
    fun `S041 RF-8 se puede elegir cualquier vertice de la lista y saber su posicion`() {
        val v = LineVertices.of(line(5, original = booleanArrayOf(true, false, true, false, true)), CadEntityKind.POLYLINE)

        v.indexOfVertex(2) shouldBe 1
        v.indexOfVertex(1) shouldBe null
        v.entries[v.indexOfVertex(4)!!].point shouldBe PlanePoint(40.0, 0.0)
    }
}
