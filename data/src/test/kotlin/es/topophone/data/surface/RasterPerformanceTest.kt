package es.topophone.data.surface

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.raster.RasterLimits
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.surface.SurfaceHeight
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.time.measureTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Un ráster de 250 MB generado al vuelo se importa en menos de 180 s sin quedarse sin memoria, activarlo y obtener la primera cota
 * tarda menos de 1 s y la consulta a 10 Hz no pasa de 16 ms (S045, RNF-T13 y RNF de la spec). Robolectric sirve de cota; la medida en
 * el móvil de referencia es de la demo.
 */
@RunWith(AndroidJUnit4::class)
class RasterPerformanceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dbName = "rp-${System.nanoTime()}.db"
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.databaseBuilder(context, TopoPhoneDatabase::class.java, dbName).build()
    private val projects = RoomProjectRepository(db)
    private val repository = RoomSurfaceRepository(db)
    private val project = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Obra grande")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @After
    fun close() {
        db.close()
        context.deleteDatabase(dbName)
    }

    private val side = 7_906 // 62.5 millones de celdas de 4 bytes: 238 MiB, justo bajo el límite de 250 MB
    private val originE = 440_000.0
    private val originN = 4_480_000.0

    /** Un GeoTIFF de [side] × [side] celdas float32 de 1 m, por tiras de 16 filas y sin comprimir, escrito de una pasada. */
    private fun writeBigTiff(file: File) {
        val rowsPerStrip = 16
        val strips = (side + rowsPerStrip - 1) / rowsPerStrip
        RandomAccessFile(file, "rw").use { out ->
            out.write(byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 42, 0, 0, 0, 0, 0))
            val offsets = IntArray(strips)
            val counts = IntArray(strips)
            val row = ByteBuffer.allocate(side * 4 * rowsPerStrip).order(ByteOrder.LITTLE_ENDIAN)
            for (strip in 0 until strips) {
                val rows = minOf(rowsPerStrip, side - strip * rowsPerStrip)
                row.clear()
                for (r in 0 until rows) {
                    val y = strip * rowsPerStrip + r
                    for (x in 0 until side) row.putFloat((600.0 + 0.001 * x + 0.002 * y).toFloat())
                }
                offsets[strip] = out.filePointer.toInt()
                counts[strip] = rows * side * 4
                out.write(row.array(), 0, rows * side * 4)
            }
            val extra = ByteBuffer.allocate(strips * 8 + 64 + 128).order(ByteOrder.LITTLE_ENDIAN)
            val extraBase = out.filePointer.toInt()
            fun entry(tag: Int, type: Int, count: Int, value: Int) = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(tag.toShort()).putShort(type.toShort()).putInt(count).putInt(value).array()

            val offsetsAt = extraBase + extra.position().also { offsets.forEach { v -> extra.putInt(v) } }
            val countsAt = extraBase + extra.position().also { counts.forEach { v -> extra.putInt(v) } }
            val scaleAt = extraBase + extra.position().also { listOf(1.0, 1.0, 0.0).forEach { v -> extra.putDouble(v) } }
            val tieAt =
                extraBase + extra.position().also { listOf(0.0, 0.0, 0.0, originE, originN, 0.0).forEach { v -> extra.putDouble(v) } }
            val keys = intArrayOf(1, 1, 0, 3, 1024, 0, 1, 1, 1025, 0, 1, 1, 3072, 0, 1, 25830)
            val keysAt = extraBase + extra.position().also { keys.forEach { v -> extra.putShort(v.toShort()) } }
            val nodata = "-9999\u0000".toByteArray(Charsets.US_ASCII)
            val nodataAt = extraBase + extra.position().also { extra.put(nodata) }
            out.write(extra.array(), 0, extra.position())

            val entries = listOf(
                entry(256, 4, 1, side), entry(257, 4, 1, side), entry(258, 3, 1, 32), entry(259, 3, 1, 1), entry(262, 3, 1, 1),
                entry(273, 4, strips, offsetsAt), entry(277, 3, 1, 1), entry(278, 4, 1, rowsPerStrip), entry(279, 4, strips, countsAt),
                entry(284, 3, 1, 1), entry(339, 3, 1, 3), entry(33550, 12, 3, scaleAt), entry(33922, 12, 6, tieAt),
                entry(34735, 3, keys.size, keysAt), entry(42113, 2, nodata.size, nodataAt),
            )
            val ifdAt = out.filePointer.toInt()
            out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(entries.size.toShort()).array())
            entries.forEach { out.write(it) }
            out.write(ByteArray(4))
            out.seek(4)
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(ifdAt).array())
        }
    }

    @Test
    fun `S045 RNF 250 MB importar activar y consultar rapido`() = runBlocking<Unit> {
        val file = folder.newFile("grande.tif")
        writeBigTiff(file)
        (file.length() < RasterLimits.MAX_FILE_BYTES) shouldBe true
        println(
            "Ráster de ${file.length() / (1024 * 1024)} MiB, ${side.toLong() * side} celdas; memoria máxima de la JVM: ${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MiB",
        )

        val importer = RasterImporter(repository, folder.newFolder())
        lateinit var staged: StagedRaster
        val copying = measureTime { staged = (importer.stage({ file.inputStream() }) {} as RasterStageResult.Staged).staged }
        val result: RasterImportResult
        val importing = measureTime {
            result =
                importer.import(
                    project,
                    ProjectCrs.ETRS89_UTM30,
                    staged,
                    "grande.tif",
                    RasterImportOptions(CadSourceCrs.Project, LinearUnit.METRE, HeightSystem.ORTHOMETRIC),
                )
        }
        val imported = result.shouldBeInstanceOf<RasterImportResult.Imported>()
        staged.close()
        val used = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024)
        println(
            "Copiar: ${copying.inWholeSeconds} s; importar ${imported.summary.summary.dataCells} celdas: ${importing.inWholeSeconds} s; memoria en uso al acabar: $used MiB",
        )
        importing.inWholeSeconds shouldBeLessThan 180L
        imported.summary.summary.dataCells shouldBe side.toLong() * side

        // La primera cota tras activarlo (S045/RF-21): menos de 1 s, con la cota de una celda conocida.
        val provider =
            ActiveSurfaceProvider(repository, Dispatchers.IO, projectContext = {
                flowOf(RasterProjectContext(ProjectCrs.ETRS89_UTM30, HeightSystem.ORTHOMETRIC))
            })
        val e = originE + 3_000.5
        val n = originN - 4_000.5
        val first = measureTime {
            withTimeout(10_000) {
                provider.activeRaster(project).filterNotNull().first { active ->
                    var height = active.heightAt(e, n)
                    while (height == SurfaceHeight.Loading) {
                        delay(1)
                        height = active.heightAt(e, n)
                    }
                    (height as SurfaceHeight.Value).metres shouldBe (600.0 + 0.001 * 3_000 + 0.002 * 4_000 plusOrMinus 1e-3)
                    true
                }
            }
        }
        println("Activar y primera cota: ${first.inWholeMilliseconds} ms")
        first.inWholeMilliseconds shouldBeLessThan 1_000L

        // La consulta a 10 Hz (RNF-T13): 2.000 posiciones a lo largo de la malla, de bloque en bloque, sin pasar de 16 ms.
        withTimeout(120_000) {
            provider.activeRaster(project).filterNotNull().first { active ->
                val bean = java.lang.management.ManagementFactory.getThreadMXBean()

                suspend fun walk(): Pair<List<Long>, Long> {
                    val wall = ArrayList<Long>()
                    var cpuLongest = 0L
                    for (step in 0 until 2_000) {
                        val east = originE + 10.0 + step * 3.0
                        val north = originN - 10.0 - step * 3.0
                        val cpu0 = bean.currentThreadCpuTime
                        val start = System.nanoTime()
                        active.heightAt(east, north)
                        wall += (System.nanoTime() - start) / 1_000
                        cpuLongest = maxOf(cpuLongest, (bean.currentThreadCpuTime - cpu0) / 1_000_000)
                        if (step % 50 == 0) delay(5)
                    }
                    return wall to cpuLongest
                }
                walk()
                val (wall, cpuLongest) = walk()
                val sorted = wall.sorted()
                val p99 = sorted[(sorted.size * 0.99).toInt()] / 1_000
                println("2.000 consultas en diagonal: mediana ${sorted[sorted.size / 2]} us, p99 $p99 ms, CPU máxima $cpuLongest ms")
                p99 shouldBeLessThan 16L
                cpuLongest shouldBeLessThan 16L
                true
            }
        }
    }
}
