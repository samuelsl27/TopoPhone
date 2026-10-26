package es.topophone.app.ui.stakeout

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.log.RecordingLog
import es.topophone.app.ui.cad.DesignSelection
import es.topophone.app.ui.collector.MeasurementInProgress
import es.topophone.app.ui.receiver.FakePreferences
import es.topophone.app.ui.receiver.nmea
import es.topophone.core.model.cad.CadColor
import es.topophone.core.model.cad.CadDrawing
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadEntityRef
import es.topophone.core.model.cad.CadExtent
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.cad.DesignLayer
import es.topophone.core.model.cad.DesignLayerKind
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.LineInputError
import es.topophone.core.model.point.LineStakeoutMode
import es.topophone.core.model.point.StakeoutSettings
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.cad.RoomCadRepository
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
 * El replanteo de una línea: el modo que se recuerda, los campos con sus validaciones, los bloqueos con una medida en marcha, las
 * listas de vértices y de puntos, el cierre si se borra el dibujo y la guía que sigue a la sesión (S041/RF-1 a RF-4, RF-6 a RF-10,
 * RF-16 a RF-22, RF-24, RF-31).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class LineStakeoutViewModelTest {

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

    private val stakeoutPreferences = FakeStakeoutPreferences()
    private val viewModels = mutableListOf<LineStakeoutViewModel>()
    private val projectId: String = runBlocking {
        (projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")) as SaveResult.Saved).id
    }

    // La punta de las épocas de sendEpoch, en el SC del proyecto.
    private val draft = ProjectDefaults().toDraft()
    private val tip = calculator.fromGround(GeodeticPosition(40.416800, -3.703713, 650.123 + 51.234), draft.crs, draft.heightSystem)
    private val e0 = tip.easting!!
    private val n0 = tip.northing!!

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

    private fun viewModel(prefs: StakeoutPreferences = stakeoutPreferences): LineStakeoutViewModel {
        val vm = LineStakeoutViewModel(
            projects,
            cad,
            prefs,
            FakeCollectorPreferences(),
            FakePreferences(),
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

    private fun await(vm: LineStakeoutViewModel, predicate: (LineStakeoutUiState) -> Boolean): LineStakeoutUiState = runBlocking {
        try {
            withTimeout(10_000) { vm.state.first(predicate) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("El replanteo no llegó al estado esperado; se quedó en ${vm.state.value}", e)
        }
    }

    /** Un dibujo con la capa "Eje" en la base de datos, para que exista de verdad (S041/RF-31). */
    private var drawingInserted = false

    private fun insertDrawing(into: String = projectId) = runBlocking {
        if (drawingInserted) return@runBlocking
        drawingInserted = true
        cad.begin(CadDrawing("d1", into, "calle.dxf", 1L, CadSourceCrs.Project, LinearUnit.METRE, true, true))
        cad.addLayer(DesignLayer("l1", "d1", "Eje", DesignLayerKind.LINES, CadColor.rgb(0xFF0000)))
        cad.finish("d1", mapOf("l1" to 1), hasElevations = true, incomplete = false, extent = CadExtent(0.0, 1.0, 0.0, 1.0))
    }

    private fun selection(
        kind: CadEntityKind = CadEntityKind.POLYLINE,
        xs: DoubleArray = doubleArrayOf(e0 - 50.0, e0 + 50.0),
        ys: DoubleArray = doubleArrayOf(n0, n0),
        zs: DoubleArray? = null,
        original: BooleanArray? = null,
    ) = DesignSelection(
        ref = CadEntityRef("l1", 7L, 3),
        entity = CadEntity(kind, xs, ys, zs, null, original),
        layerName = "Eje",
        drawingName = "calle.dxf",
        hasElevation = zs != null,
    )

    private fun open(vm: LineStakeoutViewModel, selection: DesignSelection = selection()): LineStakeoutUiState {
        insertDrawing()
        vm.open(projectId, selection)
        return await(vm) { it.element != null && it.project != null }
    }

    private fun sendEpoch(second: Int) {
        val t = 36_930 + second
        val time = "%02d%02d%02d.00".format(t / 3_600, t / 60 % 60, t % 60)
        toApp.write(nmea("GNGGA,$time,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001").toByteArray())
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
    fun `S041 RF-2 y RF-4 abrir una linea da su capa, su dibujo, su tipo, su longitud y su cota`() {
        val vm = viewModel()

        val state = open(vm, selection(zs = doubleArrayOf(100.0, 110.0)))

        val element = state.element.shouldNotBeNull()
        element.layerName shouldBe "Eje"
        element.drawingName shouldBe "calle.dxf"
        element.kind shouldBe CadEntityKind.POLYLINE
        element.lengthM shouldBe (100.0 plusOrMinus 1e-6)
        element.minHeightM shouldBe 100.0
        element.maxHeightM shouldBe 110.0
        state.unsupported shouldBe false
    }

    @Test
    fun `S041 RF-2 la primera vez el modo es A la linea y los campos empiezan con 0, el inicio y el final y sin intervalo`() {
        val vm = viewModel()

        val state = open(vm)

        state.mode shouldBe LineStakeoutMode.TO_LINE
        state.offsetText.replace(",", ".") shouldBe "0.000"
        state.aText.replace(",", ".") shouldBe "0.000"
        state.bText.replace(",", ".") shouldBe "100.000"
        state.intervalText shouldBe ""
        state.series.shouldBeNull()
        state.errors shouldBe emptyMap()
    }

    @Test
    fun `S041 RF-3 el modo elegido se recuerda para todos los proyectos`() {
        val vm = viewModel()
        open(vm)

        vm.setMode(LineStakeoutMode.INTERVALS) shouldBe true

        await(vm) { it.mode == LineStakeoutMode.INTERVALS }
        stakeoutPreferences.settings.value.lineMode shouldBe LineStakeoutMode.INTERVALS
        val another = viewModel()
        open(another).mode shouldBe LineStakeoutMode.INTERVALS
    }

    @Test
    fun `S041 RF-11 un desplazamiento valido se aplica y uno que no es un numero se rechaza con su motivo y conserva el anterior`() {
        val vm = viewModel()
        open(vm)

        vm.setOffset("-2,5") shouldBe true
        vm.setOffset("abc") shouldBe true // el campo se acepta escrito, pero no se aplica

        val state = await(vm) { it.errors.containsKey(LineField.OFFSET) }
        state.errors[LineField.OFFSET] shouldBe LineInputError.NOT_A_NUMBER
        state.offsetText shouldBe "abc"
        vm.setOffset("3") shouldBe true
        await(vm) { !it.errors.containsKey(LineField.OFFSET) }.offsetText shouldBe "3"
    }

    @Test
    fun `S041 RF-21 un PK fuera de la linea, A igual a B y un intervalo no valido no se aplican y dicen por que`() {
        val vm = viewModel()
        open(vm)

        vm.setB("500")
        await(vm) { it.errors[LineField.B] == LineInputError.OUT_OF_LINE }
        vm.setB("0")
        await(vm) { it.errors[LineField.B] == LineInputError.SAME_PK }
        vm.setB("80")
        await(vm) { it.errors.isEmpty() }
        vm.setA("-3")
        await(vm) { it.errors[LineField.A] == LineInputError.OUT_OF_LINE }
        vm.setInterval("0")
        await(vm) { it.errors[LineField.INTERVAL] == LineInputError.INTERVAL_NOT_POSITIVE }.series.shouldBeNull()
        vm.setInterval("x")
        await(vm) { it.errors[LineField.INTERVAL] == LineInputError.NOT_A_NUMBER }
        vm.setInterval("10")
        val ok = await(vm) { it.series != null && it.errors[LineField.INTERVAL] == null }
        ok.series.shouldNotBeNull().size shouldBe 9 // de 0 a 80 cada 10; A sigue siendo 0 porque el -3 no se aplicó
    }

    @Test
    fun `S041 RF-16 Invertir cambia A y B entre si y la serie va de B hacia A`() {
        val vm = viewModel()
        open(vm)
        vm.setB("40")
        vm.setInterval("15")
        await(vm) { it.series != null }

        vm.invert() shouldBe true

        val state = await(vm) { it.aText.startsWith("40") }
        state.bText.startsWith("0") shouldBe true
        state.series.shouldNotBeNull().chainageOf(0) shouldBe (40.0 plusOrMinus 1e-9)
        state.series.shouldNotBeNull().chainageOf(state.series.shouldNotBeNull().size - 1) shouldBe (0.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S041 RF-16 Mi posicion pone en A el PK del punto mas cercano a la punta y sin guia no hace nada`() {
        val vm = viewModel()
        open(vm)
        vm.useMyPosition() shouldBe false

        connect()
        await(vm) { it.guidance is GuidanceState.On }
        vm.useMyPosition() shouldBe true

        val state = await(vm) { it.aText.replace(",", ".").startsWith("50.") || it.aText.replace(",", ".").startsWith("49.") }
        state.errors shouldBe emptyMap()
    }

    @Test
    fun `S041 RF-22 con una medida en marcha no se cambia ni el modo ni los campos ni el vertice ni el punto de la serie`() {
        val vm = viewModel()
        open(vm, selection(xs = doubleArrayOf(e0 - 50.0, e0, e0 + 50.0), ys = doubleArrayOf(n0, n0 + 10.0, n0)))
        inProgress.set(true)

        vm.setMode(LineStakeoutMode.INTERVALS) shouldBe false
        vm.setOffset("2") shouldBe false
        vm.setA("10") shouldBe false
        vm.setB("30") shouldBe false
        vm.setInterval("5") shouldBe false
        vm.invert() shouldBe false
        vm.next() shouldBe false
        vm.previous() shouldBe false
        vm.selectVertex(1) shouldBe false
        vm.selectSeriesPoint(1) shouldBe false
        vm.setDesignElevation("100") shouldBe false

        val state = await(vm) { it.measuring }
        state.mode shouldBe LineStakeoutMode.TO_LINE
        state.offsetText.replace(",", ".") shouldBe "0.000"
        state.vertexIndex shouldBe 0
        inProgress.set(false)
        vm.setOffset("2") shouldBe true
    }

    @Test
    fun `S041 RF-6 y RF-8 por segmentos ofrece los vertices originales con su numero y su PK y permite elegir cualquiera`() {
        val vm = viewModel()
        val state = open(vm, selection(xs = doubleArrayOf(e0 - 60.0, e0, e0 + 40.0), ys = doubleArrayOf(n0, n0, n0 + 30.0)))

        val vertices = state.vertices.shouldNotBeNull()
        vertices.entries.map { it.number } shouldBe listOf(1, 2, 3)
        vertices.entries.map { it.chainageM.toInt() } shouldBe listOf(0, 60, 110)
        vm.selectVertex(2) shouldBe true
        await(vm) { it.vertexIndex == 2 }
    }

    @Test
    fun `S041 RF-7 Siguiente y Anterior en Por segmentos no pasan de los extremos y en una cerrada vuelve al primero`() {
        val vm = viewModel()
        open(vm, selection(xs = doubleArrayOf(e0, e0 + 10.0, e0 + 10.0), ys = doubleArrayOf(n0, n0, n0 + 10.0)))
        vm.setMode(LineStakeoutMode.BY_SEGMENTS)
        await(vm) { it.mode == LineStakeoutMode.BY_SEGMENTS }

        vm.next()
        vm.next()
        vm.next() // ya es el último
        await(vm) { it.vertexIndex == 2 }
        vm.previous()
        vm.previous()
        vm.previous() // ya es el primero
        await(vm) { it.vertexIndex == 0 }

        val closed = viewModel()
        open(closed, selection(xs = doubleArrayOf(e0, e0 + 10.0, e0 + 10.0, e0), ys = doubleArrayOf(n0, n0, n0 + 10.0, n0)))
        closed.setMode(LineStakeoutMode.BY_SEGMENTS)
        await(closed) { it.mode == LineStakeoutMode.BY_SEGMENTS }
        closed.next()
        closed.next()
        await(closed) { it.vertexIndex == 2 }
        closed.next() // tras el último vértice de una cerrada vuelve al primero
        await(closed) { it.vertexIndex == 0 }
    }

    @Test
    fun `S041 RF-9 de un arco solo se ofrecen sus dos extremos`() {
        val vm = viewModel()
        val xs = DoubleArray(6) { e0 + it * 5.0 }
        val ys = DoubleArray(6) { n0 + if (it % 2 == 0) 0.0 else 1.0 }

        val state = open(vm, selection(CadEntityKind.ARC, xs, ys, original = BooleanArray(6) { it == 0 || it == 5 }))

        state.vertices.shouldNotBeNull().entries.map { it.number } shouldBe listOf(1, 2)
    }

    @Test
    fun `S041 RF-20 Intervalos da la lista de puntos con su PK y Siguiente y Anterior los recorren sin pasar de los extremos`() {
        val vm = viewModel()
        open(vm)
        vm.setMode(LineStakeoutMode.INTERVALS)
        vm.setInterval("30")
        val state = await(vm) { it.mode == LineStakeoutMode.INTERVALS && it.series != null }

        state.series.shouldNotBeNull().size shouldBe 5 // 0, 30, 60, 90 y 100
        vm.next()
        vm.next()
        await(vm) { it.seriesIndex == 2 }
        repeat(5) { vm.next() }
        await(vm) { it.seriesIndex == 4 }
        repeat(6) { vm.previous() }
        await(vm) { it.seriesIndex == 0 }
        vm.selectSeriesPoint(3)
        await(vm) { it.seriesIndex == 3 }
    }

    @Test
    fun `S041 RF-1 una linea sin dos vertices distintos no se puede replantear y lo dice`() {
        val vm = viewModel()
        insertDrawing()

        vm.open(projectId, selection(CadEntityKind.CIRCLE, doubleArrayOf(e0), doubleArrayOf(n0)))

        await(vm) { it.unsupported }.element.shouldBeNull()
    }

    @Test
    fun `S041 RF-31 si se borra el dibujo con el replanteo abierto se cierra y dice por que`() {
        val vm = viewModel()
        open(vm)

        runBlocking { cad.deleteDrawing("d1") }

        await(vm) { it.closed == LineClosedReason.DRAWING_DELETED }
        vm.setOffset("3") shouldBe false
        vm.setMode(LineStakeoutMode.ALONG) shouldBe false
    }

    @Test
    fun `S041 RF-10 con la sesion recibiendo la guia da el objetivo de la linea con su PK y su lado`() {
        val vm = viewModel()
        open(vm)
        connect()

        val state = await(vm) { (it.guidance as? GuidanceState.On)?.ui?.line != null }

        val line = state.guidance.shouldBeInstanceOf<GuidanceState.On>().ui.line.shouldNotBeNull()
        line.chainageM shouldBe (50.0 plusOrMinus 0.5) // la punta está en el centro de la línea
        line.location.lateralM shouldBe (0.0 plusOrMinus 0.1)
    }

    @Test
    fun `S041 RF-6 con Por segmentos la guia apunta al vertice elegido, que es un punto fijo`() {
        val vm = viewModel()
        open(vm)
        vm.setMode(LineStakeoutMode.BY_SEGMENTS)
        vm.selectVertex(1)
        connect()

        val state = await(vm) { it.guidance is GuidanceState.On && it.vertexIndex == 1 }

        val ui = state.guidance.shouldBeInstanceOf<GuidanceState.On>().ui
        ui.line.shouldBeNull()
        ui.guidance.distanceM shouldBe (50.0 plusOrMinus 0.5) // del centro al vértice del este
    }

    @Test
    fun `S041 RF-24 una cota escrita se aplica y una que no es un numero no cambia nada`() {
        val vm = viewModel()
        open(vm)

        vm.setDesignElevation("abc") shouldBe false
        vm.setDesignElevation("650,5") shouldBe true

        val state = await(vm) { it.elevationOverridden }
        state.designElevationM shouldBe 650.5
        vm.useLineElevation() shouldBe true
        await(vm) { !it.elevationOverridden }.designElevationM.shouldBeNull()
    }

    @Test
    fun `S041 RNF-T9 en un proyecto en pies los campos se escriben en pies y se aplican en metros`() {
        val feet = ProjectDefaults().toDraft().copy(name = "Pies", linearUnit = LinearUnit.INTERNATIONAL_FOOT)
        val feetProject = runBlocking { (projects.create(feet) as SaveResult.Saved).id }
        val vm = viewModel()
        insertDrawing(into = feetProject)
        vm.open(feetProject, selection())
        await(vm) { it.element != null && it.project?.id == feetProject }

        vm.setB("100")

        val state = await(vm) { it.errors.isEmpty() && it.bText == "100" }
        state.errors shouldBe emptyMap() // 100 ft = 30,48 m: dentro de la línea de 100 m
    }
}
