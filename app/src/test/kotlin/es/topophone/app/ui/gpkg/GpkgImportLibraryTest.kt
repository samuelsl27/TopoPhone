package es.topophone.app.ui.gpkg

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.gpkg.GpkgCrs
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.Utm
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.CentralPoint
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.cad.RoomCadRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.map.RoomMapViewportRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Importar un GeoPackage con capas en un SC de la biblioteca: se reconoce el de cada capa, se puede elegir uno cuando no se reconoce y la importación
 * espera al aviso de precisión; la extensión sale a 1 mm de pyproj (S057/RF-4, RF-6, RF-8 a RF-10, RF-16).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class GpkgImportLibraryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val viewports = RoomMapViewportRepository(db)
    private val cad = RoomCadRepository(db)
    private val dispatcher = UnconfinedTestDispatcher()
    private val madrid = Utm.inverse(440_000.0, 4_474_000.0, 30).let { CentralPoint(it.latitudeDeg, it.longitudeDeg) }
    private var counter = 0

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        disposeGpkgViewModels()
        Dispatchers.resetMain()
        db.close()
    }

    private fun newProject(crs: ProjectCrs = ProjectCrs.ETRS89_UTM30): String = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Proyecto ${counter++}", crs = crs, centralPoint = madrid))
            .shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private fun open(file: String, project: String = newProject()): GpkgImportViewModel {
        val viewModel = gpkgViewModel(folder.newFolder(), projects, viewports, dispatcher, cad)
        viewModel.start(project, GpkgTestFiles.source(file))
        return viewModel
    }

    private fun GpkgImportUiState.layer(table: String) = layers.first { it.info.table == table }

    private fun library(epsg: Int, at: GeoPoint = GeoPoint(40.4, -3.7)): LibraryCrs {
        val definition = (CrsCatalog.bundled.build(epsg, CorrectionsDatum.ETRS89, at) as CrsBuildResult.Ready).definition
        return LibraryCrs(definition, CorrectionsDatum.ETRS89)
    }

    @Test
    fun `S057 RF-4 una capa con un EPSG de la biblioteca se reconoce y se propone con su cambio de datum`() {
        val state = open("no-disponible-3857.gpkg").settled()

        val row = state.layers.single()
        row.crs shouldBe GpkgCrs.Library(3857, "WGS 84 / Pseudo-Mercator")
        row.recognized.shouldNotBeNull()
        row.effective.shouldBeNull()
        row.effectiveSource.shouldBeInstanceOf<CadSourceCrs.Library>().definition.epsg shouldBe 3857
    }

    @Test
    fun `S057 RF-16 la extension de la capa en 3857 cae donde dice pyproj y se importa tras confirmar si hace falta`() {
        val viewModel = open("no-disponible-3857.gpkg")
        viewModel.settled()
        val table = viewModel.state.value.layers.single().info.table
        viewModel.confirmLibrary(table, true) // el cambio de datum de WGS 84 a ETRS89 puede pedir confirmación

        val state = viewModel.settled()

        val extent = state.layers.single().extent.shouldNotBeNull()
        // La extensión es la de las cuatro esquinas del recuadro de la capa en 3857, pasadas al proyecto: sale a unos centímetros de los puntos
        // (440100…440340, 4474090…4474160, lo esperado de pyproj en fixtures/formatos/biblioteca) por el giro entre los dos sistemas.
        extent.minEasting shouldBe (440_100.0 plusOrMinus 1.0)
        extent.maxEasting shouldBe (440_340.0 plusOrMinus 1.0)
        extent.minNorthing shouldBe (4_474_090.0 plusOrMinus 1.0)
        extent.maxNorthing shouldBe (4_474_160.0 plusOrMinus 1.0)
        state.canImport shouldBe true
    }

    @Test
    fun `S057 RF-6 una capa sin sistema se puede importar eligiendo uno de la biblioteca`() {
        val viewModel = open("sin-sistema.gpkg")
        val first = viewModel.settled().layers.first()
        viewModel.setMarked(first.info.table, true)
        viewModel.state.value.layers.filter { it.info.table != first.info.table }.forEach { viewModel.setMarked(it.info.table, false) }
        viewModel.settled().canImport shouldBe false

        viewModel.setLibrary(first.info.table, library(2154, GeoPoint(48.0, 2.0)))

        val row = viewModel.state.value.layer(first.info.table)
        row.effectiveSource.shouldBeInstanceOf<CadSourceCrs.Library>().definition.epsg shouldBe 2154
        row.chosen.shouldBeNull()
        row.chosenLibrary.shouldNotBeNull()
    }

    @Test
    fun `S057 RF-6 elegir un SC de España despues de uno de la biblioteca quita el de la biblioteca`() {
        val viewModel = open("sin-sistema.gpkg")
        val table = viewModel.settled().layers.first().info.table
        viewModel.setLibrary(table, library(2154, GeoPoint(48.0, 2.0)))

        viewModel.setCrs(table, ProjectCrs.ETRS89_UTM30)

        val row = viewModel.state.value.layer(table)
        row.chosenLibrary.shouldBeNull()
        row.effectiveSource shouldBe CadSourceCrs.Utm(GeodeticDatum.ETRS89, 30)
    }

    @Test
    fun `S057 RF-9 un cambio de datum de mas de 1 m bloquea la importacion hasta confirmarlo`() {
        val viewModel = open("sin-sistema.gpkg")
        val first = viewModel.settled().layers.first()
        viewModel.state.value.layers.filter { it.info.table != first.info.table }.forEach { viewModel.setMarked(it.info.table, false) }

        viewModel.setLibrary(first.info.table, library(3844, GeoPoint(44.43, 26.1)))

        viewModel.state.value.layer(first.info.table).libraryReady shouldBe false
        viewModel.state.value.canImport shouldBe false
        viewModel.confirmLibrary(first.info.table, true)
        viewModel.state.value.layer(first.info.table).libraryReady shouldBe true
    }

    @Test
    fun `S057 RF-27 una capa de Espana sigue como siempre y sin SC de la biblioteca`() {
        val state = open("puntos-25830.gpkg").settled()

        val row = state.layers.single()
        row.recognized.shouldBeNull()
        row.effectiveSource shouldBe CadSourceCrs.Utm(GeodeticDatum.ETRS89, 30)
        state.canImport shouldBe true
    }
}
