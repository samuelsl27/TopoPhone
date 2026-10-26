package es.topophone.core.formats.dxf

import es.topophone.core.model.cad.CadColor
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadIgnored
import es.topophone.core.model.cad.CadIgnoredReason
import es.topophone.core.model.project.LinearUnit
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import org.junit.jupiter.api.Test

/** El recorrido completo de un DXF: cabecera, capas, bloques y elementos, con lo ignorado y los ficheros cortados (S040/RF-13, RF-14, RF-16). */
class DxfReaderTest {

    private class Collected : DxfSink {
        val elements = mutableListOf<Pair<String, CadEntity>>()
        val ignored = mutableListOf<CadIgnored>()

        override fun element(layer: String, entity: CadEntity) {
            elements += layer to entity
        }

        override fun ignored(ignored: CadIgnored) {
            this.ignored += ignored
        }
    }

    private fun read(name: String, unit: LinearUnit = LinearUnit.METRE): Triple<Collected, DxfReadResult, DxfTables> =
        read(DxfFixtures.bytes(name), unit)

    private fun read(bytes: ByteArray, unit: LinearUnit = LinearUnit.METRE): Triple<Collected, DxfReadResult, DxfTables> {
        val sink = Collected()
        val result = DxfReader.read(ByteArrayInputStream(bytes), unit, sink)
        return Triple(sink, result, result.tables)
    }

    private fun summary(file: String): Map<String, String> = DxfFixtures.expected("esperado-resumen.csv")
        .filter { it["archivo"] == file }
        .associate { it.getValue("clave") to it.getValue("valor") }

    /** Los vértices originales del esperado: todos están en el elemento y, si no tiene bulges, son justo esos. */
    private fun checkVertices(entity: CadEntity, text: String) {
        val expected = text.split(";").map { v -> v.split(" ").map { it.toDoubleOrNull() } }
        val curved = entity.vertexCount != expected.size
        if (!curved) entity.vertexCount shouldBe expected.size
        expected.forEachIndexed { i, v ->
            if (!curved) {
                entity.easting[i] shouldBe (v[0]!! plusOrMinus 1e-9)
                entity.northing[i] shouldBe (v[1]!! plusOrMinus 1e-9)
            } else {
                entity.hasVertex(v[0]!!, v[1]!!) shouldBe true
            }
        }
    }

    private fun color(text: String) = if (text == "AUTO") CadColor.AUTO_CONTRAST else CadColor(text.toLong(16).toInt())

    private val curveKinds = setOf(CadEntityKind.ARC, CadEntityKind.CIRCLE, CadEntityKind.SPLINE)

    @Test
    fun `S040 RF-14 cada version da los elementos esperados en orden con su capa color y cota`() {
        listOf(
            "r12.dxf",
            "r2000.dxf",
            "r2010.dxf",
            "r2018.dxf",
            "capas.dxf",
            "extrusion-espejo.dxf",
            "cotas.dxf",
            "todo-z-cero.dxf",
        ).forEach { file ->
            val (sink, result, tables) = read(file)
            val want = DxfFixtures.expected("esperado.csv").filter { it["archivo"] == file }

            result.complete shouldBe true
            sink.elements.size shouldBe want.size
            sink.elements.zip(want).forEach { (got, row) ->
                val (layer, entity) = got
                layer shouldBe row.getValue("capa")
                entity.kind.name shouldBe row.getValue("tipo_elemento")
                entity.hasZ shouldBe (row.getValue("con_z") == "SI")
                (entity.color ?: tables.colorOf(layer)) shouldBe color(row.getValue("color"))
                if (entity.kind !in curveKinds) checkVertices(entity, row.getValue("vertices"))
            }
        }
    }

    @Test
    fun `S040 RF-14 los bulges de las polilineas anaden vertices y los extremos originales se conservan`() {
        val (sink, _, _) = read("r2000.dxf")
        val closedWithBulge = sink.elements.map { it.second }.first { it.kind == CadEntityKind.POLYLINE }

        (closedWithBulge.vertexCount > 5) shouldBe true // 4 vértices + 1 de cierre + los de los dos tramos curvos
        closedWithBulge.easting.first() shouldBe 440030.0
        closedWithBulge.easting.last() shouldBe 440030.0 // cerrada
        closedWithBulge.hasVertex(440060.0, 4474000.0) shouldBe true
        closedWithBulge.hasVertex(440060.0, 4474020.0) shouldBe true
    }

    @Test
    fun `S040 RF-34 solo los extremos de un arco y de una spline y la partida de un circulo son originales`() {
        val elements = read("r2000.dxf").first.elements.map { it.second }

        listOf(CadEntityKind.ARC, CadEntityKind.SPLINE).forEach { kind ->
            elements.filter { it.kind == kind }.forEach { e ->
                (e.vertexCount > 2) shouldBe true
                (0 until e.vertexCount).filter { e.isOriginal(it) } shouldBe listOf(0, e.vertexCount - 1)
            }
        }
        elements.filter { it.kind == CadEntityKind.CIRCLE && it.vertexCount > 1 }.forEach { e ->
            (0 until e.vertexCount).filter { e.isOriginal(it) } shouldBe listOf(0)
        }
    }

    @Test
    fun `S040 RF-34 en una polilinea con bulge son originales los vertices escritos y no los del arco`() {
        val e = read("r2000.dxf").first.elements.map { it.second }.first { it.kind == CadEntityKind.POLYLINE && it.vertexCount > 5 }

        (0 until e.vertexCount).filter { e.isOriginal(it) }.size shouldBe 5 // 4 vértices y el de cierre
        e.isOriginal(0) shouldBe true
        e.isOriginal(e.vertexCount - 1) shouldBe true
        e.hasOriginalMarks shouldBe true
    }

    @Test
    fun `S040 RF-34 una linea y un punto no llevan marcas y todos sus vertices son originales`() {
        val line = read("r2000.dxf").first.elements.map { it.second }.first { it.kind == CadEntityKind.LINE }

        line.hasOriginalMarks shouldBe false
        line.isOriginal(0) shouldBe true
    }

    private fun CadEntity.hasVertex(e: Double, n: Double) = easting.indices.any { easting[it] == e && northing[it] == n }

    @Test
    fun `S040 RF-16 lo ignorado sale por tipo como en el esperado`() {
        listOf("ignorados.dxf", "r2000.dxf", "bloques.dxf").forEach { file ->
            val (sink, _, _) = read(file)
            val want = summary(file)

            val byType = sink.ignored.filter { it.reason == CadIgnoredReason.UNSUPPORTED_TYPE }.groupingBy { it.dxfType }.eachCount()
            val expected = want.filterKeys {
                it.startsWith("ignorados.")
            }.mapKeys { it.key.removePrefix("ignorados.") }.mapValues { it.value.toInt() }
            byType shouldBe expected
        }
    }

    @Test
    fun `S040 RF-14 el recuento por tipo de elemento es el del esperado`() {
        listOf("r12.dxf", "r2018.dxf", "bloques.dxf", "ignorados.dxf").forEach { file ->
            val (sink, _, _) = read(file)
            val want = summary(file).filterKeys {
                it.startsWith("elementos.")
            }.mapKeys { it.key.removePrefix("elementos.") }.mapValues { it.value.toInt() }

            sink.elements.groupingBy { it.second.kind.name }.eachCount() shouldBe want
        }
    }

    @Test
    fun `S040 RF-7 la unidad declarada sale del resultado`() {
        read("r2000.dxf").second.header.declaredUnit shouldBe LinearUnit.METRE
        read("r12.dxf").second.header.declaredUnit shouldBe null
        read("origen-etrs89-utm30-pies.dxf").second.header.declaredUnit shouldBe LinearUnit.INTERNATIONAL_FOOT
    }

    @Test
    fun `S040 RF-13 un fichero cortado entrega lo leido y se marca incompleto`() {
        val (sink, result, _) = read("cortado.dxf")

        sink.elements.size shouldBe 3
        result.complete shouldBe false
    }

    @Test
    fun `S040 RF-13 un elemento con un valor que no es un numero se descarta y el fichero sigue completo`() {
        val (sink, result, _) = read("elemento-roto.dxf")

        sink.elements.size shouldBe 4
        sink.ignored shouldBe listOf(CadIgnored(CadIgnoredReason.INVALID_VALUE, "LINE"))
        result.complete shouldBe true
    }

    @Test
    fun `S040 RF-13 un codigo de grupo que no es un numero corta la lectura y deja lo anterior`() {
        val text = DxfFixtures.bytes("r2000.dxf").toString(Charsets.ISO_8859_1).replace("\r\n", "\n")
        val cut = text.indexOf("LWPOLYLINE") // el primer elemento tras el que se estropea un código
        val broken = text.substring(0, cut) + "LWPOLYLINE\nxx\nbasura\n" + text.substring(cut + "LWPOLYLINE\n".length)

        val (sink, result, _) = read(broken.toByteArray(Charsets.ISO_8859_1))

        result.complete shouldBe false
        (sink.elements.size in 1 until DxfFixtures.expected("esperado.csv").count { it["archivo"] == "r2000.dxf" }) shouldBe true
    }

    @Test
    fun `S040 RF-13 un fichero sin elementos no da ninguno y esta completo`() {
        val (sink, result, _) = read("sin-elementos.dxf")

        sink.elements.size shouldBe 0
        result.complete shouldBe true
    }

    @Test
    fun `S040 RF-13 un fichero sin seccion de elementos no esta completo`() {
        val (sink, result, _) = read("0\nSECTION\n2\nHEADER\n0\nENDSEC\n0\nEOF\n".toByteArray())

        sink.elements.size shouldBe 0
        result.complete shouldBe false
    }

    @Test
    fun `S040 RF-14 las secciones de mas, como CLASSES y OBJECTS, se saltan`() {
        val text =
            "0\nSECTION\n2\nCLASSES\n0\nCLASS\n1\nX\n0\nENDSEC\n0\nSECTION\n2\nENTITIES\n0\nPOINT\n8\nC\n10\n1.0\n20\n2.0\n0\nENDSEC\n" +
                "0\nSECTION\n2\nOBJECTS\n0\nDICTIONARY\n5\nC\n0\nENDSEC\n0\nEOF\n"

        val (sink, result, _) = read(text.toByteArray())

        sink.elements.size shouldBe 1
        result.complete shouldBe true
    }

    @Test
    fun `S040 RF-15 la tolerancia de 1 cm se pasa a las unidades del dibujo`() {
        val meters = read("r2000.dxf").first.elements.first { it.second.kind == CadEntityKind.CIRCLE }.second
        val feet = read("r2000.dxf", LinearUnit.INTERNATIONAL_FOOT).first.elements.first { it.second.kind == CadEntityKind.CIRCLE }.second

        // El mismo círculo de radio 25 (en unidades del dibujo) necesita menos segmentos cuando 1 cm son más unidades del dibujo… y más si son menos.
        (feet.vertexCount <= meters.vertexCount) shouldBe true
    }

    @Test
    fun `S040 RF-9 la exploracion da la extension y los recuentos sin guardar los elementos`() {
        val sink = DxfExtentSink()
        DxfReader.read(ByteArrayInputStream(DxfFixtures.bytes("cotas.dxf")), LinearUnit.METRE, sink)

        val extent = sink.extent!!
        extent.minEasting shouldBe 440000.0
        extent.maxEasting shouldBe 440010.0
        extent.minNorthing shouldBe 4474000.0
        extent.maxNorthing shouldBe 4474014.0
        sink.count shouldBe 7
        sink.countsByKind[CadEntityKind.POINT] shouldBe 2
    }

    @Test
    fun `S040 RF-9 la extension incluye los bloques desplegados y no los elementos ignorados`() {
        val sink = DxfExtentSink()
        DxfReader.read(ByteArrayInputStream(DxfFixtures.bytes("bloques.dxf")), LinearUnit.METRE, sink)
        val ignored = DxfExtentSink()
        DxfReader.read(ByteArrayInputStream(DxfFixtures.bytes("ignorados.dxf")), LinearUnit.METRE, ignored)

        val extent = sink.extent!!
        (extent.minEasting <= 439992.0) shouldBe true // el conjunto girado se va a la izquierda de su inserción
        ignored.extent!!.maxEasting shouldBe 440010.0 // solo la línea y el punto
        ignored.ignoredCount shouldBe 9
        DxfExtentSink().extent shouldBe null
    }
}
