package es.topophone.data.shp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.gpkg.GpkgCrs
import es.topophone.core.formats.shp.ShpCrsCatalog
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.model.cad.CadExtent
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.cad.DesignLayerKind
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.cad.RoomCadRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.gpkg.GeoPackageImporter
import es.topophone.data.gpkg.GpkgImportOptions
import es.topophone.data.gpkg.GpkgImportResult
import es.topophone.data.gpkg.GpkgLayerChoice
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Importar un Shapefile en un SC de la biblioteca (ETRS89 / UTM 33N, que no es de los de España) en un proyecto en UTM 30N, frente a pyproj a menos
 * de 1 mm (S057/RF-16, RF-4; constitución nº 2). Lo esperado sale de `tools/formatos/generar_biblioteca.py`.
 */
@RunWith(AndroidJUnit4::class)
class ShpLibraryImportTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val repository = RoomCadRepository(db)
    private val projectId = runBlocking {
        RoomProjectRepository(db).create(ProjectDefaults().toDraft().copy(name = "Parcelas")).shouldBeInstanceOf<SaveResult.Saved>().id
    }
    private val expected = ShpFixtures.expected("../biblioteca/esperado-shp-utm33.csv")
    private val sessions = ArrayList<ShapefileSession>()

    @After
    fun close() {
        sessions.forEach { it.close() }
        db.close()
    }

    private fun session(): ShapefileSession {
        val files = File(ShpFixtures.folder, "sistemas").listFiles().orEmpty()
            .filter { it.nameWithoutExtension == "utm33" }
            .map { "sistemas/${it.name}" }
        val reader = ShapefileReader(folder.newFolder("cache"), freeSpace = { Long.MAX_VALUE })
        return runBlocking { reader.open(files.map { ShpFixtures.input(it) }) }
            .shouldBeInstanceOf<ShpOpenResult.Opened>().session.also { sessions += it }
    }

    @Test
    fun `S057 RF-4 el prj de la capa se reconoce como un SC de la biblioteca y no como uno de Espana`() {
        val prj = ShpFixtures.file("sistemas/utm33.prj").readText()

        ShpCrsCatalog.resolve(prj).shouldBeInstanceOf<GpkgCrs.Library>().epsg shouldBe 25833
    }

    @Test
    fun `S057 RF-16 los puntos de un Shapefile en un SC de la biblioteca entran como en pyproj a menos de 1 mm`() {
        val session = session()
        val library = ShpCrsCatalog.resolve(ShpFixtures.file("sistemas/utm33.prj").readText()).shouldBeInstanceOf<GpkgCrs.Library>()
        val definition = CrsCatalog.bundled.build(library.epsg, CorrectionsDatum.ETRS89, GeoPoint(40.4, -3.7))
            .shouldBeInstanceOf<CrsBuildResult.Ready>().definition
        val choice = GpkgLayerChoice(session.layers.single(), CadSourceCrs.Library(definition))
        val importer = GeoPackageImporter(repository, clock = { 1L }, newId = { "dibujo" })

        val result = runBlocking {
            importer.import(projectId, ProjectCrs.ETRS89_UTM30, "utm33.shp", GpkgImportOptions(listOf(choice)), session)
        }

        result.shouldBeInstanceOf<GpkgImportResult.Imported>().summary.importedTotal shouldBe 3
        val drawing = runBlocking { repository.drawings(projectId).first() }.single()
        val layer = drawing.layers.first { it.kind == DesignLayerKind.POINTS }
        val points = runBlocking { repository.chunksIn(listOf(layer.id), CadExtent(-1e9, 1e9, -1e9, 1e9)) }.flatMap { it.entities }
        points.size shouldBe expected.size
        for (row in expected) {
            val e = row.getValue("este_m").toDouble()
            val n = row.getValue("norte_m").toDouble()
            (points.any { abs(it.easting[0] - e) < 1e-3 && abs(it.northing[0] - n) < 1e-3 }) shouldBe true
        }
    }
}
