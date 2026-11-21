package es.topophone.data.gpkg

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadExtent
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.cad.DesignLayerKind
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.cad.RoomCadRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Importar una capa de un GeoPackage en un SC de la biblioteca (EPSG:3857) en un proyecto de España y en uno de la biblioteca, frente a pyproj a
 * menos de 1 mm (S057/RF-16; constitución nº 2). Lo esperado sale de `tools/formatos/generar_biblioteca.py`.
 */
@RunWith(AndroidJUnit4::class)
class GpkgLibraryImportTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val repository = RoomCadRepository(db)
    private val projectId = runBlocking {
        RoomProjectRepository(db).create(ProjectDefaults().toDraft().copy(name = "Madrid")).shouldBeInstanceOf<SaveResult.Saved>().id
    }
    private val expected = GpkgFixtures.expected("../biblioteca/esperado-gpkg-3857.csv")
    private val everywhere = CadExtent(-1e9, 1e9, -1e9, 1e9)

    @After
    fun close() = db.close()

    private fun session(name: String): GpkgSession {
        val bytes = GpkgFixtures.bytes(name)
        val result = runBlocking { GeoPackageReader(folder.newFolder()).open(bytes.size.toLong()) { ByteArrayInputStream(bytes) } }
        return result.shouldBeInstanceOf<GpkgOpenResult.Opened>().session
    }

    private fun source(epsg: Int): CadSourceCrs.Library {
        val result = CrsCatalog.bundled.build(epsg, CorrectionsDatum.ETRS89, GeoPoint(40.4, -3.7))
        return CadSourceCrs.Library(result.shouldBeInstanceOf<CrsBuildResult.Ready>().definition)
    }

    private fun importInto(project: ProjectCrs): List<CadEntity> {
        session("no-disponible-3857.gpkg").use { session ->
            val choice = GpkgLayerChoice(session.layers.single(), source(3857), nameField = "nombre")
            val importer = GeoPackageImporter(repository, clock = { 1L }, newId = { "dibujo" })

            val result = runBlocking {
                importer.import(projectId, project, "madrid.gpkg", GpkgImportOptions(listOf(choice)), session)
            }

            result.shouldBeInstanceOf<GpkgImportResult.Imported>().summary.importedTotal shouldBe 3
            val drawing = runBlocking { repository.drawings(projectId).first() }.single()
            val layer = drawing.layers.first { it.kind == DesignLayerKind.POINTS }
            return runBlocking { repository.chunksIn(listOf(layer.id), everywhere) }.flatMap { it.entities }
        }
    }

    private fun assertMatchesPyproj(points: List<CadEntity>) {
        points.size shouldBe expected.size
        for (row in expected) {
            val e = row.getValue("este_m").toDouble()
            val n = row.getValue("norte_m").toDouble()
            val found = points.firstOrNull { abs(it.easting[0] - e) < 1e-3 && abs(it.northing[0] - n) < 1e-3 }
            (found != null) shouldBe true
            found!!.name shouldBe row.getValue("nombre")
        }
    }

    @Test
    fun `S057 RF-16 una capa en un SC de la biblioteca entra en un proyecto de Espana como en pyproj a menos de 1 mm`() {
        assertMatchesPyproj(importInto(ProjectCrs.ETRS89_UTM30))
    }

    @Test
    fun `S057 RF-16 una capa en un SC de la biblioteca entra en un proyecto de la biblioteca como en pyproj a menos de 1 mm`() {
        val definition = (CrsCatalog.bundled.build(25830, CorrectionsDatum.ETRS89, GeoPoint(40.4, -3.7)) as CrsBuildResult.Ready).definition

        assertMatchesPyproj(importInto(LibraryCrs(definition)))
    }

    @Test
    fun `S057 RF-3 la unidad de la capa es la del SC de origen`() {
        val definition = source(3857).definition

        CadSourceCrs.Library(definition.copy(unitMetres = 0.3048)).unit.metres shouldBe 0.3048
        CadSourceCrs.Library(definition.copy(unitMetres = 1200.0 / 3937.0)).unit.metres shouldBe 1200.0 / 3937.0
        CadSourceCrs.Library(definition).unit.metres shouldBe 1.0
    }
}
