package es.topophone.data.cad

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.cad.CadChunkCodec
import es.topophone.core.model.cad.CadImportOptions
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.InputStream
import kotlin.time.measureTimedValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Un DXF sintético de 100 MB se explora en menos de 20 s y se importa en menos de 60 s sin pasar del límite de memoria de los
 * buffers (S040, RNF). Robolectric sirve de cota; la medida en el móvil de referencia es de la demo.
 */
@RunWith(AndroidJUnit4::class)
class CadPerformanceTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val cad = RoomCadRepository(db)

    @After
    fun close() = db.close()

    /** Un DXF de [size] bytes que no se guarda: se genera al leerlo, entidad a entidad, en una malla de 20 × 20 km sobre Madrid. */
    private class Generated(private val size: Long) : InputStream() {
        private val chunk = StringBuilder()
        private var pending = ByteArray(0)
        private var offset = 0
        private var produced = 0L
        private var index = 0
        var entities = 0
            private set
        private var closing = false

        init {
            chunk.append("0\nSECTION\n2\nENTITIES\n")
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (offset >= pending.size) {
                if (!refill()) return -1
            }
            val n = minOf(len, pending.size - offset)
            System.arraycopy(pending, offset, b, off, n)
            offset += n
            return n
        }

        private fun refill(): Boolean {
            if (closing && chunk.isEmpty()) return false
            while (chunk.length < BLOCK && produced + chunk.length < size) {
                val i = index++
                val e = 400_000.0 + (i % 2000) * 10.0
                val n = 4_460_000.0 + (i / 2000 % 2000) * 10.0
                if (i % 5 == 0) {
                    chunk.append(
                        "0\nPOINT\n8\nPuntos\n10\n",
                    ).append(e).append("\n20\n").append(n).append("\n30\n").append(600.0 + i % 50).append('\n')
                } else {
                    chunk.append("0\nLINE\n8\nEjes\n10\n").append(e).append("\n20\n").append(n).append("\n30\n").append(600.0)
                        .append("\n11\n").append(e + 8.0).append("\n21\n").append(n + 6.0).append("\n31\n").append(601.0).append('\n')
                }
                entities++
            }
            if (produced + chunk.length >= size) {
                chunk.append("0\nENDSEC\n0\nEOF\n")
                closing = true
            }
            pending = chunk.toString().toByteArray()
            produced += pending.size
            chunk.setLength(0)
            offset = 0
            return pending.isNotEmpty()
        }

        private companion object {
            const val BLOCK = 64 * 1024
        }
    }

    private fun newProject(): String = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Obra grande", crs = ProjectCrs.ETRS89_UTM30))
            .shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @Test
    fun `S040 RNF un DXF de 100 MB se explora en menos de 20 s`() {
        val source = Generated(SIZE)

        val (scan, time) = measureTimedValue { runBlocking { CadDrawingImporter(cad).scan({ source }) } }

        println("S040 exploración de 100 MB: ${time.inWholeMilliseconds} ms, ${source.entities} elementos")
        scan.elements shouldBe source.entities
        scan.complete shouldBe true
        (time.inWholeSeconds < 20) shouldBe true
    }

    @Test
    fun `S040 RNF un DXF de 100 MB se importa en menos de 60 s con los buffers acotados`() {
        val projectId = newProject()
        val source = Generated(SIZE)
        val importer = CadDrawingImporter(cad)

        val (result, time) = measureTimedValue {
            runBlocking { importer.import(projectId, ProjectCrs.ETRS89_UTM30, "obra.dxf", CadImportOptions(), { source }) }
        }

        println(
            "S040 importación de 100 MB: ${time.inWholeMilliseconds} ms, ${source.entities} elementos, pico de buffers ${importer.peakBufferedBytes / 1024} kB",
        )
        val imported = result.shouldBeInstanceOf<CadImportResult.Imported>()
        imported.summary.importedTotal shouldBe source.entities
        (time.inWholeSeconds < 60) shouldBe true
        (importer.peakBufferedBytes <= CadDrawingImporter.MAX_BUFFERED_BYTES + CadChunkCodec.MAX_CHUNK_BYTES) shouldBe true
        val layers = runBlocking { cad.drawings(projectId).first() }.single().layers
        layers.sumOf { it.entityCount } shouldBe source.entities
    }

    private companion object {
        const val SIZE = 100L * 1024 * 1024
    }
}
