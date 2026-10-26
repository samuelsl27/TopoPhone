package es.topophone.core.formats.dxf

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import org.junit.jupiter.api.Test

/** Punto, línea, polilíneas, arco y círculo del DXF, con su extrusión (S040/RF-14, RF-22, RF-23). */
class DxfEntitiesTest {

    private class Parsed(val items: List<DxfItem>, val truncated: Boolean) {
        val entities: List<DxfEntity> get() = items.filterIsInstance<DxfItem.Entity>().map { it.entity }
    }

    private fun entitiesOf(bytes: ByteArray): Parsed {
        val tags = DxfTagReader(ByteArrayInputStream(bytes))
        DxfFixtures.skipToEntities(tags)
        val stream = DxfEntityStream(tags)
        val items = generateSequence { stream.next() }.toList()
        return Parsed(items, stream.truncated)
    }

    private fun entitiesOf(name: String) = entitiesOf(DxfFixtures.bytes(name))

    private fun text(vararg lines: String) = (listOf("0", "SECTION", "2", "ENTITIES") + lines + listOf("0", "ENDSEC", "0", "EOF"))
        .joinToString("\n", postfix = "\n").toByteArray()

    private fun vertices(text: String): List<List<Double?>> =
        text.split(";").filter { it.isNotEmpty() }.map { v -> v.split(" ").map { if (it == "_") null else it.toDouble() } }

    private fun expected(file: String) = DxfFixtures.expected("esperado.csv").filter { it["archivo"] == file }

    @Test
    fun `S040 RF-14 los puntos y las lineas de cada version son los esperados`() {
        listOf("r12.dxf", "r2000.dxf", "r2010.dxf", "r2018.dxf").forEach { name ->
            val entities = entitiesOf(name).entities
            val want = expected(name)

            val points = entities.filterIsInstance<DxfPoint>()
            points.map { Triple(it.x, it.y, it.z) } shouldBe want.filter { it["tipo_elemento"] == "POINT" }.take(2).map {
                val v = vertices(it.getValue("vertices")).single()
                Triple(v[0]!!, v[1]!!, v[2])
            }
            val lines = entities.filterIsInstance<DxfLine>()
            lines.map { l -> listOf(l.start, l.end).map { listOf(it.x, it.y, it.z) } } shouldBe
                want.filter { it["tipo_elemento"] == "LINE" }.take(2).map { vertices(it.getValue("vertices")).map { v -> v.take(3) } }
        }
    }

    @Test
    fun `S040 RF-22 una linea y un punto sin Z no traen altura y con Z si, aunque valga 0`() {
        val entities = entitiesOf("cotas.dxf").entities

        (entities[0] as DxfPoint).z shouldBe 100.0
        (entities[1] as DxfPoint).z shouldBe null
        (entities[2] as DxfLine).start.z shouldBe null
        (entities[3] as DxfLine).end.z shouldBe 102.0
        val zero = entitiesOf("todo-z-cero.dxf").entities
        (zero[0] as DxfPoint).z shouldBe 0.0
        (zero[1] as DxfLine).start.z shouldBe 0.0
    }

    @Test
    fun `S040 RF-14 la polilinea 2D del R12 y la LWPOLYLINE traen los mismos vertices y bulges`() {
        val r12 = entitiesOf("r12.dxf").entities.filterIsInstance<DxfPolyline>()
        val r2000 = entitiesOf("r2000.dxf").entities.filterIsInstance<DxfPolyline>()
        val want = expected("r2000.dxf").first { it["tipo_elemento"] == "POLYLINE" }

        for (poly in listOf(r12.first(), r2000.first())) {
            poly.closed shouldBe true
            poly.is3d shouldBe false
            poly.vertices.map { listOf(it.x, it.y, it.bulge) } shouldBe
                listOf(
                    listOf(440030.0, 4474000.0, 0.5),
                    listOf(440060.0, 4474000.0, 0.0),
                    listOf(440060.0, 4474020.0, -0.25),
                    listOf(440030.0, 4474020.0, 0.0),
                )
        }
        // El esperado repite el primer vértice al cerrar: los cuatro vértices más el primero.
        vertices(want.getValue("vertices")).size shouldBe 5
    }

    @Test
    fun `S040 RF-14 la polilinea 3D trae sus alturas`() {
        val poly = entitiesOf("r12.dxf").entities.filterIsInstance<DxfPolyline>()[1]
        val want = vertices(expected("r12.dxf").filter { it["tipo_elemento"] == "POLYLINE" }[1].getValue("vertices"))

        poly.is3d shouldBe true
        poly.hasZ shouldBe true
        poly.vertices.map { listOf(it.x, it.y, it.z) } shouldBe want.map { it.take(3) }
        entitiesOf("r2018.dxf").entities.filterIsInstance<DxfPolyline>().first { it.is3d }.vertices.size shouldBe 3
    }

    @Test
    fun `S040 RF-22 una LWPOLYLINE trae altura solo si tiene elevacion`() {
        val modern = entitiesOf("r2000.dxf").entities.filterIsInstance<DxfPolyline>()
        val elevated = modern.first { it.elevation == 645.0 }

        elevated.hasZ shouldBe true
        elevated.vertices.size shouldBe 3
        modern.first().hasZ shouldBe false
        modern.first().elevation shouldBe null
    }

    @Test
    fun `S040 RF-22 una POLYLINE 2D trae altura solo si su elevacion no es 0`() {
        fun poly(elevation: String) = text(
            "0", "POLYLINE", "8", "C", "66", "1", "10", "0.0", "20", "0.0", "30", elevation, "70", "0",
            "0", "VERTEX", "8", "C", "10", "1.0", "20", "2.0", "30", elevation,
            "0", "VERTEX", "8", "C", "10", "3.0", "20", "4.0", "30", elevation,
            "0", "SEQEND", "8", "C",
        )

        (entitiesOf(poly("5.0")).entities.single() as DxfPolyline).hasZ shouldBe true
        (entitiesOf(poly("0.0")).entities.single() as DxfPolyline).hasZ shouldBe false
    }

    @Test
    fun `S040 RF-14 el arco y el circulo traen centro radio y angulos`() {
        val arc = entitiesOf("r2000.dxf").entities.filterIsInstance<DxfArc>().single()
        val circles = entitiesOf("r2000.dxf").entities.filterIsInstance<DxfCircle>()
        val want = expected("r2000.dxf").first { it["tipo_elemento"] == "ARC" }.getValue("curva").split(" ")

        arc.centerX shouldBe want[1].toDouble()
        arc.centerY shouldBe want[2].toDouble()
        arc.centerZ shouldBe want[3].toDouble()
        arc.radius shouldBe want[4].toDouble()
        arc.startDegrees shouldBe want[5].toDouble()
        arc.endDegrees shouldBe want[6].toDouble()
        arc.normal shouldBe Triple(0.0, 0.0, 1.0)
        circles.map { it.radius } shouldBe listOf(25.0, 0.005)
        circles.first().hasZ shouldBe true
    }

    @Test
    fun `S040 RF-14 la extrusion 0 0 -1 invierte el Este de los arcos los circulos y las polilineas`() {
        val entities = entitiesOf("extrusion-espejo.dxf").entities
        val want = expected("extrusion-espejo.dxf")
        val arc = entities[0] as DxfArc
        val circle = entities[1] as DxfCircle
        val poly = entities[2] as DxfPolyline

        arc.normal shouldBe Triple(0.0, 0.0, -1.0)
        DxfOcs.toWcs(arc.centerX, arc.centerY, arc.centerZ, arc.normal).toList() shouldBe
            want[0].getValue("curva").split(" ").slice(1..3).map { it.toDouble() }
        DxfOcs.toWcs(circle.centerX, circle.centerY, circle.centerZ, circle.normal).first shouldBe 440060.0
        poly.vertices.map { DxfOcs.toWcs(it.x, it.y, 0.0, poly.normal).let { w -> listOf(w.first, w.second) } } shouldBe
            vertices(want[2].getValue("vertices")).map { it.take(2) }
    }

    @Test
    fun `S040 RF-14 el eje arbitrario con una normal cualquiera da lo que se calcula a mano`() {
        // N = (1, 0, 0): Ax = (0, 1, 0), Ay = (0, 0, 1) y el punto (1, 2, 3) pasa a (3, 1, 2).
        DxfOcs.toWcs(1.0, 2.0, 3.0, Triple(1.0, 0.0, 0.0)) shouldBe Triple(3.0, 1.0, 2.0)
        DxfOcs.toWcs(1.0, 2.0, 3.0, Triple(0.0, 0.0, 1.0)) shouldBe Triple(1.0, 2.0, 3.0)
        DxfOcs.toWcs(1.0, 2.0, 3.0, Triple(0.0, 0.0, -1.0)) shouldBe Triple(-1.0, 2.0, -3.0)
        // N = (0, 1, 0): Ax = Wz × N = (-1, 0, 0), Ay = N × Ax = (0, 0, 1) y (1, 2, 3) pasa a (-1, 3, 2).
        DxfOcs.toWcs(1.0, 2.0, 3.0, Triple(0.0, 1.0, 0.0)) shouldBe Triple(-1.0, 3.0, 2.0)
        val tilted = DxfOcs.toWcs(1.0, 0.0, 0.0, Triple(1.0, 1.0, 1.0))
        val length = Math.sqrt(tilted.first * tilted.first + tilted.second * tilted.second + tilted.third * tilted.third)
        length shouldBe (1.0 plusOrMinus 1e-12) // un giro no cambia las distancias
    }

    @Test
    fun `S040 RF-14 la capa y el color de cada elemento se leen`() {
        val entities = entitiesOf("capas.dxf").entities

        entities.take(6).map { it.style.layer } shouldBe listOf("Roja", "Apagada", "Congelada", "Blanca", "Verdadera", "Naranja")
        entities[6].style.aci shouldBe 2
        entities[7].style.trueColor shouldBe 0x00AA55
        entities[8].style.trueColor shouldBe 0xFFFFFF
        entities[9].style.aci shouldBe 250
        entities[0].style.aci shouldBe null
    }

    @Test
    fun `S040 RF-16 los tipos que no se importan se dan como ignorados con su nombre`() {
        val items = entitiesOf("ignorados.dxf").items

        items.filterIsInstance<DxfItem.Ignored>().map { it.type } shouldBe
            listOf("TEXT", "MTEXT", "DIMENSION", "HATCH", "ELLIPSE", "SOLID", "3DFACE", "LEADER", "TEXT")
        items.filterIsInstance<DxfItem.Entity>().size shouldBe 2
    }

    @Test
    fun `S040 RF-13 un valor que no es un numero deja ese elemento como roto y los demas siguen`() {
        val items = entitiesOf("elemento-roto.dxf").items

        items.size shouldBe 5
        items[2] shouldBe DxfItem.Broken("LINE")
        items.filterIsInstance<DxfItem.Entity>().size shouldBe 4
    }

    @Test
    fun `S040 RF-13 un elemento al que le faltan datos es un elemento roto`() {
        entitiesOf(text("0", "LINE", "8", "C", "10", "1.0", "20", "2.0")).items shouldBe listOf(DxfItem.Broken("LINE"))
        entitiesOf(text("0", "CIRCLE", "8", "C", "10", "1.0", "20", "2.0")).items shouldBe listOf(DxfItem.Broken("CIRCLE"))
        entitiesOf(text("0", "ARC", "8", "C", "10", "1.0", "20", "2.0", "40", "5.0")).items shouldBe listOf(DxfItem.Broken("ARC"))
    }

    @Test
    fun `S040 RF-13 en un fichero cortado el elemento a medias se descarta y se avisa`() {
        val parsed = entitiesOf("cortado.dxf")

        parsed.entities.size shouldBe 3
        parsed.truncated shouldBe true
    }

    @Test
    fun `S040 RF-13 un fichero completo no esta cortado y uno sin elementos no da ninguno`() {
        entitiesOf("r2000.dxf").truncated shouldBe false
        entitiesOf("sin-elementos.dxf").items shouldBe emptyList()
        entitiesOf("sin-elementos.dxf").truncated shouldBe false
    }

    @Test
    fun `S040 RF-14 una polilinea de malla o de caras se ignora`() {
        val mesh = text(
            "0", "POLYLINE", "8", "C", "66", "1", "70", "16",
            "0", "VERTEX", "8", "C", "10", "1.0", "20", "1.0", "70", "64",
            "0", "SEQEND", "8", "C",
        )

        entitiesOf(mesh).items shouldBe listOf(DxfItem.Ignored("POLYLINE"))
    }

    @Test
    fun `S040 RF-14 los vertices de control de una curva ajustada de una polilinea no cuentan`() {
        val poly = text(
            "0", "POLYLINE", "8", "C", "66", "1", "70", "4",
            "0", "VERTEX", "8", "C", "10", "0.0", "20", "0.0", "70", "0",
            "0", "VERTEX", "8", "C", "10", "5.0", "20", "5.0", "70", "16",
            "0", "VERTEX", "8", "C", "10", "10.0", "20", "0.0", "70", "0",
            "0", "SEQEND", "8", "C",
        )

        (entitiesOf(poly).entities.single() as DxfPolyline).vertices.size shouldBe 2
    }
}
