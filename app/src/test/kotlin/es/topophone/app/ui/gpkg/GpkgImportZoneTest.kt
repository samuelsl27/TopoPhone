package es.topophone.app.ui.gpkg

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.geodesy.Utm
import es.topophone.core.model.project.CentralPoint
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.survey.CentralPointRejection
import es.topophone.core.survey.CentralPointResult
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.map.RoomMapViewportRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.hypot
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Importar un GeoPackage, parte 3: la zona de importación, el recuento, el límite y el punto central (S048/RF-13, RF-14, RF-16). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class GpkgImportZoneTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val viewports = RoomMapViewportRepository(db)
    private val dispatcher = UnconfinedTestDispatcher()
    private val e0 = 440_000.0
    private val n0 = 4_474_000.0
    private val madrid = Utm.inverse(e0, n0, 30).let { CentralPoint(it.latitudeDeg, it.longitudeDeg) }
    private var counter = 0

    private fun newProject(center: CentralPoint?): String = runBlocking {
        projects.create(
            ProjectDefaults().toDraft().copy(name = "Proyecto ${counter++}", crs = ProjectCrs.ETRS89_UTM30, centralPoint = center),
        )
            .shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun open(file: String, project: String = newProject(madrid), limit: Int? = null): GpkgImportViewModel {
        val viewModel = gpkgViewModel(folder.newFolder(), projects, viewports, dispatcher)
        limit?.let { viewModel.maxObjects = it }
        viewModel.start(project, GpkgTestFiles.source(file))
        return viewModel
    }

    private val expected = GpkgTestFiles.expected("esperado.csv")

    /** Los objetos de la tabla con algún vértice a [radius] o menos del centro del proyecto, según pyproj. */
    private fun expectedInZone(table: String, radius: Double): Int = expected
        .filter { it["tabla"] == table && it["estado"] == "OK" }
        .groupBy { it.getValue("fid") }
        .count { (_, rows) ->
            rows.any { row ->
                row.getValue("vertices_proyecto").split(';').any { v ->
                    val c = v.split(' ')
                    hypot(c[0].toDouble() - e0, c[1].toDouble() - n0) <= radius
                }
            }
        }

    @Test
    fun `S048 RF-13 la zona propuesta es un radio de 5 km alrededor del centro`() {
        val state = open("puntos-25830.gpkg").settled()

        state.areaAll shouldBe false
        state.radiusM shouldBe 5_000.0
        state.hasCenter shouldBe true
        state.zone.objects shouldBe 5
        state.zone.exceeded shouldBe false
    }

    @Test
    fun `S048 RF-13 el recuento sigue al radio y coincide con pyproj`() {
        val viewModel = open("puntos-25830.gpkg")
        viewModel.settled()

        for (radius in listOf(100.0, 150.0, 250.0, 400.0, 1_000.0)) {
            viewModel.setRadius(radius)
            viewModel.settled().zone.objects shouldBe expectedInZone("vertices", radius)
        }
    }

    @Test
    fun `S048 RF-13 el radio va de 100 m a 100 km`() {
        val viewModel = open("puntos-25830.gpkg")
        viewModel.settled()

        viewModel.setRadius(5.0)
        viewModel.state.value.radiusM shouldBe 100.0
        viewModel.setRadius(5_000_000.0)
        viewModel.state.value.radiusM shouldBe 100_000.0
        viewModel.setRadius(12_345.0)
        viewModel.state.value.radiusM shouldBe 12_345.0
    }

    @Test
    fun `S048 RF-13 Todo el contenido cuenta todos los objetos sea cual sea el radio`() {
        val viewModel = open("puntos-25830.gpkg")
        viewModel.settled()
        viewModel.setRadius(100.0)
        viewModel.settled().zone.objects shouldBe expectedInZone("vertices", 100.0)

        viewModel.setAllContent(true)

        viewModel.settled().zone.objects shouldBe 5
        viewModel.state.value.areaAll shouldBe true
    }

    @Test
    fun `S048 RF-13 el recuento suma las capas marcadas y cambia al marcar o desmarcar`() {
        val viewModel = open("poligonos-25830.gpkg")
        val both = viewModel.settled().zone.objects

        viewModel.setMarked("manzanas", false)
        val one = viewModel.settled().zone.objects

        both shouldBe 4
        one shouldBe 2
        viewModel.setMarked("parcelas", false)
        viewModel.settled().zone.objects shouldBe 0
    }

    @Test
    fun `S048 RF-13 el recuento cambia con el sistema elegido de la capa`() {
        val viewModel = open("sistemas-mixtos.gpkg")
        viewModel.settled()
        viewModel.layersOf().filter { it != "utm31" }.forEach { viewModel.setMarked(it, false) }
        val before = viewModel.settled().zone.objects

        viewModel.setCrs("utm31", ProjectCrs.ETRS89_UTM30) // las mismas cifras en otro huso caen a cientos de km

        before shouldBe 3
        viewModel.settled().zone.objects shouldBe 0
    }

    @Test
    fun `S048 RF-13 cambiar el radio recuenta en menos de 2 segundos`() {
        val viewModel = open("puntos-25830.gpkg")
        viewModel.settled()
        viewModel.debounceMs = 150L // la espera real de la app

        val elapsed = measureTimeMillis {
            viewModel.setRadius(250.0)
            dispatcher.scheduler.advanceTimeBy(viewModel.debounceMs + 1) // el tiempo del dispatcher de prueba es virtual
            viewModel.settled().zone.objects shouldBe expectedInZone("vertices", 250.0)
        }

        (elapsed + viewModel.debounceMs < 2_000) shouldBe true
    }

    @Test
    fun `S048 RF-16 pasar del limite de objetos lo dice y no deja importar`() {
        val viewModel = open("puntos-25830.gpkg", limit = 2)

        val state = viewModel.settled()

        state.zone.exceeded shouldBe true
        state.canImport shouldBe false
        viewModel.setRadius(100.0)
        viewModel.settled().zone.exceeded shouldBe false
        viewModel.settled().canImport shouldBe true
    }

    @Test
    fun `S048 RF-16 con Todo el contenido tambien cuenta el limite`() {
        val viewModel = open("puntos-25830.gpkg", limit = 3)
        viewModel.settled()

        viewModel.setAllContent(true)

        viewModel.settled().zone.exceeded shouldBe true
        viewModel.setMarked("vertices", false)
        viewModel.settled().zone.exceeded shouldBe false
    }

    @Test
    fun `S048 RF-14 sin centro del proyecto y con radio hay que fijarlo o elegir Todo el contenido`() {
        val viewModel = open("puntos-25830.gpkg", newProject(center = null))

        val state = viewModel.settled()

        state.hasCenter shouldBe false
        state.needsCenter shouldBe true
        state.canImport shouldBe false
        viewModel.setAllContent(true)
        viewModel.settled().needsCenter shouldBe false
        viewModel.settled().canImport shouldBe true
    }

    @Test
    fun `S048 RF-14 fijar el punto central escrito lo guarda en el proyecto y habilita la importacion`() {
        val project = newProject(center = null)
        val viewModel = open("puntos-25830.gpkg", project)
        viewModel.settled()

        viewModel.fixCentralTyped("440290,5", "4474257") // el proyecto va en UTM: Este y Norte en metros

        val state = awaitCenter(viewModel)
        state.hasCenter shouldBe true
        state.centralError.shouldBeNull()
        state.centralPoint.shouldNotBeNull()
        runBlocking { projects.project(project) }.shouldNotBeNull().centralPoint.shouldNotBeNull()
        viewModel.settled().canImport shouldBe true
    }

    @Test
    fun `S048 RF-14 un punto central escrito mal o fuera del ambito no se fija y dice por que`() {
        val viewModel = open("puntos-25830.gpkg", newProject(center = null))
        viewModel.settled()

        viewModel.fixCentralTyped("abc", "4474257")
        viewModel.state.value.centralError shouldBe CentralPointRejection.INVALID_NUMBER

        viewModel.fixCentralTyped("440000", "3100000") // Canarias, fuera del ámbito de ETRS89 UTM 30
        viewModel.state.value.centralError shouldBe CentralPointRejection.OUTSIDE_CRS_AREA
        viewModel.state.value.hasCenter shouldBe false
    }

    @Test
    fun `S048 RF-14 sin posicion del receptor Usar mi posicion lo dice`() {
        val viewModel = open("puntos-25830.gpkg", newProject(center = null))
        viewModel.settled()

        viewModel.fixCentralFromReceiver()

        viewModel.state.value.centralError shouldBe CentralPointRejection.NO_POSITION
        viewModel.state.value.hasCenter shouldBe false
    }

    @Test
    fun `S048 RF-14 un toque del mapa da el punto si cae en el ambito y el motivo si no`() {
        val viewModel = open("puntos-25830.gpkg", newProject(center = null))
        viewModel.settled()

        viewModel.resolveCentralTap(40.4168, -3.7038).shouldBeInstanceOf<CentralPointResult.Fixed>()
        viewModel.resolveCentralTap(28.1, -15.4).shouldBeInstanceOf<CentralPointResult.Rejected>().reason shouldBe
            CentralPointRejection.OUTSIDE_CRS_AREA
    }

    @Test
    fun `S048 RF-14 el punto aceptado en el selector se guarda y recuenta`() {
        val viewModel = open("puntos-25830.gpkg", newProject(center = null))
        viewModel.settled()

        viewModel.setCentralPoint(madrid)

        val state = awaitCenter(viewModel)
        state.centralPoint shouldBe madrid
        viewModel.settled().zone.objects shouldBe 5
    }

    @Test
    fun `S048 RF-14 quitar el punto central deja al proyecto sin centro`() {
        val viewModel = open("puntos-25830.gpkg")
        viewModel.settled().hasCenter shouldBe true

        viewModel.clearCentralPoint()

        runBlocking { withTimeout(5_000) { viewModel.state.first { !it.hasCenter } } }
        viewModel.state.value.needsCenter shouldBe true
    }

    @Test
    fun `S048 RF-14 el mapa del selector empieza en el punto central o en España entera`() {
        val withPoint = open("puntos-25830.gpkg")
        withPoint.settled()
        val start = withPoint.pickerStart().shouldNotBeNull()
        start.latitudeDeg shouldBe madrid.latitudeDeg
        start.longitudeDeg shouldBe madrid.longitudeDeg
        start.zoom shouldBe 16.0

        val without = open("puntos-25830.gpkg", newProject(center = null))
        without.settled()
        without.pickerStart().shouldBeNull()
    }

    private fun GpkgImportViewModel.layersOf(): List<String> = state.value.layers.map { it.info.table }

    private fun awaitCenter(viewModel: GpkgImportViewModel): GpkgImportUiState = runBlocking {
        withTimeout(5_000) { viewModel.state.first { it.hasCenter } }
    }
}
