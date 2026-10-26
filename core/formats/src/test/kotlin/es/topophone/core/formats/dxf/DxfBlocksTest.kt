package es.topophone.core.formats.dxf

import es.topophone.core.model.cad.CadColor
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadIgnored
import es.topophone.core.model.cad.CadIgnoredReason
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import org.junit.jupiter.api.Test

/** Los INSERT, los bloques y su despliegue con escala, giro y anidados (S040/RF-14, RF-15, RF-24). */
class DxfBlocksTest {

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

    /** Lee un DXF con las piezas de esta tarea: cabecera, capas, bloques y elementos, sin pasar por el lector completo. */
    private fun expand(bytes: ByteArray, maxBlockVertices: Int = DxfBlocks.MAX_BLOCK_VERTICES): Pair<Collected, DxfTables> {
        val tags = DxfTagReader(ByteArrayInputStream(bytes))
        var tables = DxfTables(emptyMap())
        var blocks: Map<String, DxfBlock> = emptyMap()
        val sink = Collected()
        while (true) {
            val tag = tags.next() ?: break
            if (tag.code != 0 || tag.value != "SECTION") continue
            when (tags.next()?.value) {
                "HEADER" -> DxfHeaderParser.parse(tags)
                "TABLES" -> tables = DxfTables.parse(tags)
                "BLOCKS" -> blocks = DxfBlocks.parse(tags, maxBlockVertices)
                "ENTITIES" -> {
                    val expander = DxfExpander(tables, blocks, CadCurves.TOLERANCE_M, sink)
                    val stream = DxfEntityStream(tags)
                    generateSequence { stream.next() }.forEach { expander.accept(it) }
                }
            }
        }
        return sink to tables
    }

    private fun expand(name: String) = expand(DxfFixtures.bytes(name))

    private fun color(text: String) = if (text == "AUTO") CadColor.AUTO_CONTRAST else CadColor(text.toLong(16).toInt())

    private fun doc(blocks: String, entities: String) = listOf(
        "0\nSECTION\n2\nBLOCKS",
        blocks,
        "0\nENDSEC\n0\nSECTION\n2\nENTITIES",
        entities,
        "0\nENDSEC\n0\nEOF",
    ).filter { it.isNotEmpty() }.joinToString("\n", postfix = "\n").toByteArray()

    private fun block(name: String, vararg inner: String) =
        "0\nBLOCK\n8\n0\n2\n$name\n70\n0\n10\n0.0\n20\n0.0\n30\n0.0\n3\n$name\n${inner.joinToString("\n")}\n0\nENDBLK\n8\n0"

    private fun line(layer: String, x1: Double, y1: Double, x2: Double, y2: Double) =
        "0\nLINE\n8\n$layer\n10\n$x1\n20\n$y1\n11\n$x2\n21\n$y2"

    private fun insert(name: String, x: Double, y: Double, extra: String = "", layer: String = "L") =
        "0\nINSERT\n8\n$layer\n2\n$name\n10\n$x\n20\n$y${if (extra.isEmpty()) "" else "\n$extra"}"

    @Test
    fun `S040 RF-14 el INSERT se lee con su bloque posicion escala giro y rejilla`() {
        val bytes =
            doc("", insert("B", 10.0, 20.0, "41\n2.0\n42\n3.0\n43\n4.0\n50\n30.0\n70\n2\n71\n3\n44\n5.0\n45\n6.0\n210\n0\n220\n0\n230\n-1"))
        val tags = DxfTagReader(ByteArrayInputStream(bytes))
        DxfFixtures.skipToEntities(tags)
        val insert = (DxfEntityStream(tags).next() as DxfItem.Entity).entity as DxfInsert

        insert.blockName shouldBe "B"
        (insert.x to insert.y) shouldBe (10.0 to 20.0)
        insert.z shouldBe null
        insert.scale shouldBe Triple(2.0, 3.0, 4.0)
        insert.rotationDegrees shouldBe 30.0
        (insert.columns to insert.rows) shouldBe (2 to 3)
        (insert.columnSpacing to insert.rowSpacing) shouldBe (5.0 to 6.0)
        insert.normal shouldBe Triple(0.0, 0.0, -1.0)
    }

    @Test
    fun `S040 RF-14 los bloques desplegados son los esperados en capa color cota y vertices`() {
        val (sink, tables) = expand("bloques.dxf")
        val want = DxfFixtures.expected("esperado.csv").filter { it["archivo"] == "bloques.dxf" }

        sink.elements.size shouldBe want.size
        sink.elements.zip(want).forEach { (got, row) ->
            val (layer, entity) = got
            layer shouldBe row.getValue("capa")
            entity.kind.name shouldBe row.getValue("tipo_elemento")
            entity.hasZ shouldBe (row.getValue("con_z") == "SI")
            (entity.color ?: tables.colorOf(layer)) shouldBe color(row.getValue("color"))
            val vertices = row.getValue("vertices").split(";").map { v -> v.split(" ").map { if (it == "_") null else it.toDouble() } }
            entity.vertexCount shouldBe vertices.size
            vertices.forEachIndexed { i, v ->
                entity.easting[i] shouldBe (v[0]!! plusOrMinus 1e-9)
                entity.northing[i] shouldBe (v[1]!! plusOrMinus 1e-9)
                if (v[2] != null) entity.height!![i] shouldBe (v[2]!! plusOrMinus 1e-9)
            }
        }
    }

    @Test
    fun `S040 RF-14 el despliegue calculado a mano de un conjunto con escala 2 y giro de 90 grados`() {
        val (sink, _) = expand("bloques.dxf")
        val lines = sink.elements.filter { it.second.kind == CadEntityKind.LINE }.map { it.second }

        // (0,0)-(1,0) pasa a (0,0)-(0,2) y (1,0)-(1,1) a (0,2)-(-2,2), desde la inserción (440000, 4474000).
        lines[0].easting.toList() shouldBe listOf(440000.0, 440000.0)
        lines[0].northing.toList() shouldBe listOf(4474000.0, 4474002.0)
        lines[1].easting.toList() shouldBe listOf(440000.0, 439998.0)
        lines[1].northing.toList() shouldBe listOf(4474002.0, 4474002.0)
    }

    @Test
    fun `S040 RF-14 una rejilla de dos columnas y tres filas da seis insertos girados`() {
        val (sink, _) = expand("bloques.dxf")
        val grid = sink.elements.map { it.second }.filter { it.kind == CadEntityKind.BLOCK_INSERT && it.hasZ }

        grid.size shouldBe 6
        grid[1].easting[0] shouldBe (440100.0 + 5 * Math.cos(Math.toRadians(30.0)) plusOrMinus 1e-9)
        grid[1].northing[0] shouldBe (4474000.0 + 5 * Math.sin(Math.toRadians(30.0)) plusOrMinus 1e-9)
        grid.all { it.height!![0] == 10.0 } shouldBe true
    }

    @Test
    fun `S040 RF-14 un ciclo de bloques se corta en el primer ciclo y se cuenta`() {
        val (sink, _) = expand("bloques.dxf")

        sink.ignored shouldBe listOf(CadIgnored(CadIgnoredReason.BLOCK_CYCLE))
    }

    @Test
    fun `S040 RF-15 cada inserto deja su punto de insercion en la capa del inserto`() {
        val (sink, _) = expand("r2000.dxf")
        val points = sink.elements.filter { it.second.kind == CadEntityKind.BLOCK_INSERT }

        points.size shouldBe 1
        points.single().first shouldBe "Pozos"
        points.single().second.easting[0] shouldBe 440070.0
    }

    @Test
    fun `S040 RF-24 BYBLOCK toma el color del inserto y la capa 0 la capa del inserto`() {
        val (sink, tables) = expand("r2000.dxf")
        val pozoLine = sink.elements.first { it.second.kind == CadEntityKind.LINE && it.second.easting.first() == 440070.0 }

        pozoLine.first shouldBe "Pozos" // dentro del bloque era la capa 0
        (pozoLine.second.color ?: tables.colorOf("Pozos")) shouldBe CadColor.rgb(0x00FF00) // el color 3 del inserto, no el de la capa
    }

    @Test
    fun `S040 RF-14 un bloque no definido deja el punto de insercion y se cuenta`() {
        val bytes = doc("", insert("NOEXISTE", 5.0, 6.0))

        val (sink, _) = expand(bytes)

        sink.elements.map { it.second.kind } shouldBe listOf(CadEntityKind.BLOCK_INSERT)
        sink.ignored shouldBe listOf(CadIgnored(CadIgnoredReason.UNDEFINED_BLOCK))
    }

    @Test
    fun `S040 RF-14 el punto base del bloque se resta antes de colocarlo`() {
        val blocks = "0\nBLOCK\n8\n0\n2\nB\n70\n0\n10\n100.0\n20\n100.0\n30\n0.0\n3\nB\n${line(
            "0",
            100.0,
            100.0,
            103.0,
            100.0,
        )}\n0\nENDBLK\n8\n0"

        val (sink, _) = expand(doc(blocks, insert("B", 10.0, 20.0)))

        val inner = sink.elements.map { it.second }.first { it.kind == CadEntityKind.LINE }
        inner.easting.toList() shouldBe listOf(10.0, 13.0)
        inner.northing.toList() shouldBe listOf(20.0, 20.0)
    }

    @Test
    fun `S040 RF-14 la extrusion del inserto invierte el Este`() {
        val blocks = block("B", line("0", 0.0, 0.0, 3.0, 1.0))

        val (sink, _) = expand(doc(blocks, insert("B", 10.0, 20.0, "210\n0\n220\n0\n230\n-1")))

        val inner = sink.elements.map { it.second }.first { it.kind == CadEntityKind.LINE }
        inner.easting.toList() shouldBe listOf(-10.0, -13.0)
        inner.northing.toList() shouldBe listOf(20.0, 21.0)
    }

    @Test
    fun `S040 RF-15 los arcos de un bloque escalado se discretizan para quedar a menos de 1 cm tras la escala`() {
        val arc = "0\nCIRCLE\n8\n0\n10\n0.0\n20\n0.0\n30\n0.0\n40\n1.0"
        val blocks = block("B", arc)

        val (sink, _) = expand(doc(blocks, insert("B", 0.0, 0.0, "41\n50.0\n42\n50.0\n43\n1.0")))

        val circle = sink.elements.map { it.second }.first { it.kind == CadEntityKind.CIRCLE }
        val sagitta = (0 until circle.vertexCount - 1).maxOf { i ->
            val mx = (circle.easting[i] + circle.easting[i + 1]) / 2
            val my = (circle.northing[i] + circle.northing[i + 1]) / 2
            50.0 - Math.hypot(mx, my)
        }
        (sagitta <= CadCurves.TOLERANCE_M + 1e-9) shouldBe true
    }

    @Test
    fun `S040 RF-14 una cadena de bloques de mas de 16 niveles se corta`() {
        val chain = (0 until 20).joinToString("\n") { i ->
            block(
                "B$i",
                if (i ==
                    19
                ) {
                    line("0", 0.0, 0.0, 1.0, 1.0)
                } else {
                    insert("B${i + 1}", 0.0, 0.0, layer = "0")
                },
            )
        }

        val (sink, _) = expand(doc(chain, insert("B0", 0.0, 0.0)))

        sink.ignored.contains(CadIgnored(CadIgnoredReason.BLOCK_TOO_LARGE)) shouldBe true
        sink.elements.none { it.second.kind == CadEntityKind.LINE } shouldBe true
    }

    @Test
    fun `S040 RF-14 un bloque con demasiados vertices guarda solo hasta el limite y se cuenta`() {
        val lines = (0 until 10).map { line("0", it.toDouble(), 0.0, it + 1.0, 0.0) }.toTypedArray()
        val blocks = block("B", *lines)

        val (sink, _) = expand(doc(blocks, insert("B", 0.0, 0.0)), maxBlockVertices = 6)

        sink.elements.count { it.second.kind == CadEntityKind.LINE } shouldBe 3
        sink.ignored shouldBe listOf(CadIgnored(CadIgnoredReason.BLOCK_TOO_LARGE))
    }

    @Test
    fun `S040 RF-16 y RF-13 lo ignorado y roto dentro de un bloque se cuenta cada vez que se despliega`() {
        val text = "0\nTEXT\n8\n0\n10\n0.0\n20\n0.0\n1\nhola"
        val broken = "0\nLINE\n8\n0\n10\nabc\n20\n0\n11\n1\n21\n1"
        val blocks = block("B", text, broken)

        val (sink, _) = expand(doc(blocks, insert("B", 0.0, 0.0) + "\n" + insert("B", 5.0, 5.0)))

        sink.ignored.count { it == CadIgnored(CadIgnoredReason.UNSUPPORTED_TYPE, "TEXT") } shouldBe 2
        sink.ignored.count { it == CadIgnored(CadIgnoredReason.INVALID_VALUE, "LINE") } shouldBe 2
    }
}
