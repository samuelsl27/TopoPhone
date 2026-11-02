package es.topophone.app.ui.slope

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.log.RecordingLog
import es.topophone.app.receiver.CurrentGear
import es.topophone.app.ui.alignment.AlignmentTestFiles
import es.topophone.app.ui.collector.MeasurementInProgress
import es.topophone.app.ui.receiver.nmea
import es.topophone.app.ui.stakeout.AxisField
import es.topophone.app.ui.stakeout.GuidanceState
import es.topophone.core.model.alignment.section.CrossSection
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.alignment.section.SectionPoint
import es.topophone.core.model.alignment.slope.SlopeKind
import es.topophone.core.model.alignment.slope.SlopeRule
import es.topophone.core.model.alignment.slope.SlopeSide
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.StakeoutSettings
import es.topophone.core.model.point.TypedCoordinates
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.AxisInputError
import es.topophone.core.survey.ManualCoordinates
import es.topophone.core.survey.ManualResult
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.alignment.AlignmentImporter
import es.topophone.data.alignment.RoomAlignmentRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.CollectorPreferences
import es.topophone.data.point.StakeoutPreferences
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.data.section.RoomSectionRepository
import es.topophone.data.slope.RoomSlopeRepository
import es.topophone.data.surface.SurfaceGuides
import es.topophone.receiver.platform.Alerts
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.transport.StreamTransport
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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

/** El replanteo de taludes: la serie de PK y lados, el objetivo fijo, la estaca, los bloqueos y los motivos de apagado (S051/RF-9 a RF-18). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SlopeStakeViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val projects = RoomProjectRepository(db)
    private val alignments = RoomAlignmentRepository(db)
    private val sections = RoomSectionRepository(db)
    private val slopes = RoomSlopeRepository(db)
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(
        sessionScope,
        NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE },
        noDataTimeoutMs = 60_000,
        watchdogPeriodMs = 1_000,
    )
    private val toApp = PipedOutputStream()
    private val inProgress = MeasurementInProgress()
    private val calculator = ProjectPositionCalculator()
    private val dispatcher = UnconfinedTestDispatcher()

    private class FakeStakeoutPreferences : StakeoutPreferences {
        override val settings = MutableStateFlow(StakeoutSettings())

        override suspend fun save(settings: StakeoutSettings) {
            this.settings.value = settings.copy(toleranceM = settings.validToleranceM, toleranceVM = settings.validToleranceVM)
        }
    }

    private class FakeCollectorPreferences : CollectorPreferences {
        override val settings = MutableStateFlow(CollectorSettings())

        override suspend fun save(settings: CollectorSettings) {
            this.settings.value = settings
        }
    }

    private val found = MutableStateFlow(0)
    private val viewModels = mutableListOf<SlopeStakeViewModel>()
    private val projectId: String = runBlocking {
        (projects.create(ProjectDefaults().toDraft().copy(name = "Obra")) as SaveResult.Saved).id
    }

    /** El eje C de los ejemplos (al Este desde 441000, PK de 0 a 200). */
    private val axis: String = runBlocking {
        AlignmentImporter(alignments).importLandXml(
            projectId,
            ProjectCrs.ETRS89_UTM30,
            "ejes.xml",
            setOf(2),
            CadSourceCrs.Project,
            LinearUnit.METRE,
            { ByteArrayInputStream(AlignmentTestFiles.bytes("ejes.xml")) },
        )
        alignments.summaries(projectId).first().single().id
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        runCatching { toApp.close() }
        session.disconnect()
        sessionScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun design() = runBlocking {
        val pts = listOf(SectionPoint("BI", -3.5, 100.0), SectionPoint("EJE", 0.0, 100.5), SectionPoint("BD", 3.5, 100.0))
        sections.addSections(axis, listOf(0.0, 200.0).map { CrossSection(SectionKind.DESIGN, it, HeightKind.ABSOLUTE, pts) })
    }

    /** El terreno de los ejemplos: a la derecha sube 1 por cada 3,5 m desde el borde (desmonte); a la izquierda baja (terraplén). */
    private fun ground() = runBlocking {
        val pts = listOf(
            SectionPoint("T0", -110.0, 60.0),
            SectionPoint("T1", -3.5, 99.2),
            SectionPoint("T2", 3.5, 100.5),
            SectionPoint("T3", 8.0, 102.0),
            SectionPoint("T4", 15.0, 104.0),
            SectionPoint("T5", 30.0, 110.0),
            SectionPoint("T6", 110.0, 140.0),
        )
        sections.addSections(axis, listOf(0.0, 200.0).map { CrossSection(SectionKind.GROUND, it, HeightKind.ABSOLUTE, pts) })
    }

    private fun rules() = runBlocking {
        slopes.add(axis, SlopeRule(SlopeSide.RIGHT, 0.0, 200.0, 1.5, 2.0, "BD"))
        slopes.add(axis, SlopeRule(SlopeSide.LEFT, 0.0, 200.0, 1.5, 2.0, "BI"))
    }

    private fun viewModel(): SlopeStakeViewModel {
        val vm = SlopeStakeViewModel(
            projects,
            alignments,
            sections,
            slopes,
            SurfaceGuides.NONE,
            FakeStakeoutPreferences(),
            FakeCollectorPreferences(),
            CurrentGear.NONE,
            session,
            object : Alerts {
                override fun connectionLost() = Unit

                override fun correctionsLost() = Unit

                override fun pointSaved() = Unit

                override fun solutionLost() = Unit

                override fun tiltLost() = Unit

                override fun slopePointFound() = found.update { it + 1 }
            },
            { calculator },
            dispatcher,
            inProgress,
            RecordingLog(),
        )
        viewModels += vm
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        return vm
    }

    private fun await(vm: SlopeStakeViewModel, predicate: (SlopeStakeUiState) -> Boolean): SlopeStakeUiState = runBlocking {
        try {
            withTimeout(10_000) { vm.state.first(predicate) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("El replanteo no llegó al estado esperado; se quedó en ${vm.state.value}", e)
        }
    }

    private fun open(): SlopeStakeViewModel {
        val vm = viewModel()
        vm.open(projectId, axis)
        await(vm) { it.loaded && it.project != null }
        return vm
    }

    private fun SlopeStakeViewModel.begin(pk: String, end: String = "", interval: String = "") {
        setStart(pk)
        setEnd(end)
        setInterval(interval)
        start() shouldBe true
    }

    private fun sendEpoch(second: Int) {
        val t = 36_930 + second
        val time = "%02d%02d%02d.00".format(t / 3_600, t / 60 % 60, t % 60)
        toApp.write(nmea("GNGGA,$time,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001").toByteArray())
        toApp.write(nmea("GNGST,$time,0.012,0.010,0.007,45.0,0.008,0.006,0.015").toByteArray())
        toApp.flush()
    }

    /** Una época en el Este y el Norte del SC del proyecto, con la altitud dada: la latitud y la longitud salen de la UTM inversa. */
    private fun sendEpochAt(second: Int, easting: Double, northing: Double, altitudeM: Double) {
        val typed = TypedCoordinates(EntryForm.PROJECT_CRS, easting, northing, null, es.topophone.core.model.point.HeightKind.PROJECT)
        val ground = (
            ManualCoordinates(
                calculator,
            ).compute(typed, ProjectCrs.ETRS89_UTM30, HeightSystem.ORTHOMETRIC) as ManualResult.Computed
            )
            .coordinates.ground

        fun degrees(value: Double, digits: Int): String {
            val whole = abs(value).toInt()
            return "%0${digits}d%010.7f".format(Locale.ROOT, whole, (abs(value) - whole) * 60.0)
        }
        val t = 36_930 + second
        val time = "%02d%02d%02d.00".format(t / 3_600, t / 60 % 60, t % 60)
        val lat = degrees(ground.latitudeDeg, 2) + "," + if (ground.latitudeDeg >= 0) "N" else "S"
        val lon = degrees(ground.longitudeDeg, 3) + "," + if (ground.longitudeDeg >= 0) "E" else "W"
        val altitude = "%.4f".format(Locale.ROOT, altitudeM)
        toApp.write(nmea("GNGGA,$time,$lat,$lon,4,24,0.6,$altitude,M,51.234,M,1.0,0001").toByteArray())
        toApp.write(nmea("GNGST,$time,0.012,0.010,0.007,45.0,0.008,0.006,0.015").toByteArray())
        toApp.flush()
    }

    private fun awaitFoundAlerts(count: Int) = runBlocking { withTimeout(10_000) { found.first { it >= count } } }

    private fun connect() {
        session.connect(StreamTransport("reach-RS3", PipedInputStream(toApp, 64 * 1024), ByteArrayOutputStream()))
        sendEpoch(0)
        sendEpoch(1)
        runBlocking { withTimeout(5_000) { session.state.first { it.connection is ConnectionState.Receiving && it.epoch != null } } }
    }

    @Test
    fun `S051 RF-9 al abrir se ven el eje los lados por omision y los campos de partida`() {
        val state = open().state.value

        state.alignmentName shouldBe "Eje C"
        state.startChainageM shouldBe 0.0
        state.endChainageM shouldBe (200.0 plusOrMinus 1e-9)
        state.started shouldBe false
        state.sides shouldBe listOf(SlopeSide.LEFT, SlopeSide.RIGHT)
        state.stakeOffsetText shouldBe "0"
    }

    @Test
    fun `S051 RF-9 un PK fuera del eje o un campo mal escrito no abre el replanteo y dice el campo`() {
        design()
        rules()
        val vm = open()
        vm.setStart("abc")
        vm.start() shouldBe false
        await(vm) { it.errors[AxisField.START] == AxisInputError.NOT_A_NUMBER }

        vm.setStart("999")
        vm.setStakeOffset("x")
        vm.start() shouldBe false
        val state = await(vm) { it.errors[AxisField.START] == AxisInputError.OUT_OF_AXIS && AxisField.OFFSET in it.errors }

        state.started shouldBe false
        state.item.shouldBeNull()
    }

    @Test
    fun `S051 RF-9 la serie recorre cada PK con sus lados y siguiente y anterior no pasan de los extremos`() {
        design()
        ground()
        rules()
        val vm = open()

        vm.begin("100", end = "120", interval = "10")

        var state = await(vm) { it.outcome != null }
        state.count shouldBe 6 // 3 PK por 2 lados
        state.position shouldBe 1
        state.item!!.chainageM shouldBe 100.0
        state.item!!.side shouldBe SlopeSide.LEFT
        vm.previous() shouldBe false
        vm.next() shouldBe true
        state = await(vm) { it.position == 2 && it.outcome != null }
        state.item!!.chainageM shouldBe 100.0
        state.item!!.side shouldBe SlopeSide.RIGHT
        repeat(4) { vm.next() shouldBe true }
        await(vm) { it.position == 6 && it.outcome != null }.item!!.chainageM shouldBe 120.0
        vm.next() shouldBe false
    }

    @Test
    fun `S051 RF-9 con un solo lado la serie tiene un lugar por PK y siempre queda un lado`() {
        design()
        ground()
        rules()
        val vm = open()

        vm.toggleSide(SlopeSide.LEFT) shouldBe true
        vm.toggleSide(SlopeSide.RIGHT) shouldBe true // el ultimo no se quita
        vm.begin("100", end = "120", interval = "10")

        await(vm) { it.outcome != null }.run {
            sides shouldBe listOf(SlopeSide.RIGHT)
            count shouldBe 3
        }
    }

    @Test
    fun `S051 RF-9 el objetivo es el punto de talud teorico y fijo para el PK y el lado`() {
        design()
        ground()
        rules()
        val vm = open()
        vm.toggleSide(SlopeSide.LEFT)

        vm.begin("105")

        val objective = await(vm) { it.objective != null }.objective.shouldNotBeNull()
        objective.kind shouldBe SlopeKind.CUT
        objective.heightM shouldBe (101.0 plusOrMinus 1e-6)
        objective.slopeHeightM.shouldNotBeNull() shouldBe (1.0 plusOrMinus 1e-6)
        objective.point.easting shouldBe (441_105.0 plusOrMinus 1e-6)
        objective.point.northing shouldBe (4_474_000.0 - 5.0 plusOrMinus 1e-6)
        objective.stake.shouldBeNull()
        await(vm) { it.stakeTarget != null }
    }

    @Test
    fun `S051 RF-9 sin terreno el objetivo es el borde de explanacion`() {
        design()
        rules()
        val vm = open()
        vm.toggleSide(SlopeSide.LEFT)

        vm.begin("105")

        val state = await(vm) { it.objective != null }
        state.outcome.shouldBeInstanceOf<SlopePointOutcome.NoTerrain>()
        state.objective!!.point.northing shouldBe (4_474_000.0 - 3.5 plusOrMinus 1e-6)
        state.objective!!.heightM shouldBe 100.0
        state.objective!!.kind.shouldBeNull()
    }

    @Test
    fun `S051 RF-14 el desplazamiento de estaca lleva el objetivo hacia fuera sobre la misma seccion`() {
        design()
        ground()
        rules()
        val vm = open()
        vm.toggleSide(SlopeSide.LEFT)
        vm.setStakeOffset("1,5") shouldBe true

        vm.begin("105")

        val objective = await(vm) { it.objective?.stake != null }.objective.shouldNotBeNull()
        objective.stake!!.offsetM shouldBe (6.5 plusOrMinus 1e-6)
        objective.stake!!.distanceToCatchM shouldBe 1.5
        objective.point.northing shouldBe (4_474_000.0 - 6.5 plusOrMinus 1e-6)
        objective.slopeHeightM.shouldNotBeNull() shouldBe (1.0 plusOrMinus 1e-6)
        objective.heightM shouldBe (102.0 - 0.0 plusOrMinus 0.5) // la del terreno en la estaca (entre 102 y 104)
    }

    @Test
    fun `S051 RF-8 un PK sin talud no tiene objetivo y se dice el motivo`() {
        design()
        ground()
        runBlocking { slopes.add(axis, SlopeRule(SlopeSide.RIGHT, 0.0, 50.0, 1.5, 2.0, "BD")) }
        val vm = open()
        vm.toggleSide(SlopeSide.LEFT)

        vm.begin("105")

        val state = await(vm) { it.outcome != null }
        state.outcome.shouldBeInstanceOf<SlopePointOutcome.Unavailable>()
        state.objective.shouldBeNull()
        state.stakeTarget.shouldBeNull()
    }

    @Test
    fun `S051 RF-18 con una medida en marcha no se cambia el PK ni el lado ni la estaca`() {
        design()
        ground()
        rules()
        val vm = open()
        vm.begin("100", end = "120", interval = "10")
        await(vm) { it.outcome != null }

        inProgress.set(true)
        await(vm) { it.measuring }

        vm.next() shouldBe false
        vm.previous() shouldBe false
        vm.setStakeOffset("2") shouldBe false
        vm.start() shouldBe false
        vm.close()
        vm.state.value.position shouldBe 1
        inProgress.set(false)
        await(vm) { !it.measuring }
        vm.next() shouldBe true
    }

    @Test
    fun `S051 RF-17 una punta sin proyeccion sobre el eje apaga los valores en vivo y se dice`() {
        design()
        ground()
        rules()
        val vm = open()
        vm.toggleSide(SlopeSide.LEFT)
        vm.begin("105")
        await(vm) { it.objective != null }
        connect()

        val state = await(vm) { it.noProjection } // las épocas están en el Este 440290, antes del inicio del eje

        state.live.shouldBeNull()
        state.guidance.shouldBeInstanceOf<GuidanceState.On>()
    }

    @Test
    fun `S051 RF-9 cerrar vuelve a los campos de partida conservando lo escrito`() {
        design()
        ground()
        rules()
        val vm = open()
        vm.begin("100", end = "120", interval = "10")
        await(vm) { it.outcome != null }

        vm.close()

        val state = vm.state.value
        state.started shouldBe false
        state.startText shouldBe "100"
        state.intervalText shouldBe "10"
        state.position shouldBe 0
    }

    @Test
    fun `S051 RF-13 encontrar el punto de talud avisa una vez y vuelve a avisar al perderlo y recuperarlo`() {
        design()
        ground()
        rules()
        val vm = open()
        vm.toggleSide(SlopeSide.LEFT)
        vm.begin("105")
        val point = checkNotNull(await(vm) { it.objective != null }.objective).point
        connect()

        // Primero con una altitud cualquiera, para saber cuánto falta; la altitud llega a la punta una a una.
        sendEpochAt(2, point.easting, point.northing, 650.0)
        val dz = checkNotNull(await(vm) { it.live != null }.live).dHeightM
        val altitude = 650.0 - dz
        sendEpochAt(3, point.easting, point.northing, altitude)
        val atPoint = checkNotNull(await(vm) { it.live?.found == true }.live)
        awaitFoundAlerts(1)

        abs(atPoint.dHeightM) shouldBeLessThan 0.005
        abs(atPoint.dChainageM) shouldBeLessThan 0.005
        sendEpochAt(4, point.easting, point.northing, altitude) // sigue encontrado: no vuelve a sonar
        sendEpochAt(5, point.easting, point.northing, altitude + 1.0)
        await(vm) { it.live?.found == false && (it.live?.dHeightM ?: 0.0) > 0.5 }
        found.value shouldBe 1

        sendEpochAt(6, point.easting, point.northing, altitude)
        await(vm) { it.live?.found == true }
        awaitFoundAlerts(2)
        found.value shouldBe 2
    }

    @Test
    fun `S051 RF-13 en cota pero fuera de la linea de la seccion no se encuentra el punto`() {
        design()
        ground()
        rules()
        val vm = open()
        vm.toggleSide(SlopeSide.LEFT)
        vm.begin("105")
        val point = checkNotNull(await(vm) { it.objective != null }.objective).point
        connect()
        sendEpochAt(2, point.easting, point.northing, 650.0)
        val altitude = 650.0 - checkNotNull(await(vm) { it.live != null }.live).dHeightM

        sendEpochAt(3, point.easting + 1.0, point.northing, altitude) // un metro adelante en PK: fuera de la línea de la sección
        val state = await(vm) { (it.live?.dChainageM ?: 0.0) > 0.5 }

        checkNotNull(state.live).found shouldBe false
        found.value shouldBe 0
    }
}
