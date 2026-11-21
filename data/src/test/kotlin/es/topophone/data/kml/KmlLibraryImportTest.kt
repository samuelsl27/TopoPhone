package es.topophone.data.kml

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.gpkg.GpkgCrs
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadExtent
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.LibraryCrs
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
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Importar un KML en un proyecto en un SC de la biblioteca: las geográficas del KML se toman como las del datum de las correcciones y salen en el
 * SC del proyecto lo mismo que en un proyecto de España con el mismo SC, a menos de 1 mm (S057/RF-17; constitución nº 2).
 */
@RunWith(AndroidJUnit4::class)
class KmlLibraryImportTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val repository = RoomCadRepository(db)
    private val projects = RoomProjectRepository(db)
    private val everywhere = CadExtent(-1e9, 1e9, -1e9, 1e9)
    private val sessions = ArrayList<KmlSession>()
    private var cacheId = 0

    @After
    fun close() {
        sessions.forEach { it.close() }
        db.close()
    }

    private fun newProject(name: String) = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = name)).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private fun open(path: String): KmlSession {
        val reader = KmlReader(folder.newFolder("c${cacheId++}"))
        val opened = runBlocking { reader.open(KmlFixtures.input(path), GeodeticDatum.ETRS89) }
        return opened.shouldBeInstanceOf<KmlOpenResult.Opened>().session.also { sessions += it }
    }

    private fun importInto(project: ProjectCrs, file: String): List<CadEntity> {
        val projectId = newProject("p${cacheId++}")
        val session = open(file)
        val choices = session.layers.map { layer ->
            val supported = session.crs(layer).shouldBeInstanceOf<GpkgCrs.Supported>()
            GpkgLayerChoice(layer, supported.source, "name", wgs84 = supported.wgs84)
        }
        val importer = GeoPackageImporter(repository, clock = { 1L }, newId = { "id-${cacheId++}" })

        val result = runBlocking { importer.import(projectId, project, "obra.kml", GpkgImportOptions(choices), session) }

        result.shouldBeInstanceOf<GpkgImportResult.Imported>()
        val drawing = runBlocking { repository.drawings(projectId).first() }.single()
        return runBlocking { repository.chunksIn(drawing.layers.map { it.id }, everywhere) }.flatMap { it.entities }
    }

    @Test
    fun `S057 RF-17 un KML en un proyecto de la biblioteca sale como en un proyecto de Espana con el mismo SC a menos de 1 mm`() {
        val library = LibraryCrs(
            (CrsCatalog.bundled.build(25830, CorrectionsDatum.ETRS89, GeoPoint(40.4, -3.7)) as CrsBuildResult.Ready).definition,
        )

        val spain = importInto(ProjectCrs.ETRS89_UTM30, "geometrias.kml")
        val lib = importInto(library, "geometrias.kml")

        lib.size shouldBe spain.size
        (lib.size > 3) shouldBe true
        val left = lib.toMutableList()
        for (entity in spain) {
            val index = left.indexOfFirst { other ->
                other.vertexCount == entity.vertexCount &&
                    (0 until entity.vertexCount).all {
                        abs(other.easting[it] - entity.easting[it]) < 1e-3 && abs(other.northing[it] - entity.northing[it]) < 1e-3
                    }
            }
            (index >= 0) shouldBe true
            left.removeAt(index)
        }
    }
}
