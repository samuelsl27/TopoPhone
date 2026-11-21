package es.topophone.app.ui.raster

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.app.ui.points.ImportSource
import es.topophone.app.ui.projects.ProjectCenterProvider
import es.topophone.core.formats.raster.RasterCrsDeclaration
import es.topophone.core.formats.raster.RasterLimits
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.survey.HeightConversion
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.map.RoomMapViewportRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.data.surface.RasterImporter
import es.topophone.data.surface.RoomSurfaceRepository
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Importar un ráster con un SC de la biblioteca: el que declara el GeoTIFF se reconoce y se propone, la extensión sale como en un ráster de
 * España y las cotas dicen si se pueden convertir (S057/RF-1, RF-4, RF-15, RF-19, RF-20). Los GeoTIFF de prueba son los de `fixtures/raster/` con
 * el código EPSG de su clave de GeoTIFF cambiado.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class RasterImportLibraryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val surfaces = RoomSurfaceRepository(db)
    private val viewports = RoomMapViewportRepository(db)
    private val dispatcher = UnconfinedTestDispatcher()
    private var projectCount = 0

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun newProject(crs: ProjectCrs, height: HeightSystem = HeightSystem.ORTHOMETRIC): String = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Proyecto ${projectCount++}", crs = crs, heightSystem = height))
            .shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private fun runBlocking(block: suspend () -> String): String = kotlinx.coroutines.runBlocking { block() }

    /** El GeoTIFF [name] con el código de su ProjectedCSTypeGeoKey (3072) cambiado de [from] a [to]. */
    private fun withEpsg(name: String, from: Int, to: Int): ImportSource {
        val data = RasterTestFiles.bytes(name)
        val key = ByteBuffer.allocate(
            8,
        ).order(ByteOrder.LITTLE_ENDIAN).putShort(3072).putShort(0).putShort(1).putShort(from.toShort()).array()
        val at = data.indices.firstOrNull { i -> i + 8 <= data.size && (0 until 8).all { data[i + it] == key[it] } }
        requireNotNull(at) { "$name no declara EPSG:$from" }
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putShort(at + 6, to.toShort())
        return ImportSource(name, data.size.toLong()) { ByteArrayInputStream(data) }
    }

    private fun open(source: ImportSource, project: String): RasterImportViewModel {
        val importer = RasterImporter(surfaces, folder.newFolder(), maxFileBytes = RasterLimits.MAX_FILE_BYTES)
        val vm = RasterImportViewModel(surfaces, importer, projects, ProjectCenterProvider(FakePointRepository(), viewports), dispatcher)
        vm.start(project, source)
        vm.settled()
        return vm
    }

    private fun library(
        epsg: Int,
        corrections: CorrectionsDatum = CorrectionsDatum.ETRS89,
        at: GeoPoint = GeoPoint(40.4, -3.7),
    ): LibraryCrs {
        val definition = (CrsCatalog.bundled.build(epsg, corrections, at) as CrsBuildResult.Ready).definition
        return LibraryCrs(definition, corrections)
    }

    @Test
    fun `S057 RF-4 un GeoTIFF con un EPSG de la biblioteca se reconoce y se propone como origen`() {
        val vm = open(withEpsg("f32-sin-tiras-area.tif", 25830, 32630), newProject(ProjectCrs.ETRS89_UTM30))

        val state = vm.state.value
        state.declaration.shouldBeInstanceOf<RasterCrsDeclaration.Library>().epsg shouldBe 32630
        state.source.shouldBeInstanceOf<CadSourceCrs.Library>().definition.epsg shouldBe 32630
        state.library.shouldNotBeNull()
    }

    @Test
    fun `S057 RF-15 el raster en un SC de la biblioteca ocupa la misma extension que el de Espana a 1 mm`() {
        val project = newProject(ProjectCrs.ETRS89_UTM30)
        val spain = open(RasterTestFiles.source("f32-sin-tiras-area.tif"), project).state.value.extent.shouldNotBeNull()

        val vm = open(withEpsg("f32-sin-tiras-area.tif", 25830, 32630), project)
        vm.confirmLibrary(true) // el cambio de datum de WGS 84 a ETRS89 puede pedir confirmación
        val extent = vm.state.value.extent.shouldNotBeNull()

        extent.minEasting shouldBe (spain.minEasting plusOrMinus 1e-3)
        extent.maxNorthing shouldBe (spain.maxNorthing plusOrMinus 1e-3)
        vm.state.value.canImport shouldBe true
    }

    @Test
    fun `S057 RF-12 un GeoTIFF en el SC del proyecto no se reproyecta ni pide aviso`() {
        val vm = open(RasterTestFiles.source("f32-sin-tiras-area.tif"), newProject(ProjectCrs.ETRS89_UTM30))

        vm.state.value.source shouldBe CadSourceCrs.Project
        vm.state.value.library.shouldBeNull()
    }

    @Test
    fun `S057 RF-20 un proyecto de la biblioteca sin geoide no convierte las cotas pero importa la geometria`() {
        val projectCrs = library(25830)
        val vm = open(RasterTestFiles.source("f32-sin-tiras-area.tif"), newProject(projectCrs, HeightSystem.ELLIPSOIDAL))

        vm.setHeightSystem(HeightSystem.ORTHOMETRIC)

        vm.state.value.heightConversion shouldBe HeightConversion.NoProjectGeoid
        vm.state.value.geoidShiftM.shouldBeNull()
        vm.state.value.canImport shouldBe true
    }

    @Test
    fun `S057 RF-19 con el geoide del proyecto instalado las cotas se convierten`() {
        val projectCrs = library(25830).copy(geoidGrid = "es_ign_egm08-rednap.tif")
        val vm = open(RasterTestFiles.source("f32-sin-tiras-area.tif"), newProject(projectCrs, HeightSystem.ELLIPSOIDAL))

        vm.setHeightSystem(HeightSystem.ORTHOMETRIC)

        vm.state.value.heightConversion shouldBe HeightConversion.Available
        vm.state.value.geoidShiftM.shouldNotBeNull()
    }
}
