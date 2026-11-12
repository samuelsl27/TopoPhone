package es.topophone.app.ui.stakeout

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.cad.DesignSelection
import es.topophone.core.model.cad.CadColor
import es.topophone.core.model.cad.CadDrawing
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadEntityRef
import es.topophone.core.model.cad.CadExtent
import es.topophone.core.model.cad.CadOrigin
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.cad.DesignLayer
import es.topophone.core.model.cad.DesignLayerKind
import es.topophone.core.model.cad.GpkgValue
import es.topophone.core.model.point.StakeSource
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.cad.RoomCadRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.receiver.session.ReceiverSession
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Replantear los puntos y las líneas importados de un KML o KMZ con S041, con el nombre de su objeto (S031/RF-10, RF-16). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class KmlStakeoutTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val projects = RoomProjectRepository(db)
    private val cad = RoomCadRepository(db)
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(
        sessionScope,
        NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE },
        noDataTimeoutMs = 60_000,
        watchdogPeriodMs = 1_000,
    )
    private val viewModels = mutableListOf<LineStakeoutViewModel>()
    private val projectId: String = runBlocking {
        (projects.create(ProjectDefaults().toDraft().copy(name = "Obra")) as SaveResult.Saved).id
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        session.disconnect()
        sessionScope.cancel()
        Dispatchers.resetMain()
        db.close()
    }

    private fun selection(
        name: String?,
        kind: CadEntityKind = CadEntityKind.POINT,
        ring: Boolean = false,
        xs: DoubleArray = doubleArrayOf(440_000.0),
        ys: DoubleArray = doubleArrayOf(4_474_000.0),
    ) = DesignSelection(
        CadEntityRef("l1", 1L, 0),
        CadEntity(kind, xs, ys, null, null, name = name, polygonRing = ring),
        "vertices",
        "obra.kmz",
        false,
    )

    private fun insertKmlDrawing() = runBlocking {
        val drawing = CadDrawing("d1", projectId, "obra.kmz", 1L, CadSourceCrs.Project, LinearUnit.METRE, true, false)
        cad.begin(drawing.copy(origin = CadOrigin.KML))
        cad.addLayer(DesignLayer("l1", "d1", "vertices", DesignLayerKind.LINES, CadColor.rgb(0xFF0000), sourceTable = "vertices"))
        cad.finish("d1", mapOf("l1" to 1), hasElevations = false, incomplete = false, extent = CadExtent(0.0, 1.0, 0.0, 1.0))
    }

    private fun lineViewModel(): LineStakeoutViewModel {
        val vm = LineStakeoutTestSupport.viewModel(
            projects,
            cad,
            LineStakeoutTestSupport.FakeStakeoutPreferences(),
            session,
            ProjectPositionCalculator(),
            StakeLines.NONE,
        )
        viewModels += vm
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        return vm
    }

    private fun open(vm: LineStakeoutViewModel, selection: DesignSelection): LineStakeoutUiState {
        insertKmlDrawing()
        vm.open(projectId, selection)
        return runBlocking { withTimeout(10_000) { vm.state.first { it.element != null && it.project != null } } }
    }

    @Test
    fun `S031 RF-16 se replantea con el nombre del objeto`() {
        selection("Esquina norte").stakeoutName shouldBe "Esquina norte"
    }

    @Test
    fun `S031 RF-16 sin nombre o con un nombre en blanco se replantea con el de su capa como un DXF`() {
        selection(null).stakeoutName shouldBe "vertices"
        selection("   ").stakeoutName shouldBe "vertices"
        selection("").stakeoutName shouldBe "vertices"
    }

    @Test
    fun `S031 RF-10 el objeto sin nombre llega con su numero de registro como nombre`() {
        selection("5").stakeoutName shouldBe "5"
    }

    @Test
    fun `S031 RF-16 los atributos de la seleccion no cambian el nombre`() {
        selection("Farola").copy(attributes = mapOf("nombre" to GpkgValue.Text("Otro"))).stakeoutName shouldBe "Farola"
    }

    @Test
    fun `S031 RF-16 el nombre de un contorno de poligono es el de su objeto`() {
        selection("Parcela 2", CadEntityKind.POLYLINE, ring = true).stakeoutName shouldBe "Parcela 2"
    }

    @Test
    fun `S031 RF-16 una linea de un KML se abre en el replanteo con su dibujo, su capa y su nombre`() {
        val vm = lineViewModel()
        val line = selection(
            "Calle Mayor",
            CadEntityKind.POLYLINE,
            xs = doubleArrayOf(440_000.0, 440_100.0),
            ys = doubleArrayOf(4_474_000.0, 4_474_000.0),
        )

        val state = open(vm, line)

        val element = state.element.shouldNotBeNull()
        element.drawingName shouldBe "obra.kmz"
        element.layerName shouldBe "vertices"
        element.name shouldBe "Calle Mayor"
        element.kind shouldBe CadEntityKind.POLYLINE
        element.lengthM shouldBe (100.0 plusOrMinus 1e-6)
        state.target?.name shouldBe "Calle Mayor"
        state.target?.source shouldBe StakeSource.CadElement("obra.kmz", "vertices", CadEntityKind.POLYLINE)
    }

    @Test
    fun `S031 RF-16 el contorno de un poligono se replantea como una linea cerrada con su longitud`() {
        val vm = lineViewModel()
        val ring = selection(
            "Parcela 1",
            CadEntityKind.POLYLINE,
            ring = true,
            xs = doubleArrayOf(440_000.0, 440_000.0, 440_100.0, 440_100.0, 440_000.0),
            ys = doubleArrayOf(4_474_000.0, 4_474_100.0, 4_474_100.0, 4_474_000.0, 4_474_000.0),
        )

        val element = open(vm, ring).element.shouldNotBeNull()

        element.lengthM shouldBe (400.0 plusOrMinus 1e-6)
        element.name shouldBe "Parcela 1"
        element.drawingName shouldBe "obra.kmz"
    }
}
