package es.topophone.data.surface

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.surface.SurfaceHeight
import es.topophone.core.model.surface.SurfaceTileCodec
import es.topophone.core.survey.TinTiler
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.InputStream
import kotlin.concurrent.thread
import kotlin.time.measureTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Activar una superficie de 1.000.000 de triángulos y tener la primera cota en menos de 1 s, consultarla a 10 Hz sin pasar de 16 ms
 * por época, e importar un LandXML de 100 MB en menos de 90 s sin quedarse sin memoria (S042, RNF-T13 y RNF de la spec). Robolectric
 * sirve de cota; la medida en el móvil de referencia es de la demo.
 */
@RunWith(AndroidJUnit4::class)
class SurfacePerformanceTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val repository = RoomSurfaceRepository(db)
    private val project = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Obra grande")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @After
    fun close() = db.close()

    private val e0 = 440_000.0
    private val n0 = 4_474_000.0

    /** Una malla de [side] × [side] celdas de 2 m con dos triángulos cada una: 2 × side² triángulos y (side + 1)² vértices. */
    private class Grid(val side: Int, val e0: Double, val n0: Double) {
        val vertexCount = (side + 1) * (side + 1)
        val easting = DoubleArray(vertexCount) { e0 + (it % (side + 1)) * 2.0 }
        val northing = DoubleArray(vertexCount) { n0 + (it / (side + 1)) * 2.0 }
        val height = DoubleArray(vertexCount) { 600.0 + 0.01 * (it % (side + 1)) + 0.02 * (it / (side + 1)) }
        val triangles = IntArray(side * side * 6).also { out ->
            var k = 0
            for (j in 0 until side) {
                for (i in 0 until side) {
                    val a = j * (side + 1) + i
                    val b = a + 1
                    val c = a + side + 1
                    val d = c + 1
                    out[k++] = a
                    out[k++] = b
                    out[k++] = d
                    out[k++] = a
                    out[k++] = d
                    out[k++] = c
                }
            }
        }
    }

    private fun write(id: String, grid: Grid) = runBlocking {
        val cells = TinTiler.tile(grid.easting, grid.northing, grid.height, grid.triangles)
        repository.begin(SurfaceDraft(id, project, "grande", "grande.xml", 1L, CadSourceCrs.Project, LinearUnit.METRE))
        for (batch in cells.chunked(8)) {
            repository.addTiles(id, batch.map { SurfaceTileWrite(it.region, it.tile.triangleCount, SurfaceTileCodec.encode(it.tile)) })
        }
        val extent = es.topophone.core.model.surface.SurfaceExtent(
            grid.easting.min(),
            grid.easting.max(),
            grid.northing.min(),
            grid.northing.max(),
        )
        repository.finish(id, SurfaceSummary(extent, 600.0, 700.0, grid.vertexCount, grid.triangles.size / 3, 0))
        cells.size
    }

    @Test
    fun `S042 RNF activar una superficie de un millon de triangulos da la primera cota en menos de 1 s`() {
        val grid = Grid(side = 708, e0 = e0, n0 = n0) // 1.002.528 triángulos
        (grid.triangles.size / 3 >= 1_000_000) shouldBe true
        val tiles = write("s1", grid)
        (tiles > 100) shouldBe true
        val provider = ActiveSurfaceProvider(repository, Dispatchers.IO)
        val centerE = e0 + 708.0
        val centerN = n0 + 708.0

        val elapsed = measureTime {
            runBlocking {
                withTimeout(10_000) {
                    provider.active(project).filterNotNull().first { active ->
                        var height = active.heightAt(centerE, centerN)
                        while (height == SurfaceHeight.Loading) {
                            delay(1)
                            height = active.heightAt(centerE, centerN)
                        }
                        height is SurfaceHeight.Value
                    }
                }
            }
        }

        println("Activar un millón de triángulos y la primera cota: ${elapsed.inWholeMilliseconds} ms ($tiles teselas)")
        elapsed.inWholeMilliseconds shouldBeLessThan 1_000L
    }

    @Test
    fun `S042 RNF la cota bajo la punta a 10 Hz no pasa de 16 ms por consulta`() {
        val grid = Grid(side = 708, e0 = e0, n0 = n0)
        write("s1", grid)
        val provider = ActiveSurfaceProvider(repository, Dispatchers.IO)
        runBlocking {
            withTimeout(60_000) {
                provider.active(project).filterNotNull().first { active ->
                    // Se camina en diagonal por la malla, como una punta: las teselas se piden al cruzar de una a otra. La primera
                    // pasada calienta la JIT y la caché. Lo que cuesta una consulta es el tiempo de CPU del hilo y el percentil 99 del
                    // reloj: un tirón del recolector de basura o del planificador de la JVM de pruebas para el hilo sin que trabaje.
                    val bean = java.lang.management.ManagementFactory.getThreadMXBean()

                    suspend fun walk(): Pair<List<Long>, Long> {
                        val wall = ArrayList<Long>()
                        var cpuLongest = 0L
                        for (step in 0 until 2_000) {
                            val e = e0 + 4.0 + step * 0.7
                            val n = n0 + 4.0 + step * 0.7
                            val cpu0 = bean.currentThreadCpuTime
                            val start = System.nanoTime()
                            active.heightAt(e, n)
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
                    println(
                        "2.000 consultas a lo largo de la malla: mediana ${sorted[sorted.size / 2]} us, p99 $p99 ms, CPU máxima $cpuLongest ms",
                    )
                    p99 shouldBeLessThan 16L
                    cpuLongest shouldBeLessThan 16L
                    true
                }
            }
        }
    }

    /** Un LandXML de [side]² celdas escrito al vuelo, sin guardarlo en memoria ni en disco. */
    private class GeneratedLandXml(private val side: Int) : InputStream() {
        private var buffer = ByteArray(0)
        private var position = 0
        private var stage = 0
        private var index = 0
        var produced = 0L
            private set

        private fun refill(): Boolean {
            val out = StringBuilder(64 * 1024)
            while (out.length < 60 * 1024) {
                when (stage) {
                    0 -> {
                        out.append("<?xml version=\"1.0\"?>\n<LandXML version=\"1.2\"><Units><Metric linearUnit=\"meter\"/></Units>")
                        out.append("<Surfaces><Surface name=\"grande\"><Definition surfType=\"TIN\"><Pnts>\n")
                        stage = 1
                    }

                    1 -> {
                        if (index >= (side + 1) * (side + 1)) {
                            out.append("</Pnts><Faces>\n")
                            stage = 2
                            index = 0
                        } else {
                            val i = index % (side + 1)
                            val j = index / (side + 1)
                            out.append("<P id=\"").append(index + 1).append("\">")
                            out.append(4_474_000.0 + j * 2.0).append(' ').append(440_000.0 + i * 2.0).append(' ')
                            out.append(600.0 + 0.01 * i + 0.02 * j).append("</P>\n")
                            index++
                        }
                    }

                    2 -> {
                        if (index >= side * side) {
                            out.append("</Faces></Definition></Surface></Surfaces></LandXML>\n")
                            stage = 3
                        } else {
                            val i = index % side
                            val j = index / side
                            val a = j * (side + 1) + i + 1
                            val c = a + side + 1
                            out.append("<F>").append(a).append(' ').append(a + 1).append(' ').append(c + 1).append("</F>\n")
                            out.append("<F>").append(a).append(' ').append(c + 1).append(' ').append(c).append("</F>\n")
                            index++
                        }
                    }

                    else -> break
                }
            }
            if (out.isEmpty()) return false
            buffer = out.toString().toByteArray()
            position = 0
            produced += buffer.size
            return true
        }

        override fun read(): Int {
            if (position >= buffer.size && !refill()) return -1
            return buffer[position++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= buffer.size && !refill()) return -1
            val n = minOf(len, buffer.size - position)
            System.arraycopy(buffer, position, b, off, n)
            position += n
            return n
        }
    }

    private fun usedMb(): Long = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1_000_000

    @Test
    fun `S042 RNF importar un LandXML de 100 MB tarda menos de 90 s y no se queda sin memoria`() {
        val importer = SurfaceImporter(repository)
        val generated = GeneratedLandXml(side = 1_000)
        var result: SurfaceImportResult? = null
        System.gc()
        val baseline = usedMb()
        var peak = baseline
        val sampler = thread(isDaemon = true) {
            while (!Thread.currentThread().isInterrupted) {
                System.gc() // lo vivo de verdad, no la basura que aún no se ha recogido
                peak = maxOf(peak, usedMb())
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    return@thread
                }
            }
        }
        val elapsed = measureTime {
            result = runBlocking {
                importer.import(project, ProjectCrs.ETRS89_UTM30, "grande.xml", 0, CadSourceCrs.Project, LinearUnit.METRE, { generated })
            }
        }

        println(
            "Importar ${generated.produced / 1_000_000} MB de LandXML: ${elapsed.inWholeSeconds} s, memoria viva de más como máximo: ${peak - baseline} MB",
        )
        (generated.produced >= 100_000_000L) shouldBe true
        val imported = result.shouldBeInstanceOf<SurfaceImportResult.Imported>()
        imported.summary.summary.triangleCount shouldBe 2_000_000
        imported.summary.summary.vertexCount shouldBe 1_002_001
        imported.summary.droppedFaces shouldBe 0
        elapsed.inWholeSeconds shouldBeLessThan 90L
        // Un móvil da a una app unos 256 a 512 MB de memoria: la importación de 100 MB no pasa de 400 MB sobre lo que ya había.
        (peak - baseline) shouldBeLessThan 400L
    }
}
