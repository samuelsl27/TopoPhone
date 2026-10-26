package es.topophone.data.surface

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.survey.TinDirectory
import es.topophone.core.survey.TinElevation
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
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

/** Importar un LandXML: escanear, leer, convertir, repartir en teselas, escribir, cancelar y resumir (S042/RF-5 a RF-15). */
@RunWith(AndroidJUnit4::class)
class SurfaceImporterTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val repository = RoomSurfaceRepository(db)

    private val project = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private var ids = 0
    private fun importer(repo: SurfaceRepository = repository) = SurfaceImporter(repo, clock = { 123_456L }, newId = { "id-${ids++}" })

    @After
    fun close() = db.close()

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }

    private fun fixture(name: String): ByteArray = File(root, "fixtures/superficies/$name").readBytes()

    private fun queries(): List<List<String>> = File(root, "fixtures/superficies/consultas.csv").readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .drop(1)
        .map { it.split(",") }

    private fun scan(file: String): SurfaceScanResult = runBlocking { importer().scan({ ByteArrayInputStream(fixture(file)) }) }

    private fun import(
        file: String,
        index: Int = 0,
        source: CadSourceCrs = CadSourceCrs.Project,
        unit: LinearUnit = LinearUnit.METRE,
        importer: SurfaceImporter = importer(),
        progress: (Long) -> Unit = {},
    ): SurfaceImportResult = runBlocking {
        importer.import(project, ProjectCrs.ETRS89_UTM30, file, index, source, unit, { ByteArrayInputStream(fixture(file)) }, progress)
    }

    private fun imported(result: SurfaceImportResult) = result.shouldBeInstanceOf<SurfaceImportResult.Imported>()

    private fun count(table: String): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
        it.moveToFirst()
        it.getInt(0)
    }

    /** La cota de la superficie guardada en una posición, leyendo las teselas de la base de datos como la app. */
    private fun heightAt(surfaceId: String, e: Double, n: Double): Double? = runBlocking {
        val cells = repository.cells(surfaceId)
        var found: Double? = null
        for (candidate in TinDirectory(cells.map { it.region }).candidates(e, n)) {
            found = TinElevation.at(repository.tile(cells[candidate].tileId)!!, e, n)
            if (found != null) break
        }
        found
    }

    @Test
    fun `S042 RF-5 el escaneo lista las superficies del fichero con su unidad`() {
        val found = scan("dos-superficies.xml").shouldBeInstanceOf<SurfaceScanResult.Found>().scan

        found.surfaces.map { it.name } shouldBe listOf("terreno", "rasante")
        found.importable.size shouldBe 2
        found.declaredUnit shouldBe LinearUnit.METRE
        scan("en-pies.xml").shouldBeInstanceOf<SurfaceScanResult.Found>().scan.declaredUnit shouldBe LinearUnit.INTERNATIONAL_FOOT
    }

    @Test
    fun `S042 RF-3 el escaneo dice por que un fichero no se puede leer`() {
        scan("mal-formado.xml") shouldBe SurfaceScanResult.Failed(SurfaceImportFailure.MALFORMED)
        val html = runBlocking { importer().scan({ ByteArrayInputStream("<html/>".toByteArray()) }) }
        html shouldBe SurfaceScanResult.Failed(SurfaceImportFailure.NOT_LANDXML)
        val version = runBlocking { importer().scan({ ByteArrayInputStream("<LandXML version=\"2.0\"/>".toByteArray()) }) }
        version shouldBe SurfaceScanResult.Failed(SurfaceImportFailure.UNSUPPORTED_VERSION, "2.0")
        val empty = runBlocking { importer().scan({ ByteArrayInputStream(ByteArray(0)) }) }
        empty shouldBe SurfaceScanResult.Failed(SurfaceImportFailure.MALFORMED)
        scan("sin-superficies.xml").shouldBeInstanceOf<SurfaceScanResult.Found>().scan.importable shouldBe emptyList()
    }

    @Test
    fun `S042 RF-13 importar el terreno guarda la superficie con el resumen y la deja activa`() {
        val result = imported(import("dos-superficies.xml"))

        result.summary.name shouldBe "terreno"
        result.summary.summary.vertexCount shouldBe 42
        result.summary.summary.triangleCount shouldBe 58
        result.summary.summary.holeCount shouldBe 2
        result.summary.droppedFaces shouldBe 0
        result.summary.activated shouldBe true
        (result.summary.summary.extent.maxEasting - result.summary.summary.extent.minEasting in 119.0..122.0) shouldBe true
        result.summary.summary.minElevationM shouldBe (600.0 plusOrMinus 1e-9)
        runBlocking { repository.activeSurfaceId(project).first() } shouldBe result.surfaceId
    }

    @Test
    fun `S042 RF-14 se guarda el nombre del fichero la fecha el origen y la unidad`() {
        imported(import("en-pies.xml", source = CadSourceCrs.Utm(GeodeticDatum.ETRS89, 30), unit = LinearUnit.INTERNATIONAL_FOOT))

        val listed = runBlocking { repository.surfaces(project).first() }.single()

        listed.fileName shouldBe "en-pies.xml"
        listed.importedAtMs shouldBe 123_456L
        listed.source shouldBe CadSourceCrs.Utm(GeodeticDatum.ETRS89, 30)
        listed.unit shouldBe LinearUnit.INTERNATIONAL_FOOT
    }

    @Test
    fun `S042 RF-22 la cota guardada coincide con la referencia a menos de 1 mm`() {
        val id = imported(import("dos-superficies.xml")).surfaceId

        for (q in queries()) {
            val z = heightAt(id, q[1].toDouble(), q[2].toDouble())
            val expected = q.getOrNull(3)?.takeIf { it.isNotEmpty() }?.toDouble()
            if (expected == null) z.shouldBeNull() else z!! shouldBe (expected plusOrMinus 1e-3)
        }
    }

    @Test
    fun `S042 RF-7 un fichero en pies da las mismas cotas que el de metros`() {
        val id = imported(import("en-pies.xml", unit = LinearUnit.INTERNATIONAL_FOOT)).surfaceId

        for (q in queries().filter { it[3].isNotEmpty() }) {
            heightAt(id, q[1].toDouble(), q[2].toDouble())!! shouldBe
                (q[3].toDouble() plusOrMinus 1e-3)
        }
    }

    @Test
    fun `S042 RF-6 un fichero en UTM 29 se convierte al sistema del proyecto con las mismas cotas`() {
        val id = imported(import("en-utm29.xml", source = CadSourceCrs.Utm(GeodeticDatum.ETRS89, 29))).surfaceId

        // Solo los puntos interiores: un vertice del contorno exterior puede quedar a ~1 mm fuera tras convertir de UTM 29 (RF-6, constitucion nº 2).
        for (q in queries().filter { it[0] == "dentro" }) {
            heightAt(id, q[1].toDouble(), q[2].toDouble())!! shouldBe
                (q[3].toDouble() plusOrMinus 1e-3)
        }
    }

    @Test
    fun `S042 RF-5 se importa la superficie que se elige y solo esa`() {
        val result = imported(import("dos-superficies.xml", index = 1))

        result.summary.name shouldBe "rasante"
        result.summary.summary.triangleCount shouldBe 2
        runBlocking { repository.surfaces(project).first() }.map { it.name } shouldBe listOf("rasante")
    }

    @Test
    fun `S042 RF-17 la segunda superficie importada no pasa a ser la activa`() {
        val first = imported(import("dos-superficies.xml", index = 0))
        val second = imported(import("dos-superficies.xml", index = 1))

        first.summary.activated shouldBe true
        second.summary.activated shouldBe false
        runBlocking { repository.activeSurfaceId(project).first() } shouldBe first.surfaceId
    }

    @Test
    fun `S042 RF-3 una malla regular una superficie que no existe o un fichero cortado no se importan`() {
        import("malla.xml") shouldBe SurfaceImportResult.Failed(SurfaceImportFailure.NO_SURFACE)
        import("dos-superficies.xml", index = 7) shouldBe SurfaceImportResult.Failed(SurfaceImportFailure.NO_SURFACE)
        import("mal-formado.xml") shouldBe SurfaceImportResult.Failed(SurfaceImportFailure.MALFORMED)
        import("sin-superficies.xml") shouldBe SurfaceImportResult.Failed(SurfaceImportFailure.NO_SURFACE)
        count("surfaces") shouldBe 0
        count("surface_tiles") shouldBe 0
    }

    @Test
    fun `S042 RF-6 una superficie fuera del ambito del sistema del proyecto no se importa`() {
        val result = import("dos-superficies.xml", unit = LinearUnit.US_SURVEY_FOOT)

        result shouldBe SurfaceImportResult.Failed(SurfaceImportFailure.OUTSIDE_CRS_AREA)
        count("surfaces") shouldBe 0
    }

    @Test
    fun `S042 RF-11 cancelar a mitad no deja nada`() {
        val cancelling = object : SurfaceRepository by repository {
            override suspend fun addTiles(surfaceId: String, tiles: List<SurfaceTileWrite>) =
                throw CancellationException("el usuario cancela")
        }
        val outcome = runBlocking {
            val deferred = async(Dispatchers.Default) {
                importer(cancelling).import(
                    project,
                    ProjectCrs.ETRS89_UTM30,
                    "dos-superficies.xml",
                    0,
                    CadSourceCrs.Project,
                    LinearUnit.METRE,
                    { ByteArrayInputStream(fixture("dos-superficies.xml")) },
                )
            }
            runCatching { deferred.await() }
        }

        (outcome.exceptionOrNull() is CancellationException) shouldBe true
        count("surfaces") shouldBe 0
        count("surface_tiles") shouldBe 0
    }

    @Test
    fun `S042 RF-11 cancelar mientras se lee no deja nada`() {
        val outcome = runBlocking {
            val deferred = async(Dispatchers.Default) {
                importer().import(
                    project,
                    ProjectCrs.ETRS89_UTM30,
                    "dos-superficies.xml",
                    0,
                    CadSourceCrs.Project,
                    LinearUnit.METRE,
                    { ByteArrayInputStream(fixture("dos-superficies.xml")) },
                ) { throw CancellationException("el usuario cancela") }
            }
            runCatching { deferred.await() }
        }

        (outcome.exceptionOrNull() is CancellationException) shouldBe true
        count("surfaces") shouldBe 0
    }

    @Test
    fun `S042 RF-11 un fallo de escritura no deja nada e indica que no se importo`() {
        val failing = object : SurfaceRepository by repository {
            override suspend fun addTiles(surfaceId: String, tiles: List<SurfaceTileWrite>) = throw IOException("disco lleno")
        }

        val result = import("dos-superficies.xml", importer = importer(failing))

        result shouldBe SurfaceImportResult.Failed(SurfaceImportFailure.WRITE_ERROR)
        count("surfaces") shouldBe 0
        count("surface_tiles") shouldBe 0
    }

    @Test
    fun `S042 RF-11 un fallo de lectura no deja nada e indica que no se importo`() {
        val broken = object : InputStream() {
            private val data = fixture("dos-superficies.xml")
            private var i = 0

            override fun read(): Int = if (i < 500) data[i++].toInt() and 0xFF else throw IOException("tarjeta quitada")
        }

        val result = runBlocking {
            importer().import(project, ProjectCrs.ETRS89_UTM30, "dos-superficies.xml", 0, CadSourceCrs.Project, LinearUnit.METRE, {
                broken
            })
        }

        result shouldBe SurfaceImportResult.Failed(SurfaceImportFailure.READ_ERROR)
        count("surfaces") shouldBe 0
    }

    @Test
    fun `S042 RF-10 el progreso avisa de los bytes leidos hasta el final del fichero`() {
        val seen = mutableListOf<Long>()

        imported(import("dos-superficies.xml", progress = { seen += it }))

        seen.last() shouldBe fixture("dos-superficies.xml").size.toLong()
    }

    @Test
    fun `S042 RF-15 importar dos veces el mismo fichero crea otra superficie aparte`() {
        val first = imported(import("dos-superficies.xml"))
        val second = imported(import("dos-superficies.xml"))

        (first.surfaceId != second.surfaceId) shouldBe true
        runBlocking { repository.surfaces(project).first() }.size shouldBe 2
        count("surface_tiles") shouldBe 2
    }
}
