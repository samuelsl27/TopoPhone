package es.topophone.data.alignment

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.InputStream
import kotlin.time.measureTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Importar un LandXML de 100 MB con un eje de 50 km tarda menos de 10 s (S049, requisito no funcional de la spec). Robolectric hace de cota
 * superior del móvil de referencia; la demo en el emulador confirma el orden de magnitud.
 */
@RunWith(AndroidJUnit4::class)
class AlignmentPerformanceTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val repository = RoomAlignmentRepository(db)
    private val project = runBlocking {
        RoomProjectRepository(db).create(ProjectDefaults().toDraft().copy(name = "Obra lineal")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @After
    fun close() = db.close()

    /**
     * Un LandXML escrito al vuelo: la mitad del peso en puntos de control (`CgPoints`), un eje de 50 km hacia el Este hecho de 5.000 rectas de
     * 10 m con una rasante de 500 vértices, y la otra mitad en más puntos de control. No se guarda en memoria ni en disco.
     */
    private class GeneratedLandXml(private val bulkBytes: Long) : InputStream() {
        private var buffer = ByteArray(0)
        private var position = 0
        private var stage = 0
        private var index = 0
        var produced = 0L
            private set

        private fun refill(): Boolean {
            val out = StringBuilder(64 * 1024)
            while (out.length < 60 * 1024 && stage != STAGE_DONE) {
                when (stage) {
                    0 -> {
                        out.append("<?xml version=\"1.0\"?>\n<LandXML version=\"1.2\"><Units><Metric linearUnit=\"meter\"/></Units>")
                        out.append("<CgPoints>\n")
                        stage = 1
                    }
                    1, 4 -> bulk(out, if (stage == 1) bulkBytes / 2 else bulkBytes)
                    2 -> {
                        out.append("<Alignments><Alignment name=\"Autovia\" length=\"50000\" staStart=\"0\"><CoordGeom>")
                        stage = 3
                        index = 0
                    }
                    3 -> axis(out)
                    5 -> {
                        out.append("</CgPoints></LandXML>\n")
                        stage = STAGE_DONE
                    }
                }
            }
            if (out.isEmpty()) return false
            buffer = out.toString().toByteArray()
            position = 0
            produced += buffer.size
            return true
        }

        private var bulked = 0L

        private fun bulk(out: StringBuilder, limit: Long) {
            if (bulked >= limit) {
                if (stage == 1) {
                    out.append("</CgPoints>")
                    stage = 2
                } else {
                    stage = 5
                }
                return
            }
            val before = out.length
            out.append("<CgPoint name=\"P").append(index).append("\" code=\"x\">")
                .append(4_474_000.0 + index % 1000).append(' ').append(300_000.0 + index % 777).append(' ').append(600.0 + index % 31)
                .append("</CgPoint>\n")
            bulked += out.length - before
            index++
        }

        private var elementsWritten = 0

        private fun axis(out: StringBuilder) {
            if (elementsWritten >= ELEMENTS) {
                out.append("</CoordGeom><Profile name=\"p\"><ProfAlign name=\"r\">")
                for (i in 0..PVIS) {
                    out.append("<PVI>").append(i * (50_000.0 / PVIS)).append(' ').append(600.0 + (i % 7) * 2.0).append("</PVI>")
                }
                out.append("</ProfAlign></Profile></Alignment></Alignments><CgPoints>\n")
                stage = 4
                index = 0
                return
            }
            val k = elementsWritten++
            val e0 = 300_000.0 + k * 10.0
            out.append("<Line dir=\"90\" length=\"10\"><Start>4474000.0 ").append(e0).append("</Start><End>4474000.0 ").append(e0 + 10.0)
                .append("</End></Line>")
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

        companion object {
            const val STAGE_DONE = 99
            const val ELEMENTS = 5_000
            const val PVIS = 500
        }
    }

    @Test
    fun `S049 RNF importar un LandXML de 100 MB con un eje de 50 km tarda menos de 10 s`() {
        val importer = AlignmentImporter(repository)
        val scanned = GeneratedLandXml(bulkBytes = 100_000_000L)
        val imported = GeneratedLandXml(bulkBytes = 100_000_000L)
        var scan: AlignmentScanResult? = null
        var result: AlignmentImportResult? = null

        // La lista de alineaciones lee el fichero entero y la importación hasta el final de la alineación elegida.
        val elapsed = measureTime {
            scan = runBlocking { importer.scan({ scanned }) }
            result = runBlocking {
                importer.importLandXml(
                    project,
                    ProjectCrs.ETRS89_UTM30,
                    "autovia.xml",
                    setOf(0),
                    CadSourceCrs.Project,
                    LinearUnit.METRE,
                    { imported },
                )
            }
        }

        println(
            "Explorar ${scanned.produced / 1_000_000} MB e importar ${imported.produced / 1_000_000} MB con un eje de 50 km: ${elapsed.inWholeMilliseconds} ms",
        )
        (scanned.produced >= 100_000_000L) shouldBe true
        scan.shouldBeInstanceOf<AlignmentScanResult.Found>().scan.alignments.single().elementCount shouldBe 5_000
        val summary = result.shouldBeInstanceOf<AlignmentImportResult.Done>().summary
        summary.ignored shouldBe emptyList()
        summary.imported.single().lengthM shouldBe (50_000.0 plusOrMinus 1e-6)
        summary.imported.single().hasProfile shouldBe true
        elapsed.inWholeSeconds shouldBeLessThan 10L
        val saved = runBlocking { repository.alignment(summary.imported.single().id) }.shouldNotBeNull()
        saved.elements.size shouldBe 5_000
        runBlocking { repository.summaries(project).first() }.size shouldBe 1
    }
}
