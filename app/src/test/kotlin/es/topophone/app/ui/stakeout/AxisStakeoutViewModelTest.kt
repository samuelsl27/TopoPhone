package es.topophone.app.ui.stakeout

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
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.StakeSource
import es.topophone.core.model.point.StakeoutSettings
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.AcrossMove
import es.topophone.core.survey.AxisInputError
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.alignment.AlignmentImportResult
import es.topophone.data.alignment.AlignmentImporter
import es.topophone.data.alignment.RoomAlignmentRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.CollectorPreferences
import es.topophone.data.point.StakeoutPreferences
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.receiver.platform.Alerts
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.transport.StreamTransport
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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

/**
 * El replanteo por PK y desplazamiento: los campos con sus validaciones, la serie de PK, la cota de la rasante con el espesor, el cambio
 * de eje, el cierre si se borra el eje, los bloqueos con una medida en marcha y la guía que sigue a la sesión
 * (S054/RF-1 a RF-7, RF-9, RF-11).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AxisStakeoutViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val projects = RoomProjectRepository(db)
    private val alignments = RoomAlignmentRepository(db)
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

    private val viewModels = mutableListOf<AxisStakeoutViewModel>()
    private val projectId: String = runBlocking {
        (projects.create(ProjectDefaults().toDraft().copy(name = "Obra")) as SaveResult.Saved).id
    }
    private val ids: Map<String, String> = runBlocking {
        val result = AlignmentImporter(alignments).importLandXml(
            projectId,
            ProjectCrs.ETRS89_UTM30,
            "ejes.xml",
            setOf(0, 1, 2),
            CadSourceCrs.Project,
            LinearUnit.METRE,
            { ByteArrayInputStream(AlignmentTestFiles.bytes("ejes.xml")) },
        )
        result.shouldBeInstanceOf<AlignmentImportResult.Done>().summary.imported.associate { it.name to it.id }
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        runCatching { toApp.close() }
        session.disconnect()
        sessionScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(): AxisStakeoutViewModel {
        val vm = AxisStakeoutViewModel(
            projects,
            alignments,
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
            },
            { calculator },
            inProgress,
            RecordingLog(),
        )
        viewModels += vm
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        return vm
    }

    private fun await(vm: AxisStakeoutViewModel, predicate: (AxisStakeoutUiState) -> Boolean): AxisStakeoutUiState = runBlocking {
        try {
            withTimeout(10_000) { vm.state.first(predicate) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("El replanteo no llegó al estado esperado; se quedó en ${vm.state.value}", e)
        }
    }

    private fun open(axis: String = "Eje A"): AxisStakeoutViewModel {
        val vm = viewModel()
        vm.open(projectId, ids.getValue(axis))
        await(vm) { it.loaded && it.project != null }
        return vm
    }

    private fun AxisStakeoutViewModel.begin(pk: String, offset: String = "0", thickness: String = "") {
        setStart(pk)
        setOffset(offset)
        setThickness(thickness)
        start() shouldBe true
    }

    private fun sendEpoch(second: Int, lat: String = "4025.0080000", lon: String = "00342.2228000") {
        val t = 36_930 + second
        val time = "%02d%02d%02d.00".format(t / 3_600, t / 60 % 60, t % 60)
        toApp.write(nmea("GNGGA,$time,$lat,N,$lon,W,4,24,0.6,650.123,M,51.234,M,1.0,0001").toByteArray())
        toApp.write(nmea("GNGST,$time,0.012,0.010,0.007,45.0,0.008,0.006,0.015").toByteArray())
        toApp.flush()
    }

    private fun connect() {
        session.connect(StreamTransport("reach-RS3", PipedInputStream(toApp, 64 * 1024), ByteArrayOutputStream()))
        sendEpoch(0)
        sendEpoch(1)
        runBlocking { withTimeout(5_000) { session.state.first { it.connection is ConnectionState.Receiving && it.epoch != null } } }
    }

    @Test
    fun `S054 RF-1 al abrir se ve el eje con su PK inicial y final y los campos de partida`() {
        val state = open("Eje B").state.value

        state.alignmentName shouldBe "Eje B"
        state.startChainageM shouldBe 1000.5
        state.endChainageM shouldBe (1380.5 plusOrMinus 1e-9)
        state.hasProfile shouldBe true
        state.started shouldBe false
        state.offsetText shouldBe "0"
        state.thicknessText shouldBe ""
        state.choices.map { it.name } shouldBe listOf("Eje A", "Eje B", "Eje C")
    }

    @Test
    fun `S054 RF-1 sin eje elegido se abre el eje activo del proyecto`() {
        val vm = viewModel()

        vm.open(projectId, null)

        await(vm) { it.loaded }.alignmentName shouldBe "Eje A" // el primero que se importa queda activo (S049/RF-14)
    }

    @Test
    fun `S054 RF-9 un PK que no es numero o cae fuera del eje no abre el replanteo y dice el PK inicial y el final`() {
        val vm = open()
        vm.setStart("abc")
        vm.start() shouldBe false
        await(vm) { it.errors[AxisField.START] == AxisInputError.NOT_A_NUMBER }.started shouldBe false

        vm.setStart("421")
        vm.start() shouldBe false
        val state = await(vm) { it.errors[AxisField.START] == AxisInputError.OUT_OF_AXIS }

        state.started shouldBe false
        state.startChainageM shouldBe 0.0
        state.endChainageM shouldBe (420.0 plusOrMinus 1e-9)
        state.target.shouldBeNull()
    }

    @Test
    fun `S054 RF-9 un desplazamiento un espesor o un intervalo no validos tampoco abren el replanteo`() {
        val vm = open()
        vm.setStart("100")

        vm.setOffset("x")
        vm.start() shouldBe false
        await(vm) { it.errors[AxisField.OFFSET] == AxisInputError.NOT_A_NUMBER }
        vm.setOffset("3,5")
        vm.setThickness("y")
        vm.start() shouldBe false
        await(vm) { it.errors[AxisField.THICKNESS] == AxisInputError.NOT_A_NUMBER && AxisField.OFFSET !in it.errors }
        vm.setThickness("")
        vm.setInterval("0")
        vm.start() shouldBe false
        await(vm) { it.errors[AxisField.INTERVAL] == AxisInputError.INTERVAL_NOT_POSITIVE }
        vm.setInterval("")
        vm.setEnd("999")
        vm.start() shouldBe false
        await(vm) { it.errors[AxisField.END] == AxisInputError.OUT_OF_AXIS }.started shouldBe false
    }

    @Test
    fun `S054 RF-1 y RF-7 con un PK valido se abre el replanteo con el punto de diseno y la cota de la rasante mas el espesor`() {
        val vm = open()
        vm.begin("150", offset = "3,5", thickness = "0,1")

        val state = await(vm) { it.started && it.target != null }

        val target = state.target.shouldNotBeNull()
        target.chainageM shouldBe 150.0
        target.offsetM shouldBe 3.5
        target.thicknessM shouldBe (0.1 plusOrMinus 1e-12)
        // En el PK 150 el eje A tiene un acuerdo parabólico (Kv 3000): la rasante pasa 0,4167 m bajo el vértice (esperado-puntos.csv de tools).
        target.profileElevationM.shouldNotBeNull() shouldBe (102.58333333333333 plusOrMinus 1e-9)
        target.elevationM.shouldNotBeNull() shouldBe (102.68333333333334 plusOrMinus 1e-9)
        state.chainageText.replace(",", ".") shouldBe "0+150.00"
        state.stakeTarget.shouldNotBeNull().source shouldBe StakeSource.Alignment("Eje A")
        state.saveInfo.shouldNotBeNull().elevationM.shouldNotBeNull() shouldBe (102.68333333333334 plusOrMinus 1e-9)
    }

    @Test
    fun `S054 RF-7 un eje sin rasante se replantea solo en planta`() {
        val vm = open("Eje C")
        vm.begin("100", thickness = "0,2")

        val state = await(vm) { it.started && it.target != null }

        state.hasProfile shouldBe false
        state.target.shouldNotBeNull().elevationM.shouldBeNull()
        state.saveInfo.shouldNotBeNull().elevationM.shouldBeNull()
    }

    @Test
    fun `S054 RF-2 sin intervalo se replantea solo el PK inicial y siguiente no se mueve`() {
        val vm = open()
        vm.begin("100")

        val state = await(vm) { it.started && it.series != null }

        state.series.shouldNotBeNull().size shouldBe 1
        vm.next() shouldBe true
        await(vm) { it.seriesIndex == 0 }.chainageText.replace(",", ".") shouldBe "0+100.00"
    }

    @Test
    fun `S054 RF-3 con intervalo se avanza y se retrocede de PK sin salirse del eje ni del PK final`() {
        val vm = open()
        vm.setEnd("250")
        vm.setInterval("100")
        vm.begin("0")

        val series = await(vm) { it.started && it.series != null }.series.shouldNotBeNull()
        series.size shouldBe 4 // 0, 100, 200 y 250

        vm.next()
        await(vm) { it.seriesIndex == 1 }.target.shouldNotBeNull().chainageM shouldBe 100.0
        vm.next()
        vm.next()
        await(vm) { it.seriesIndex == 3 }.target.shouldNotBeNull().chainageM shouldBe 250.0
        vm.next()
        await(vm) { it.seriesIndex == 3 }
        vm.previous()
        await(vm) { it.seriesIndex == 2 }.target.shouldNotBeNull().chainageM shouldBe 200.0
    }

    @Test
    fun `S054 RF-4 con la casilla de puntos singulares la serie incluye los del eje por orden`() {
        val vm = open()
        vm.setEnd("420")
        vm.setInterval("200")
        vm.setSingular(true)
        vm.begin("0")

        val series = await(vm) { it.started && it.series != null }.series.shouldNotBeNull()

        (0 until series.size).map { series.chainageOf(it) } shouldBe
            listOf(0.0, 120.0, 150.0, 180.0, 200.0, 280.0, 300.0, 340.0, 400.0, 420.0)
    }

    @Test
    fun `S054 RF-5 se puede cambiar a mano el PK el desplazamiento y el espesor y un valor no valido conserva el anterior`() {
        val vm = open()
        vm.begin("100", offset = "2")
        await(vm) { it.target != null }

        vm.setChainage("0+200,5") shouldBe true
        await(vm) { it.target?.chainageM == 200.5 }
        vm.setOffset("-4") shouldBe true
        await(vm) { it.target?.offsetM == -4.0 }
        vm.setThickness("0,3") shouldBe true
        await(vm) { it.target?.thicknessM?.let { t -> Math.abs(t - 0.3) < 1e-12 } == true }
        vm.setChainage("999") shouldBe true

        val state = await(vm) { it.errors[AxisField.CHAINAGE] == AxisInputError.OUT_OF_AXIS }
        state.chainageText shouldBe "999"
        state.target.shouldNotBeNull().chainageM shouldBe 200.5 // lo aplicado sigue siendo el valor anterior
    }

    @Test
    fun `S054 RF-5 siguiente despues de cambiar el PK a mano continua desde la serie y reescribe el PK`() {
        val vm = open()
        vm.setEnd("300")
        vm.setInterval("100")
        vm.begin("0")
        await(vm) { it.series != null }
        vm.setChainage("37")
        await(vm) { it.target?.chainageM == 37.0 }

        vm.next()

        val state = await(vm) { it.seriesIndex == 1 }
        state.target.shouldNotBeNull().chainageM shouldBe 100.0
        state.chainageText.replace(",", ".") shouldBe "0+100.00"
    }

    @Test
    fun `S054 RF-11 cambiar de eje cambia el eje activo del proyecto y conserva lo escrito`() {
        val vm = open("Eje A")
        vm.begin("100", offset = "3,5", thickness = "0,1")
        await(vm) { it.target != null }

        vm.setAlignment(ids.getValue("Eje B")) shouldBe true

        val state = await(vm) { it.alignmentName == "Eje B" }
        state.errors[AxisField.CHAINAGE] shouldBe AxisInputError.OUT_OF_AXIS // el PK 100 no cabe en el eje B (1000,5 a 1380,5)
        state.target.shouldBeNull()
        state.offsetText shouldBe "3,5"
        state.thicknessText shouldBe "0,1"
        runBlocking { alignments.activeAlignmentId(projectId).first() } shouldBe ids.getValue("Eje B")
        vm.setChainage("1100") shouldBe true
        await(vm) { it.target != null }.errors shouldBe emptyMap()
    }

    @Test
    fun `S054 RF-11 el eje que se borra con el replanteo abierto lo cierra con su motivo`() {
        val vm = open("Eje C")
        vm.begin("100")
        await(vm) { it.target != null }

        runBlocking { alignments.delete(ids.getValue("Eje C")) }

        await(vm) { it.closed == AxisClosedReason.ALIGNMENT_DELETED }.target.shouldBeNull()
    }

    @Test
    fun `S054 RF-5 con una medida en marcha no se cambia nada`() {
        val vm = open()
        vm.begin("100")
        await(vm) { it.target != null }
        inProgress.set(true)

        vm.setChainage("120") shouldBe false
        vm.setOffset("1") shouldBe false
        vm.setThickness("1") shouldBe false
        vm.next() shouldBe false
        vm.setAlignment(ids.getValue("Eje B")) shouldBe false

        await(vm) { it.measuring }.target.shouldNotBeNull().chainageM shouldBe 100.0
    }

    @Test
    fun `S054 RF-6 la guia sigue a la sesion y da lo que hay que moverse a lo largo y a traves del eje`() {
        val vm = open("Eje A")
        vm.begin("150", offset = "3,5", thickness = "0,1")
        await(vm) { it.target != null }
        val replay = replayRows()
        connect()
        var checked = 0
        // La reproducción de tools recorre el eje A de 10 en 10 m; se envían épocas de tres PK distintos (antes, sobre y después del objetivo).
        for (row in listOf(replay[12], replay[15], replay[19])) {
            sendEpoch(10 + checked, row.lat, row.lon)
            val state = await(vm) { s -> s.axisGuidance != null && Math.abs(s.axisGuidance.alongM - row.ahead) < 0.01 }

            val guidance = state.axisGuidance.shouldNotBeNull()
            guidance.alongM shouldBe (row.ahead plusOrMinus 1e-3)
            guidance.acrossM shouldBe (row.right plusOrMinus 1e-3)
            guidance.across shouldBe when (row.side) {
                "dentro" -> AcrossMove.INWARD
                "fuera" -> AcrossMove.OUTWARD
                else -> AcrossMove.NONE
            }
            state.noProjection shouldBe false
            checked++
        }
        checked shouldBe 3
    }

    @Test
    fun `S054 RF-8 una punta antes del inicio del eje no tiene proyeccion y no da guia a lo largo`() {
        val vm = open("Eje C") // empieza en el Este 441000; la punta de las épocas está en el 440290
        vm.begin("100")
        await(vm) { it.target != null }
        connect()

        val state = await(vm) { it.noProjection }

        state.axisGuidance.shouldBeNull()
        state.guidance.shouldBeInstanceOf<GuidanceState.On>() // la guía sigue: se muestran el Norte y el Este
    }

    private class ReplayRow(val lat: String, val lon: String, val ahead: Double, val right: Double, val side: String)

    /** Las épocas de `fixtures/ejes/replanteo/reproduccion-eje-a.csv` con la latitud y la longitud en el formato de NMEA. */
    private fun replayRows(): List<ReplayRow> {
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .first { java.io.File(it, "settings.gradle.kts").exists() && java.io.File(it, "fixtures").isDirectory }
        return java.io.File(root, "fixtures/ejes/replanteo/reproduccion-eje-a.csv").readLines().drop(1).filter { it.isNotBlank() }.map {
            val c = it.split(",")
            ReplayRow(nmeaAngle(c[3].toDouble(), 2), nmeaAngle(-c[4].toDouble(), 3), c[8].toDouble(), c[9].toDouble(), c[12])
        }
    }

    /** Grados decimales positivos en `ddmm.mmmmmmm` (o `dddmm.mmmmmmm`) de NMEA. */
    private fun nmeaAngle(degrees: Double, degreeDigits: Int): String {
        val whole = degrees.toInt()
        val minutes = (degrees - whole) * 60.0
        return "%0${degreeDigits}d%010.7f".format(java.util.Locale.ROOT, whole, minutes)
    }

    @Test
    fun `S054 RF-1 cerrar el replanteo vuelve a los campos y conserva lo escrito`() {
        val vm = open()
        vm.begin("100", offset = "3")
        await(vm) { it.target != null }

        vm.close()

        val state = await(vm) { !it.started }
        state.startText shouldBe "100"
        state.offsetText shouldBe "3"
        state.target.shouldBeNull()
    }
}
