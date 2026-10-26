package es.topophone.data.cad

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.cad.CadChunkCodec
import es.topophone.core.model.cad.CadColor
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadExtent
import es.topophone.core.model.cad.CadIgnored
import es.topophone.core.model.cad.CadIgnoredReason
import es.topophone.core.model.cad.CadImportOptions
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.cad.DesignLayerKind
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Importar un DXF: leer, convertir, agrupar por capa de diseño y zona, escribir, cancelar y resumir (S040/RF-11 a RF-23, RF-33). */
@RunWith(AndroidJUnit4::class)
class CadDrawingImporterTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val repository = RoomCadRepository(db)

    private val project = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private var ids = 0
    private fun importer(repo: CadRepository = repository, maxBufferedBytes: Long = CadDrawingImporter.MAX_BUFFERED_BYTES) =
        CadDrawingImporter(repo, clock = { 123_456L }, newId = { "id-${ids++}" }, maxBufferedBytes = maxBufferedBytes)

    @After
    fun close() = db.close()

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }

    private fun fixture(name: String): ByteArray = File(root, "fixtures/formatos/dxf/$name").readBytes()

    private fun expected(file: String): List<Map<String, String>> {
        val lines = File(root, "fixtures/formatos/dxf/$file").readLines().filter { it.isNotEmpty() && !it.startsWith("#") }
        val header = lines.first().split(',')
        return lines.drop(1).map { line -> header.zip(csv(line)).toMap() }
    }

    private fun csv(line: String): List<String> {
        val out = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        for (c in line) {
            when {
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> {
                    out += cell.toString()
                    cell.clear()
                }
                else -> cell.append(c)
            }
        }
        return out + cell.toString()
    }

    private fun summaryOf(file: String) = expected("esperado-resumen.csv").filter { it["archivo"] == file }.associate {
        it.getValue("clave") to
            it.getValue("valor")
    }

    private fun import(
        file: String,
        options: CadImportOptions = CadImportOptions(),
        crs: ProjectCrs = ProjectCrs.ETRS89_UTM30,
        importer: CadDrawingImporter = importer(),
        progress: (Long) -> Unit = {},
    ): CadImportResult = runBlocking {
        importer.import(project, crs, file, options, { ByteArrayInputStream(fixture(file)) }, progress)
    }

    private fun imported(result: CadImportResult) = result.shouldBeInstanceOf<CadImportResult.Imported>()

    private fun drawings() = runBlocking { repository.drawings(project).first() }

    private fun entitiesOf(layerId: String): List<CadEntity> = runBlocking {
        repository.chunksIn(listOf(layerId), CadExtent(-1.0e12, 1.0e12, -1.0e12, 1.0e12)).flatMap { it.entities }
    }

    private fun color(text: String) = if (text == "AUTO") CadColor.AUTO_CONTRAST else CadColor(text.toLong(16).toInt())

    private fun count(table: String): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
        it.moveToFirst()
        it.getInt(0)
    }

    @Test
    fun `S040 RF-26 las capas salen por nombre y tipo con su color, orden y recuento`() {
        imported(import("r2000.dxf"))

        val layers = drawings().single().layers
        val want = expected("esperado-capas.csv").filter { it["archivo"] == "r2000.dxf" }

        layers.map { it.name to it.kind.name } shouldBe want.map { it.getValue("capa") to it.getValue("tipo_capa") }
        layers.map { it.entityCount } shouldBe want.map { it.getValue("elementos").toInt() }
        layers.map { it.color } shouldBe want.map { color(it.getValue("color")) }
        layers.map { it.position } shouldBe layers.indices.toList()
        layers.map { entitiesOf(it.id).size } shouldBe layers.map { it.entityCount }
    }

    @Test
    fun `S040 RF-26 una capa del DXF con puntos y lineas da dos capas de diseno con el mismo nombre`() {
        imported(import("r2000.dxf"))

        val pozos = drawings().single().layers.filter { it.name == "Pozos" }

        pozos.map { it.kind } shouldBe listOf(DesignLayerKind.POINTS, DesignLayerKind.LINES)
    }

    @Test
    fun `S040 RF-18 el resumen cuenta lo importado por tipo y las capas creadas`() {
        val result = imported(import("r2018.dxf"))

        val want = summaryOf("r2018.dxf").filterKeys { it.startsWith("elementos.") }
            .mapKeys { CadEntityKind.valueOf(it.key.removePrefix("elementos.")) }.mapValues { it.value.toInt() }
        result.summary.imported shouldBe want
        result.summary.ignored shouldBe emptyMap()
        result.summary.layers shouldBe drawings().single().layers.size
        result.summary.incomplete shouldBe false
    }

    @Test
    fun `S040 RF-16 lo ignorado se cuenta por tipo`() {
        val result = imported(import("ignorados.dxf"))

        result.summary.ignored[CadIgnored(CadIgnoredReason.UNSUPPORTED_TYPE, "TEXT")] shouldBe 2
        result.summary.ignoredTotal shouldBe 9
        result.summary.importedTotal shouldBe 2
    }

    @Test
    fun `S040 RF-33 las capas apagadas y congeladas llegan ocultas y el blanco de contraste`() {
        imported(import("capas.dxf"))

        val layers = drawings().single().layers.filter { it.kind == DesignLayerKind.LINES }
        val want = expected("esperado-capas.csv").filter { it["archivo"] == "capas.dxf" && it["tipo_capa"] == "LINES" }

        layers.map { it.name to it.visible } shouldBe want.map { it.getValue("capa") to (it.getValue("visible") == "SI") }
        layers.first { it.name == "Blanca" }.color shouldBe CadColor.AUTO_CONTRAST
        layers.first { it.name == "Apagada" }.visible shouldBe false
        layers.first { it.name == "Congelada" }.visible shouldBe false
    }

    @Test
    fun `S040 RF-24 el color propio de un elemento se guarda y el de su capa no`() {
        imported(import("capas.dxf"))

        val roja = drawings().single().layers.first { it.name == "Roja" && it.kind == DesignLayerKind.LINES }
        val colors = entitiesOf(roja.id).map { it.color }

        colors shouldBe listOf(null, CadColor.rgb(0xFFFF00), CadColor.rgb(0x00AA55))
    }

    @Test
    fun `S040 RF-21 y RF-23 la cota queda en los elementos que la traen y el dibujo la tiene`() {
        imported(import("cotas.dxf"))

        val drawing = drawings().single()
        val all = drawing.layers.flatMap { entitiesOf(it.id) }

        drawing.drawing.hasElevations shouldBe true
        all.count { it.hasZ } shouldBe 4
        all.count { !it.hasZ } shouldBe 3
    }

    @Test
    fun `S040 RF-22 un dibujo con todas las Z a cero queda sin cota`() {
        imported(import("todo-z-cero.dxf"))

        drawings().single().drawing.hasElevations shouldBe false
        drawings().single().drawing.useElevations shouldBe true
    }

    @Test
    fun `S040 RF-8 sin usar las cotas no se guarda ninguna altura`() {
        imported(import("cotas.dxf", CadImportOptions(useElevations = false)))

        val drawing = drawings().single()

        drawing.drawing.useElevations shouldBe false
        drawing.drawing.hasElevations shouldBe false
        drawing.layers.flatMap { entitiesOf(it.id) }.none { it.hasZ } shouldBe true
    }

    @Test
    fun `S040 RF-17 un elemento con un vertice fuera del ambito no entra y se cuenta`() {
        val result = imported(import("fuera-de-ambito.dxf"))

        result.summary.importedTotal shouldBe 3
        result.summary.ignored shouldBe mapOf(CadIgnored(CadIgnoredReason.OUTSIDE_CRS_AREA) to 2)
    }

    @Test
    fun `S040 RF-13 un fichero cortado se importa con lo leido y queda incompleto`() {
        val result = imported(import("cortado.dxf"))

        result.summary.importedTotal shouldBe 3
        result.summary.incomplete shouldBe true
        drawings().single().drawing.incomplete shouldBe true
    }

    @Test
    fun `S040 RF-13 un fichero sin ningun elemento no importa nada`() {
        val result = import("sin-elementos.dxf")

        result shouldBe CadImportResult.Failed(CadImportFailure.NO_ELEMENTS)
        count("cad_drawings") shouldBe 0
        count("cad_layers") shouldBe 0
    }

    @Test
    fun `S040 RF-13 un elemento con un valor que no es un numero se descarta y se cuenta`() {
        val result = imported(import("elemento-roto.dxf"))

        result.summary.importedTotal shouldBe 4
        result.summary.ignored shouldBe mapOf(CadIgnored(CadIgnoredReason.INVALID_VALUE, "LINE") to 1)
        result.summary.incomplete shouldBe false
    }

    @Test
    fun `S040 RF-6 y RF-7 las coordenadas del dibujo se convierten como en pyproj`() {
        val rows = expected("esperado-conversion.csv")
        val cases = listOf(
            "origen-ed50-utm30.dxf" to CadImportOptions(source = CadSourceCrs.Utm(GeodeticDatum.ED50, 30)),
            "origen-etrs89-utm29.dxf" to CadImportOptions(source = CadSourceCrs.Utm(GeodeticDatum.ETRS89, 29)),
            "origen-etrs89-utm30-pies.dxf" to CadImportOptions(unit = LinearUnit.INTERNATIONAL_FOOT),
            "origen-etrs89-utm30-pies-us.dxf" to CadImportOptions(unit = LinearUnit.US_SURVEY_FOOT),
        )
        cases.forEach { (file, options) ->
            val before = drawings().size
            imported(import(file, options))
            val layers = drawings()[before].layers
            val points = entitiesOf(layers.first { it.kind == DesignLayerKind.POINTS }.id)
            val want = rows.filter { it["archivo"] == file }

            points.size shouldBe 4
            points.sortedBy { it.easting[0] }.zip(want.sortedBy { it.getValue("proyecto_e").toDouble() }).forEach { (point, row) ->
                point.easting[0] shouldBe (row.getValue("proyecto_e").toDouble() plusOrMinus 1e-3)
                point.northing[0] shouldBe (row.getValue("proyecto_n").toDouble() plusOrMinus 1e-3)
                point.height!![0] shouldBe (row.getValue("proyecto_z").toDouble() plusOrMinus 1e-9)
            }
        }
    }

    @Test
    fun `S040 RF-19 el dibujo guarda el fichero, la fecha, el sistema y la unidad`() {
        imported(import("origen-etrs89-utm29.dxf", CadImportOptions(source = CadSourceCrs.Utm(GeodeticDatum.ETRS89, 29))))

        val drawing = drawings().single().drawing

        drawing.fileName shouldBe "origen-etrs89-utm29.dxf"
        drawing.importedAtMs shouldBe 123_456L
        drawing.source shouldBe CadSourceCrs.Utm(GeodeticDatum.ETRS89, 29)
        drawing.unit shouldBe LinearUnit.METRE
    }

    @Test
    fun `S040 RF-9 la extension guardada es la de los elementos importados`() {
        imported(import("cotas.dxf"))

        drawings().single().drawing.extent shouldBe CadExtent(440000.0, 440010.0, 4474000.0, 4474014.0)
    }

    @Test
    fun `S040 RF-20 el mismo fichero dos veces da dos dibujos aparte`() {
        val first = imported(import("r2000.dxf"))
        val second = imported(import("r2000.dxf"))

        (first.drawingId != second.drawingId) shouldBe true
        drawings().size shouldBe 2
        drawings()[0].layers.map { it.id }.intersect(drawings()[1].layers.map { it.id }.toSet()) shouldBe emptySet()
    }

    @Test
    fun `S040 RF-12 cancelar a mitad no deja nada`() {
        val blocked = importer()
        val job = runBlocking {
            val deferred = async(Dispatchers.Default) {
                blocked.import(project, ProjectCrs.ETRS89_UTM30, "r2000.dxf", CadImportOptions(), {
                    ByteArrayInputStream(fixture("r2000.dxf"))
                }) {
                    throw CancellationException("el usuario cancela")
                }
            }
            runCatching { deferred.await() }
        }

        (job.exceptionOrNull() is CancellationException) shouldBe true
        count("cad_drawings") shouldBe 0
        count("cad_layers") shouldBe 0
        count("cad_chunks") shouldBe 0
    }

    @Test
    fun `S040 RF-12 un fallo de escritura no deja nada e indica que no se importo`() {
        val failing = object : CadRepository by repository {
            override suspend fun addChunks(chunks: List<CadChunkWrite>) = throw IOException("disco lleno")
        }

        val result = import("r2000.dxf", importer = importer(failing))

        result shouldBe CadImportResult.Failed(CadImportFailure.WRITE_ERROR)
        count("cad_drawings") shouldBe 0
        count("cad_chunks") shouldBe 0
    }

    @Test
    fun `S040 RF-12 un fallo de lectura no deja nada e indica que no se importo`() {
        val broken = object : InputStream() {
            private val data = fixture("r2000.dxf")
            private var i = 0

            override fun read(): Int = if (i < 500) data[i++].toInt() and 0xFF else throw IOException("tarjeta quitada")
        }

        val result = runBlocking {
            importer().import(project, ProjectCrs.ETRS89_UTM30, "r2000.dxf", CadImportOptions(), { broken }) {}
        }

        result shouldBe CadImportResult.Failed(CadImportFailure.READ_ERROR)
        count("cad_drawings") shouldBe 0
    }

    @Test
    fun `S040 RF-11 el progreso avisa de los bytes leidos hasta el final del fichero`() {
        val seen = mutableListOf<Long>()

        imported(import("r2000.dxf", progress = { seen += it }))

        (seen.isNotEmpty()) shouldBe true
        seen.zipWithNext().all { (a, b) -> b >= a } shouldBe true
        seen.last() shouldBe fixture("r2000.dxf").size.toLong()
    }

    /** Un DXF de [points] puntos en tres grupos separados 2 km, en una sola capa. */
    private fun manyPoints(points: Int): ByteArray {
        val sb = StringBuilder("0\nSECTION\n2\nENTITIES\n")
        for (i in 0 until points) {
            val group = i % 3
            sb.append("0\nPOINT\n8\nPuntos\n10\n").append(440_000.0 + group * 2000 + (i / 3) % 100).append("\n20\n").append(
                4_474_000.0 + i / 300,
            )
                .append("\n30\n").append(i * 0.001).append('\n')
        }
        return sb.append("0\nENDSEC\n0\nEOF\n").toString().toByteArray()
    }

    @Test
    fun `S040 RF-12 los trozos no pasan de 256 kB y se agrupan por zona de 500 m`() {
        val bytes = manyPoints(30_000)

        val result = runBlocking {
            importer().import(project, ProjectCrs.ETRS89_UTM30, "muchos.dxf", CadImportOptions(), { ByteArrayInputStream(bytes) }) {}
        }

        imported(result).summary.importedTotal shouldBe 30_000
        val layer = drawings().single().layers.single()
        val chunks = runBlocking { repository.chunksIn(listOf(layer.id), CadExtent(-1.0e12, 1.0e12, -1.0e12, 1.0e12)) }
        (chunks.size >= 4) shouldBe true // tres zonas y más de 256 kB en total
        chunks.sumOf { it.entities.size } shouldBe 30_000
        chunks.all { CadChunkCodec.encodedSize(it.entities) <= CadChunkCodec.MAX_CHUNK_BYTES } shouldBe true
        chunks.all { c ->
            c.entities.all { e -> e.bounds.minEasting >= c.bounds.minEasting && e.bounds.maxEasting <= c.bounds.maxEasting }
        } shouldBe
            true
        // La esquina mínima de cada elemento cae en una sola celda de 500 m por trozo.
        chunks.all { c -> c.entities.map { Math.floor(it.easting[0] / 500.0) }.toSet().size == 1 } shouldBe true
    }

    @Test
    fun `S040 RF-12 con la memoria de los buffers al limite el resultado es el mismo`() {
        val bytes = manyPoints(10_000)

        val result = runBlocking {
            importer(maxBufferedBytes = 20_000).import(
                project,
                ProjectCrs.ETRS89_UTM30,
                "muchos.dxf",
                CadImportOptions(),
                { ByteArrayInputStream(bytes) },
            ) {}
        }

        imported(result).summary.importedTotal shouldBe 10_000
        drawings().single().layers.single().entityCount shouldBe 10_000
        entitiesOf(drawings().single().layers.single().id).size shouldBe 10_000
    }

    @Test
    fun `S040 RF-14 una polilinea gigante se parte para que su fila quepa`() {
        val sb = StringBuilder("0\nSECTION\n2\nENTITIES\n0\nLWPOLYLINE\n8\nL\n90\n150000\n70\n0\n")
        for (i in 0 until 150_000) sb.append("10\n").append(440_000.0 + i * 0.001).append("\n20\n").append(4_474_000.0).append('\n')
        sb.append("0\nENDSEC\n0\nEOF\n")

        val result = runBlocking {
            importer().import(project, ProjectCrs.ETRS89_UTM30, "grande.dxf", CadImportOptions(), {
                ByteArrayInputStream(sb.toString().toByteArray())
            }) {}
        }

        imported(result)
        val pieces = entitiesOf(drawings().single().layers.single().id)
        (pieces.size > 1) shouldBe true
        pieces.sumOf { it.vertexCount } shouldBe 150_000 + pieces.size - 1 // cada trozo repite el vértice de unión
        pieces.all { CadChunkCodec.entitySize(it) <= CadDrawingImporter.MAX_ENTITY_BYTES } shouldBe true
        pieces.zipWithNext().all { (a, b) -> a.easting.last() == b.easting.first() } shouldBe true
    }
}
