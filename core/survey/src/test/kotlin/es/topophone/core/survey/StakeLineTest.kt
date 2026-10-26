package es.topophone.core.survey

import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.survey.LineFixtures.number
import es.topophone.core.survey.LineFixtures.optional
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.hypot
import org.junit.jupiter.api.Test

/** La línea para replantear: PK acumulado, cierre, cota interpolada y vértices originales (S041/RF-15, RF-23, RF-33). */
class StakeLineTest {

    private fun line(vararg xy: Double, z: DoubleArray? = null, closed: Boolean = false, original: BooleanArray? = null) = StakeLine.create(
        DoubleArray(xy.size / 2) { xy[it * 2] },
        DoubleArray(xy.size / 2) { xy[it * 2 + 1] },
        z,
        closed,
        original,
    )!!

    @Test
    fun `S041 RF-15 el PK de cada vertice y la longitud salen de las distancias de cuadricula y casan con pyproj`() {
        LineFixtures.rows("lineas.csv").groupBy { it.getValue("linea") }.forEach { (name, vertices) ->
            val line = LineFixtures.line(name)
            vertices.forEachIndexed { i, row -> line.chainageOf(i) shouldBe (row.number("pk") plusOrMinus 1e-6) }
            line.lengthM shouldBe (vertices.first().number("longitud") plusOrMinus 1e-6)
            line.vertexCount shouldBe vertices.size
        }
    }

    @Test
    fun `S041 RF-15 una linea cerrada suma el tramo de vuelta al primer vertice y no repite vertices`() {
        val square = line(0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0, closed = true)

        square.closed shouldBe true
        square.vertexCount shouldBe 4
        square.segmentCount shouldBe 4
        square.lengthM shouldBe 40.0
        square.segmentEnd(3) shouldBe 0
        square.segmentStart(3) shouldBe 3
    }

    @Test
    fun `S041 RF-15 una linea que repite su primer vertice al final es cerrada y pierde el repetido`() {
        val ring = line(0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 0.0)

        ring.closed shouldBe true
        ring.vertexCount shouldBe 3
        ring.lengthM shouldBe (20.0 + hypot(10.0, 10.0) plusOrMinus 1e-12)
    }

    @Test
    fun `S041 RF-15 una abierta no es cerrada y su longitud es la suma de sus tramos`() {
        val open = line(0.0, 0.0, 3.0, 4.0, 3.0, 14.0)

        open.closed shouldBe false
        open.segmentCount shouldBe 2
        open.lengthM shouldBe 15.0
    }

    @Test
    fun `S041 RF-15 los vertices repetidos seguidos se quitan y una linea sin dos vertices distintos no existe`() {
        val dup = line(0.0, 0.0, 0.0, 0.0, 5.0, 0.0)

        dup.vertexCount shouldBe 2
        dup.lengthM shouldBe 5.0
        StakeLine.create(doubleArrayOf(1.0, 1.0), doubleArrayOf(2.0, 2.0), null).shouldBeNull()
        StakeLine.create(doubleArrayOf(1.0), doubleArrayOf(2.0), null).shouldBeNull()
    }

    @Test
    fun `S041 RF-18 el punto de un PK sale de la serie calculada aparte, sobre la linea y sobre la paralela`() {
        val rows = LineFixtures.rows("series.csv")

        rows.isNotEmpty() shouldBe true
        rows.forEach { row ->
            val p = LineFixtures.line(row.getValue("linea")).pointAt(row.number("pk"), row.number("desplazamiento"))
            p.easting shouldBe (row.number("este") plusOrMinus 1e-6)
            p.northing shouldBe (row.number("norte") plusOrMinus 1e-6)
        }
    }

    @Test
    fun `S041 RF-23 la cota se interpola linealmente en el PK y casa con lo calculado aparte`() {
        val rows = LineFixtures.rows("series.csv").filter { it.getValue("cota").isNotEmpty() }

        rows.isNotEmpty() shouldBe true
        rows.forEach { row ->
            val height = LineFixtures.line(row.getValue("linea")).heightAt(row.number("pk")).shouldNotBeNull()
            height shouldBe (row.optional("cota")!! plusOrMinus 1e-8)
        }
    }

    @Test
    fun `S041 RF-23 sin cota la altura de un PK es null y la linea no tiene cota`() {
        val flat = LineFixtures.line("recta")

        flat.hasHeight shouldBe false
        flat.heightAt(10.0).shouldBeNull()
        flat.height(0).shouldBeNull()
        LineFixtures.line("recta-cota").hasHeight shouldBe true
    }

    @Test
    fun `S041 RF-23 en un vertice la cota es la de ese vertice y entre dos vertices la proporcional`() {
        val sloped = line(0.0, 0.0, 10.0, 0.0, 20.0, 0.0, z = doubleArrayOf(100.0, 102.0, 108.0))

        sloped.heightAt(0.0) shouldBe 100.0
        sloped.heightAt(5.0) shouldBe 101.0
        sloped.heightAt(10.0) shouldBe 102.0
        sloped.heightAt(15.0) shouldBe 105.0
        sloped.heightAt(20.0) shouldBe 108.0
    }

    @Test
    fun `S041 RF-23 en una cerrada la cota del tramo de vuelta va del ultimo vertice al primero`() {
        val ring = line(0.0, 0.0, 10.0, 0.0, 10.0, 10.0, closed = true, z = doubleArrayOf(10.0, 20.0, 30.0))

        ring.heightAt(ring.lengthM) shouldBe (10.0 plusOrMinus 1e-9)
        ring.heightAt(20.0 + (ring.lengthM - 20.0) / 2) shouldBe (20.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S041 RF-18 un PK justo en un vertice usa el tramo que empieza ahi y el final usa el ultimo`() {
        val l = line(0.0, 0.0, 10.0, 0.0, 10.0, 10.0)

        l.segmentAt(0.0) shouldBe 0
        l.segmentAt(10.0) shouldBe 1
        l.segmentAt(20.0) shouldBe 1
        l.segmentAt(-5.0) shouldBe 0
        l.segmentAt(99.0) shouldBe 1
        val p = l.pointAt(10.0, 2.0)
        p.easting shouldBe (12.0 plusOrMinus 1e-12)
        p.northing shouldBe (0.0 plusOrMinus 1e-12)
    }

    @Test
    fun `S041 RF-11 el desplazamiento positivo es a la derecha del sentido de la linea`() {
        val east = line(0.0, 0.0, 100.0, 0.0)

        east.pointAt(40.0, 3.0).northing shouldBe (-3.0 plusOrMinus 1e-12)
        east.pointAt(40.0, -2.0).northing shouldBe (2.0 plusOrMinus 1e-12)
    }

    @Test
    fun `S041 RF-33 los vertices son originales salvo los marcados y sin marcas todos lo son`() {
        val marked = line(0.0, 0.0, 1.0, 0.0, 2.0, 0.0, 3.0, 0.0, original = booleanArrayOf(true, false, false, true))
        val plain = line(0.0, 0.0, 1.0, 0.0)

        List(4) { marked.isOriginal(it) } shouldBe listOf(true, false, false, true)
        List(2) { plain.isOriginal(it) } shouldBe listOf(true, true)
    }

    @Test
    fun `S041 RF-33 un elemento del dibujo con marcas da una linea con las mismas marcas y un punto no es una linea`() {
        val arc = CadEntity(
            CadEntityKind.ARC,
            doubleArrayOf(0.0, 1.0, 2.0, 3.0),
            doubleArrayOf(0.0, 0.5, 0.5, 0.0),
            null,
            null,
            booleanArrayOf(true, false, false, true),
        )
        val point = CadEntity(CadEntityKind.POINT, doubleArrayOf(1.0), doubleArrayOf(1.0), null, null)

        val line = StakeLine.of(arc).shouldNotBeNull()

        List(4) { line.isOriginal(it) } shouldBe listOf(true, false, false, true)
        StakeLine.of(point).shouldBeNull()
        StakeLine.of(CadEntity(CadEntityKind.BLOCK_INSERT, doubleArrayOf(1.0), doubleArrayOf(1.0), null, null)).shouldBeNull()
    }

    @Test
    fun `S041 RF-33 un circulo cerrado por el repetido conserva su vertice de partida como original`() {
        val circle = CadEntity(
            CadEntityKind.CIRCLE,
            doubleArrayOf(10.0, 0.0, -10.0, 0.0, 10.0),
            doubleArrayOf(0.0, 10.0, 0.0, -10.0, 0.0),
            null,
            null,
            booleanArrayOf(true, false, false, false, false),
        )

        val line = StakeLine.of(circle).shouldNotBeNull()

        line.closed shouldBe true
        line.vertexCount shouldBe 4
        List(4) { line.isOriginal(it) } shouldBe listOf(true, false, false, false)
    }

    @Test
    fun `S041 RF-24 un dibujo sin cotas da una linea sin cota aunque el elemento traiga Z`() {
        val e = CadEntity(CadEntityKind.LINE, doubleArrayOf(0.0, 10.0), doubleArrayOf(0.0, 0.0), doubleArrayOf(5.0, 6.0), null)

        StakeLine.of(e, withHeight = true).shouldNotBeNull().hasHeight shouldBe true
        StakeLine.of(e, withHeight = false).shouldNotBeNull().hasHeight shouldBe false
    }
}
