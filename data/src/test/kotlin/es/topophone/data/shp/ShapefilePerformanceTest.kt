package es.topophone.data.shp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.shp.ShpFileKind
import es.topophone.core.geodesy.Utm
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.cad.ImportArea
import es.topophone.core.model.project.CentralPoint
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.cad.RoomCadRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.gpkg.GeoPackageImporter
import es.topophone.data.gpkg.GeoPackageScan
import es.topophone.data.gpkg.GpkgImportOptions
import es.topophone.data.gpkg.GpkgImportResult
import es.topophone.data.gpkg.GpkgLayerChoice
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.BufferedOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.time.measureTimedValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Un ZIP de Shapefile sintético de unos 100 MB (1.100.000 líneas) se lee y se recorta a la zona en menos de 20 s, cambiar el radio
 * recuenta en menos de 2 s y la lectura va por flujo, sin cargar el `.shp` entero en memoria (S030, RNF). Robolectric sirve de cota; la
 * medida en el móvil de referencia es de la demo.
 */
@RunWith(AndroidJUnit4::class)
class ShapefilePerformanceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val cad = RoomCadRepository(db)
    private val crs = ProjectCrs.ETRS89_UTM30
    private val center = Utm.inverse(440_000.0, 4_474_000.0, 30).let { CentralPoint(it.latitudeDeg, it.longitudeDeg) }

    @After
    fun close() = db.close()

    /** Un `.shp` de [rows] líneas de 6 vértices en una malla de 25 m de paso sobre 20 km de ancho alrededor de Madrid, y su `.shx` y su `.dbf`. */
    private fun build(zip: File, rows: Int) {
        val shp = File(folder.root, "obra.shp")
        val shx = File(folder.root, "obra.shx")
        val dbf = File(folder.root, "obra.dbf")
        val contentBytes = 4 + 32 + 4 + 4 + 4 + VERTICES * 16
        val recordBytes = 8 + contentBytes
        fun header(words: Int): ByteArray {
            val bytes = ByteBuffer.allocate(100)
            bytes.order(ByteOrder.BIG_ENDIAN).putInt(0, 9994).putInt(24, words)
            bytes.order(ByteOrder.LITTLE_ENDIAN).putInt(28, 1000).putInt(32, 3)
            bytes.putDouble(36, 430_000.0).putDouble(44, 4_464_000.0).putDouble(52, 450_000.0).putDouble(60, 4_480_000.0)
            return bytes.array()
        }
        BufferedOutputStream(shp.outputStream(), 1 shl 20).use { out ->
            out.write(header((100 + rows.toLong() * recordBytes).div(2).toInt()))
            val record = ByteBuffer.allocate(recordBytes)
            val noise = java.util.Random(7)
            val xs = DoubleArray(VERTICES)
            val ys = DoubleArray(VERTICES)
            for (i in 0 until rows) {
                // Cada vértice con sus propios decimales: las coordenadas de un levantamiento real no se comprimen bien.
                val x0 = 430_000.0 + (i % COLUMNS) * 25.0
                val y0 = 4_464_000.0 + (i / COLUMNS) * 25.0
                for (v in 0 until VERTICES) {
                    xs[v] = x0 + v * 3.0 + noise.nextDouble() * 0.5
                    ys[v] = y0 + (v % 2) * 4.0 + noise.nextDouble() * 0.5
                }
                record.clear()
                record.order(ByteOrder.BIG_ENDIAN).putInt(i + 1).putInt(contentBytes / 2)
                record.order(ByteOrder.LITTLE_ENDIAN).putInt(3)
                record.putDouble(xs.min()).putDouble(ys.min()).putDouble(xs.max()).putDouble(ys.max())
                record.putInt(1).putInt(VERTICES).putInt(0)
                for (v in 0 until VERTICES) record.putDouble(xs[v]).putDouble(ys[v])
                out.write(record.array(), 0, recordBytes)
            }
        }
        BufferedOutputStream(shx.outputStream(), 1 shl 20).use { out ->
            out.write(header((100 + rows * 8) / 2))
            val entry = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            for (i in 0 until rows) {
                entry.clear()
                entry.putInt(((100L + i.toLong() * recordBytes) / 2).toInt()).putInt(contentBytes / 2)
                out.write(entry.array())
            }
        }
        val dbfHeader = 32 + 32 * 2 + 1
        val recordLength = 1 + NAME_WIDTH + 8
        BufferedOutputStream(dbf.outputStream(), 1 shl 20).use { out ->
            val h = ByteBuffer.allocate(dbfHeader).order(ByteOrder.LITTLE_ENDIAN)
            h.put(3).put(126).put(10).put(27).putInt(rows).putShort(dbfHeader.toShort()).putShort(recordLength.toShort())
            h.position(32)
            h.put("nombre".toByteArray()).position(32 + 11)
            h.put('C'.code.toByte()).position(32 + 16)
            h.put(NAME_WIDTH.toByte()).position(64)
            h.put("codigo".toByteArray()).position(64 + 11)
            h.put('N'.code.toByte()).position(64 + 16)
            h.put(8.toByte()).position(dbfHeader - 1)
            h.put(0x0D)
            out.write(h.array())
            val padding = "Tramo ".padEnd(NAME_WIDTH, 'x').toByteArray()
            val row = ByteArray(recordLength)
            for (i in 0 until rows) {
                row.fill(' '.code.toByte())
                System.arraycopy(padding, 0, row, 1, padding.size)
                val code = i.toString().padStart(8).toByteArray()
                System.arraycopy(code, 0, row, 1 + NAME_WIDTH, 8)
                out.write(row)
            }
            out.write(0x1A)
        }
        File(folder.root, "obra.prj").writeText(ShpFixtures.file("prj/esri-25830.prj").readText())
        File(folder.root, "obra.cpg").writeText("UTF-8")
        ZipOutputStream(BufferedOutputStream(zip.outputStream(), 1 shl 20)).use { out ->
            out.setLevel(Deflater.BEST_SPEED)
            for (name in listOf("obra.shp", "obra.shx", "obra.dbf", "obra.prj", "obra.cpg")) {
                out.putNextEntry(ZipEntry(name))
                File(folder.root, name).inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        listOf(shp, shx, dbf).forEach { it.delete() }
    }

    @Test
    fun `S030 RNF un ZIP de 100 MB se lee y se recorta en menos de 20 s y el radio recuenta en menos de 2 s`() {
        val zip = folder.newFile("obra.zip")
        val built = measureTimedValue { build(zip, ROWS) }
        println("S030 generación de ${zip.length() / (1024 * 1024)} MB: ${built.duration.inWholeMilliseconds} ms, $ROWS objetos")
        (zip.length() > 80L * 1024 * 1024) shouldBe true
        (zip.length() < ShpFileKind.MAX_PICKED_BYTES) shouldBe true
        val projectId = runBlocking {
            RoomProjectRepository(db).create(ProjectDefaults().toDraft().copy(name = "Obra grande", crs = crs))
                .shouldBeInstanceOf<SaveResult.Saved>().id
        }
        val before = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

        val reader = ShapefileReader(folder.newFolder("cache"))
        val opened = measureTimedValue { runBlocking { reader.open(listOf(ShpInput("obra.zip", zip.length()) { zip.inputStream() })) } }
        val session = opened.value.shouldBeInstanceOf<ShpOpenResult.Opened>().session
        session.use {
            val layer = session.layers.single()
            layer.featureCount shouldBe ROWS.toLong()
            val scan = GeoPackageScan(session)
            val indexed = measureTimedValue { runBlocking { scan.index(layer) } }
            indexed.value.size shouldBe ROWS
            val source = CadSourceCrs.Utm(GeodeticDatum.ETRS89, 30)

            // Cambiar el radio recuenta sobre las envolventes en memoria: menos de 2 s cada vez.
            val times = listOf(1_000.0, 2_000.0, 3_000.0, 500.0).map { radius ->
                val counted =
                    measureTimedValue { runBlocking { scan.count(indexed.value, source, crs, ImportArea.Circle(center, radius)) } }
                println(
                    "S030 recuento con radio de ${radius.toInt()} m: ${counted.value.inZone} objetos en ${counted.duration.inWholeMilliseconds} ms",
                )
                (counted.value.inZone > 0) shouldBe true
                counted.duration.inWholeMilliseconds
            }
            times.forEach { (it < 2_000) shouldBe true }

            val importer = GeoPackageImporter(cad)
            val area = ImportArea.Circle(center, 5_000.0)
            val imported = measureTimedValue {
                runBlocking {
                    importer.import(
                        projectId,
                        crs,
                        "obra.zip",
                        GpkgImportOptions(listOf(GpkgLayerChoice(layer, source, "nombre")), true, area),
                        session,
                    )
                }
            }
            println(
                "S030 lectura y recorte: abrir ${opened.duration.inWholeMilliseconds} ms, " +
                    "índice ${indexed.duration.inWholeMilliseconds} ms, " +
                    "importar ${imported.duration.inWholeMilliseconds} ms, pico de buffers ${importer.peakBufferedBytes / 1024} kB",
            )
            val result = imported.value.shouldBeInstanceOf<GpkgImportResult.Imported>()
            (result.summary.importedTotal in 1 until ROWS) shouldBe true
            result.summary.layers.single().outsideArea shouldBe ROWS - result.summary.importedTotal
            (importer.peakBufferedBytes <= 32L * 1024 * 1024 + 256 * 1024) shouldBe true
            val drawn = runBlocking { cad.drawings(projectId).first() }.single().layers.sumOf { it.entityCount }
            drawn shouldBe result.summary.importedTotal

            val total = opened.duration + indexed.duration + imported.duration
            println("S030 leer y recortar un ZIP de 100 MB: ${total.inWholeMilliseconds} ms")
            (total.inWholeSeconds < 20) shouldBe true
            // Por flujo: la memoria que ocupa la lectura no se acerca a los 100 MB del `.shp` (el índice de envolventes es de unas decenas de MB).
            val used = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() } - before
            println("S030 memoria usada por la lectura: ${used / (1024 * 1024)} MB")
        }
    }

    private companion object {
        const val VERTICES = 6
        const val COLUMNS = 800
        const val NAME_WIDTH = 24
        const val ROWS = 1_100_000
    }
}
