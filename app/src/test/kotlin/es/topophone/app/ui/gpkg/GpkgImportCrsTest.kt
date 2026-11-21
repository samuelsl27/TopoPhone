package es.topophone.app.ui.gpkg

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.gpkg.GpkgCrs
import es.topophone.core.formats.gpkg.GpkgCrsUnit
import es.topophone.core.geodesy.Utm
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.CentralPoint
import es.topophone.core.model.project.GeodeticDatum
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
import kotlin.math.hypot
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

/** Importar un GeoPackage, parte 2: sistema de cada capa, extensión, distancia y aviso de 100 km (S048/RF-8 a RF-12). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class GpkgImportCrsTest {

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
    private val e0 = 440_000.0
    private val n0 = 4_474_000.0
    private val madrid = Utm.inverse(e0, n0, 30).let { CentralPoint(it.latitudeDeg, it.longitudeDeg) }
    private val seville = CentralPoint(37.3891, -5.9845)
    private var counter = 0

    private fun newProject(center: CentralPoint?, crs: ProjectCrs = ProjectCrs.ETRS89_UTM30): String = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Proyecto ${counter++}", crs = crs, centralPoint = center))
            .shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        disposeGpkgViewModels()
        Dispatchers.resetMain()
        db.close()
    }

    private fun open(file: String, project: String = newProject(madrid)): GpkgImportViewModel {
        val viewModel = gpkgViewModel(folder.newFolder(), projects, viewports, dispatcher, cad)
        viewModel.start(project, GpkgTestFiles.source(file))
        return viewModel
    }

    private fun GpkgImportUiState.layer(table: String) = layers.first { it.info.table == table }

    @Test
    fun `S048 RF-8 propone el sistema que declara cada capa`() {
        val state = open("sistemas-mixtos.gpkg").settled()

        state.layer("utm29").effective.shouldNotBeNull().source shouldBe CadSourceCrs.Utm(GeodeticDatum.ETRS89, 29)
        state.layer("ed50_utm30").effective.shouldNotBeNull().source shouldBe CadSourceCrs.Utm(GeodeticDatum.ED50, 30)
        state.layer("canarias_utm28").effective.shouldNotBeNull().source shouldBe CadSourceCrs.Utm(GeodeticDatum.REGCAN95, 28)
        state.layer("geograficas_4258").effective.shouldNotBeNull().source shouldBe CadSourceCrs.Geographic(GeodeticDatum.ETRS89)
        state.layers.all { it.chosen == null } shouldBe true
    }

    @Test
    fun `S048 RF-10 la unidad la fija el sistema de la capa`() {
        val state = open("sistemas-mixtos.gpkg").settled()

        state.layer("utm29").effective.shouldNotBeNull().unit shouldBe GpkgCrsUnit.METRE
        state.layer("geograficas_4258").effective.shouldNotBeNull().unit shouldBe GpkgCrsUnit.DEGREE
    }

    @Test
    fun `S048 RF-8 WGS 84 se propone como ETRS89 marcado`() {
        val state = open("wgs84-4326.gpkg").settled()

        val effective = state.layers.single().effective.shouldNotBeNull()
        effective.wgs84 shouldBe true
        effective.source shouldBe CadSourceCrs.Geographic(GeodeticDatum.ETRS89)
        state.canImport shouldBe true
    }

    @Test
    fun `S048 RF-9 una capa sin sistema o con uno no disponible no se puede importar hasta elegir`() {
        val viewModel = open("sin-sistema.gpkg")
        val state = viewModel.settled()

        state.layers.map { it.effective } shouldBe listOf(null, null, null)
        state.layers.map { it.crs } shouldBe listOf(GpkgCrs.Undeclared, GpkgCrs.Undeclared, GpkgCrs.Undeclared)
        state.canImport shouldBe false

        // Sin las dos primeras marcadas basta elegir la tercera.
        viewModel.setMarked("indefinido_cartesiano", false)
        viewModel.setMarked("indefinido_geografico", false)
        viewModel.settled().canImport shouldBe false
        viewModel.setCrs("srs_inexistente", ProjectCrs.ETRS89_UTM30)
        viewModel.settled().canImport shouldBe true
    }

    @Test
    fun `S048 RF-9 un sistema que la app no ofrece se reconoce en la biblioteca y se puede sustituir por uno de Espana`() {
        val viewModel = open("no-disponible-3857.gpkg")
        // S057/RF-4: está en la biblioteca y se propone con su cambio de datum; el usuario puede elegir otro (S048/RF-9)
        viewModel.settled().layers.single().crs shouldBe GpkgCrs.Library(3857, "WGS 84 / Pseudo-Mercator")
        viewModel.state.value.layers.single().effective.shouldBeNull()

        viewModel.setCrs("puntos", ProjectCrs.ETRS89_UTM30)

        viewModel.state.value.layers.single().effective.shouldNotBeNull().source shouldBe CadSourceCrs.Utm(GeodeticDatum.ETRS89, 30)
        viewModel.settled().canImport shouldBe true
    }

    @Test
    fun `S048 RF-9 elegir otra vez el mismo sistema lo quita y vuelve al del fichero`() {
        val viewModel = open("sistemas-mixtos.gpkg")
        viewModel.settled()

        viewModel.setCrs("utm29", ProjectCrs.ETRS89_UTM31)
        viewModel.state.value.layer("utm29").effective.shouldNotBeNull().source shouldBe CadSourceCrs.Utm(GeodeticDatum.ETRS89, 31)
        viewModel.setCrs("utm29", null)

        viewModel.state.value.layer("utm29").chosen.shouldBeNull()
        viewModel.state.value.layer("utm29").effective.shouldNotBeNull().source shouldBe CadSourceCrs.Utm(GeodeticDatum.ETRS89, 29)
    }

    @Test
    fun `S048 RF-12 la extension de cada capa marcada esta en el sistema del proyecto`() {
        val state = open("puntos-25830.gpkg").settled()

        val extent = state.layers.single().extent.shouldNotBeNull()
        extent.minEasting shouldBe (440_100.0 plusOrMinus 1e-3)
        extent.maxEasting shouldBe (440_300.0 plusOrMinus 1e-3)
        extent.minNorthing shouldBe (4_474_100.0 plusOrMinus 1e-3)
        extent.maxNorthing shouldBe (4_474_180.0 plusOrMinus 1e-3)
        state.layers.single().outsideArea shouldBe false
    }

    @Test
    fun `S048 RF-12 la distancia es la del centro de la capa al centro del proyecto`() {
        val state = open("puntos-25830.gpkg").settled()

        val km = state.layers.single().distanceKm.shouldNotBeNull()
        km shouldBe (hypot(440_200.0 - e0, 4_474_140.0 - n0) / 1000.0 plusOrMinus 1e-6)
        state.farWarning shouldBe false
        state.canImport shouldBe true
    }

    @Test
    fun `S048 RF-12 las capas sin marcar no tienen extension`() {
        val viewModel = open("poligonos-25830.gpkg")
        viewModel.settled().layers.all { it.extent != null } shouldBe true

        viewModel.setMarked("manzanas", false)

        viewModel.state.value.layer("manzanas").extent.shouldBeNull()
        viewModel.state.value.layer("parcelas").extent.shouldNotBeNull()
    }

    @Test
    fun `S048 RF-12 a mas de 100 km del centro pide confirmar antes de importar`() {
        val viewModel = open("puntos-25830.gpkg", newProject(seville))
        val state = viewModel.settled()

        state.layers.single().distanceKm.shouldNotBeNull().let { (it > 100.0) shouldBe true }
        state.farWarning shouldBe true
        state.farConfirmed shouldBe false
        state.canImport shouldBe false

        viewModel.confirmFar()

        viewModel.state.value.farConfirmed shouldBe true
        viewModel.settled().canImport shouldBe true
    }

    @Test
    fun `S048 RF-12 cambiar el sistema o las capas marcadas pide confirmar de nuevo`() {
        val viewModel = open("puntos-25830.gpkg", newProject(seville))
        viewModel.settled()
        viewModel.confirmFar()

        viewModel.setCrs("vertices", ProjectCrs.ETRS89_UTM29)

        viewModel.state.value.farConfirmed shouldBe false
        viewModel.setCrs("vertices", null)
        viewModel.confirmFar()
        viewModel.setMarked("vertices", false)
        viewModel.setMarked("vertices", true)
        viewModel.state.value.farConfirmed shouldBe false
    }

    @Test
    fun `S048 RF-12 desmarcar la capa lejana quita el aviso`() {
        val viewModel = open("sistemas-mixtos.gpkg", newProject(seville))
        viewModel.settled().farWarning shouldBe true

        viewModel.layersOf().forEach { viewModel.setMarked(it, false) }

        viewModel.state.value.farWarning shouldBe false
    }

    @Test
    fun `S048 RF-12 una capa de Canarias en un proyecto de la Peninsula esta fuera del ambito`() {
        val state = open("sistemas-mixtos.gpkg").settled()

        val canarias = state.layer("canarias_utm28")
        canarias.extent.shouldBeNull()
        canarias.outsideArea shouldBe true
        canarias.distanceKm.shouldBeNull()
        state.layer("utm29").outsideArea shouldBe false
    }

    @Test
    fun `S048 RF-12 sin centro del proyecto no hay distancia ni aviso`() {
        val state = open("puntos-25830.gpkg", newProject(center = null)).settled()

        state.layers.single().extent.shouldNotBeNull()
        state.layers.single().distanceKm.shouldBeNull()
        state.farWarning shouldBe false
    }

    @Test
    fun `S048 RF-8 la extension se recalcula con el sistema elegido`() {
        val viewModel = open("sistemas-mixtos.gpkg")
        val before = viewModel.settled().layer("utm31").extent.shouldNotBeNull()

        viewModel.setCrs("utm31", ProjectCrs.ETRS89_UTM30) // las mismas cifras leídas en otro huso caen en otro sitio

        val after = viewModel.state.value.layer("utm31").extent.shouldNotBeNull()
        (after.minEasting != before.minEasting) shouldBe true
    }

    @Test
    fun `S048 RF-12 mientras se calcula las extensiones la importacion no esta disponible`() {
        val viewModel = open("puntos-25830.gpkg")
        val settled = viewModel.settled()

        settled.scanning shouldBe false
        GpkgImportUiState(stage = GpkgImportStage.CONFIG, layers = settled.layers, scanning = true).canImport shouldBe false
    }

    private fun GpkgImportViewModel.layersOf(): List<String> = state.value.layers.map { it.info.table }
}
