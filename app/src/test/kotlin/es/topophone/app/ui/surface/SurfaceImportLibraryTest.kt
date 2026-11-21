package es.topophone.app.ui.surface

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.app.ui.projects.ProjectCenterProvider
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.map.RoomMapViewportRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.data.surface.RoomSurfaceRepository
import es.topophone.data.surface.SurfaceImporter
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Importar las superficies de un LandXML con un SC de origen de la biblioteca: origen, unidad, extensión a 1 mm de pyproj y aviso (S057/RF-1, RF-3, RF-14). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SurfaceImportLibraryTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val surfaces = RoomSurfaceRepository(db)
    private val viewports = RoomMapViewportRepository(db)
    private val dispatcher = UnconfinedTestDispatcher()
    private val projectId = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Obra", crs = ProjectCrs.ETRS89_UTM30))
            .shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun open(name: String): SurfaceImportViewModel {
        val vm = SurfaceImportViewModel(
            surfaces,
            SurfaceImporter(surfaces),
            projects,
            ProjectCenterProvider(FakePointRepository(), viewports),
            dispatcher,
        )
        vm.start(projectId, SurfaceTestFiles.source(name))
        vm.settled()
        return vm
    }

    private fun library(epsg: Int, at: GeoPoint = GeoPoint(40.4, -3.7)): LibraryCrs {
        val definition = (CrsCatalog.bundled.build(epsg, CorrectionsDatum.ETRS89, at) as CrsBuildResult.Ready).definition
        return LibraryCrs(definition, CorrectionsDatum.ETRS89)
    }

    @Test
    fun `S057 RF-14 el LandXML en un SC de la biblioteca sale como en el UTM de Espana a 1 mm y la unidad es la del SC`() {
        val viaUtm = open("en-utm29.xml")
        viaUtm.setSource(CadSourceCrs.Utm(GeodeticDatum.ETRS89, 29))
        val expected = viaUtm.state.value.extent.shouldNotBeNull()

        val vm = open("en-utm29.xml")
        vm.chooseLibrary(library(25829))

        val state = vm.state.value
        state.source.shouldBeInstanceOf<CadSourceCrs.Library>()
        state.unit.metres shouldBe 1.0
        val extent = state.extent.shouldNotBeNull()
        extent.minEasting shouldBe (expected.minEasting plusOrMinus 1e-3)
        extent.maxNorthing shouldBe (expected.maxNorthing plusOrMinus 1e-3)
        state.canImport shouldBe true
    }

    @Test
    fun `S057 RF-9 con otro datum de baja precision hay que confirmar el aviso`() {
        val vm = open("en-utm29.xml")

        vm.chooseLibrary(library(3844, GeoPoint(44.43, 26.1))) // Stereo 70: el cambio de datum es de 3 m

        val transformation = vm.state.value.library.shouldNotBeNull().transformation.shouldNotBeNull()
        transformation.needsConfirmation shouldBe true
        vm.state.value.library.shouldNotBeNull().ready shouldBe false
        vm.confirmLibrary(true)
        vm.state.value.library.shouldNotBeNull().ready shouldBe true
    }

    @Test
    fun `S057 RF-27 volver al SC del proyecto quita la restriccion de la biblioteca`() {
        val vm = open("en-utm29.xml")
        vm.chooseLibrary(library(3844, GeoPoint(44.43, 26.1)))

        vm.setSource(CadSourceCrs.Project)

        vm.state.value.source shouldBe CadSourceCrs.Project
    }
}
