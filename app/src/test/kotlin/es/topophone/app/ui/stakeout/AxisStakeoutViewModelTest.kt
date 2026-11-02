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
import es.topophone.core.model.alignment.section.SectionTemplate
import es.topophone.core.model.alignment.section.SuperelevationRow
import es.topophone.core.model.alignment.section.TemplateSegment
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
import es.topophone.core.survey.SectionStationOrder
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
    private val sections = es.topophone.data.section.RoomSectionRepository(db)
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
            sections,
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

    // ---- S050: replanteo por código

    private fun template(from: Double, to: Double, vararg right: String) = SectionTemplate(
        from,
        to,
        "EJE",
        listOf(TemplateSegment("BI", 3.5, 0.02, true)),
        right.map { TemplateSegment(it, 3.5, 0.02, true) },
    )

    private fun addTemplates(axis: String = "Eje A", vararg templates: SectionTemplate) = runBlocking {
        templates.forEach { sections.addTemplate(ids.getValue(axis), it) }
    }

    private fun AxisStakeoutViewModel.beginByCode(
        start: String,
        codes: List<String>,
        order: SectionStationOrder = SectionStationOrder.BY_CHAINAGE,
        end: String = "",
        interval: String = "",
        singular: Boolean = false,
    ): Boolean {
        setByCode(true)
        codes.forEach(::toggleCode)
        setOrder(order)
        setStart(start)
        setEnd(end)
        setInterval(interval)
        setSingular(singular)
        return start()
    }

    private fun AxisStakeoutViewModel.pair() = state.value.let { it.target?.chainageM?.let(Math::round) to it.code }

    private fun AxisStakeoutViewModel.walk(steps: Int): List<Pair<Long?, String?>> {
        val seen = mutableListOf(pair())
        repeat(steps) {
            next()
            seen += pair()
        }
        return seen
    }

    @Test
    fun `S050 RF-16 un eje con secciones ofrece Por codigo con sus codigos y uno sin ellas no`() {
        addTemplates("Eje A", template(0.0, 420.0, "BD"))

        val withSections = open("Eje A").state.value
        val plain = open("Eje C")
        plain.setByCode(true)

        withSections.hasSections shouldBe true
        withSections.availableCodes shouldBe listOf("BI", "EJE", "BD")
        plain.state.value.hasSections shouldBe false
        plain.state.value.byCode shouldBe false
    }

    @Test
    fun `S050 RF-16 sin ningun codigo elegido no se abre el replanteo y se dice`() {
        addTemplates("Eje A", template(0.0, 420.0, "BD"))
        val vm = open("Eje A")

        vm.beginByCode("100", emptyList()) shouldBe false

        await(vm) { it.sectionError == AxisSectionError.NoCodes }.started shouldBe false
    }

    @Test
    fun `S050 RF-18 por codigo el punto de diseno es el del codigo con su desplazamiento su cota y su pendiente`() {
        addTemplates("Eje A", template(0.0, 420.0, "BD"))
        val vm = open("Eje A")

        vm.beginByCode("0", listOf("BD")) shouldBe true

        val state = await(vm) { it.started }
        state.code shouldBe "BD"
        state.target.shouldNotBeNull().offsetM shouldBe 3.5
        state.target.shouldNotBeNull().elevationM.shouldNotBeNull() shouldBe (99.93 plusOrMinus 1e-9)
        state.sectionSlope.shouldNotBeNull() shouldBe (0.02 plusOrMinus 1e-12)
        state.codePosition shouldBe 1
    }

    @Test
    fun `S050 RF-18 el espesor se suma a la cota de la seccion`() {
        addTemplates("Eje A", template(0.0, 420.0, "BD"))
        val vm = open("Eje A")
        vm.setThickness("0,15")

        vm.beginByCode("0", listOf("BD")) shouldBe true

        await(vm) { it.started }.target.shouldNotBeNull().elevationM.shouldNotBeNull() shouldBe (99.93 + 0.15 plusOrMinus 1e-9)
    }

    @Test
    fun `S050 RF-16 de PK en PK recorre todos los codigos de cada PK y de codigo en codigo todos los PK de cada codigo`() {
        addTemplates("Eje A", template(0.0, 420.0, "BD"))
        val byChainage = open("Eje A")
        byChainage.beginByCode("100", listOf("BI", "BD"), SectionStationOrder.BY_CHAINAGE, end = "140", interval = "20") shouldBe true
        val byCode = open("Eje A")
        byCode.beginByCode("100", listOf("BI", "BD"), SectionStationOrder.BY_CODE, end = "140", interval = "20") shouldBe true

        byChainage.walk(5) shouldBe
            listOf(100L to "BI", 100L to "BD", 120L to "BI", 120L to "BD", 140L to "BI", 140L to "BD")
        byCode.walk(5) shouldBe
            listOf(100L to "BI", 120L to "BI", 140L to "BI", 100L to "BD", 120L to "BD", 140L to "BD")
    }

    @Test
    fun `S050 RF-16 anterior y siguiente no pasan de los extremos`() {
        addTemplates("Eje A", template(0.0, 420.0, "BD"))
        val vm = open("Eje A")
        vm.beginByCode("100", listOf("BD"), end = "120", interval = "20") shouldBe true

        vm.previous()
        vm.pair().first shouldBe 100L
        vm.next()
        vm.next()
        vm.next()
        vm.pair().first shouldBe 120L
    }

    @Test
    fun `S050 RF-21 un PK sin seccion no abre el replanteo y dice el PK`() {
        addTemplates("Eje A", template(0.0, 100.0, "BD"))
        val vm = open("Eje A")

        vm.beginByCode("150", listOf("BD")) shouldBe false

        await(vm) { it.sectionError != null }.sectionError shouldBe AxisSectionError.NoSection(150.0)
    }

    @Test
    fun `S050 RF-21 un codigo que no existe en la seccion de ese PK no abre el replanteo`() {
        addTemplates("Eje A", template(0.0, 100.0, "BD"), template(100.0, 200.0, "CD"))
        val vm = open("Eje A")

        vm.beginByCode("150", listOf("BD")) shouldBe false

        await(vm) { it.sectionError != null }.sectionError shouldBe AxisSectionError.NoCode("BD", 150.0)
    }

    @Test
    fun `S050 RF-21 siguiente salta los pares cuyo codigo no existe en la seccion de ese PK`() {
        addTemplates("Eje A", template(0.0, 100.0, "BD"), template(100.0, 200.0, "CD"))
        val vm = open("Eje A")
        vm.beginByCode("50", listOf("BD", "CD"), end = "150", interval = "50") shouldBe true

        // PK 50: solo BD existe; PK 100 (el límite entre las dos secciones tipo, que manda la primera): BD y CD no existe; PK 150: solo CD.
        vm.walk(6).distinct() shouldBe listOf(50L to "BD", 100L to "BD", 150L to "CD")
    }

    @Test
    fun `S050 RF-17 con la casilla de singulares entran los PK donde cambia el peralte`() {
        addTemplates("Eje A", template(0.0, 420.0, "BD"))
        runBlocking {
            sections.setSuperelevation(
                ids.getValue("Eje A"),
                listOf(SuperelevationRow(120.0, 0.02, 0.02), SuperelevationRow(180.0, -0.06, 0.06)),
            )
        }
        val vm = open("Eje A")

        vm.beginByCode("100", listOf("BD"), end = "200", singular = true) shouldBe true

        val count = await(vm) { it.started }.codeCount
        vm.walk(count - 1).map { it.first }.containsAll(listOf(120L, 180L)) shouldBe true
    }

    @Test
    fun `S050 RF-16 en el modo por codigo el desplazamiento no se escribe a mano pero el PK y el espesor si`() {
        addTemplates("Eje A", template(0.0, 420.0, "BD"))
        val vm = open("Eje A")
        vm.beginByCode("100", listOf("BD")) shouldBe true

        vm.setOffset("9") shouldBe false
        vm.setThickness("0,05") shouldBe true
        vm.setChainage("120") shouldBe true

        await(vm) { it.target?.chainageM?.let(Math::round) == 120L }.target.shouldNotBeNull().offsetM shouldBe 3.5
    }

    @Test
    fun `S050 RF-19 el objetivo por codigo lleva el codigo, el desplazamiento de la seccion y el espesor`() {
        addTemplates("Eje A", template(0.0, 420.0, "BD"))
        val vm = open("Eje A")
        vm.setThickness("0,1")
        vm.beginByCode("100", listOf("BD")) shouldBe true
        await(vm) { it.started }

        val objective = vm.objectiveProvider.current().shouldNotBeNull()

        objective.sectionCode shouldBe "BD"
        objective.lateralOffsetM shouldBe 3.5
        objective.thicknessM shouldBe 0.1
        objective.chainageM.shouldNotBeNull() shouldBe (100.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S050 RF-19 el objetivo por PK y desplazamiento no lleva codigo de seccion`() {
        val vm = open("Eje A")
        vm.begin("100", "3,5")
        await(vm) { it.started }

        vm.objectiveProvider.current().shouldNotBeNull().sectionCode.shouldBeNull()
    }

    @Test
    fun `S052 RF-10 abrir con la guia de un perfil longitudinal empieza ya la serie de PK del inicial al final cada intervalo`() {
        val vm = viewModel()

        vm.open(projectId, ids.getValue("Eje A"), ProfileGuide(0.0, 250.0, 100.0))

        val series = await(vm) { it.started && it.series != null }.series.shouldNotBeNull()
        series.size shouldBe 4 // 0, 100, 200 y 250
        vm.state.value.target.shouldNotBeNull().chainageM shouldBe 0.0
        vm.next()
        await(vm) { it.seriesIndex == 1 }.target.shouldNotBeNull().chainageM shouldBe 100.0
    }

    @Test
    fun `S052 RF-10 la guia de un perfil lleva los PK en la unidad del proyecto`() {
        val feet = runBlocking {
            val draft = ProjectDefaults().toDraft().copy(name = "Pies", linearUnit = LinearUnit.INTERNATIONAL_FOOT)
            (projects.create(draft) as SaveResult.Saved).id
        }
        val axis = runBlocking {
            AlignmentImporter(alignments).importLandXml(
                feet,
                ProjectCrs.ETRS89_UTM30,
                "ejes.xml",
                setOf(0),
                CadSourceCrs.Project,
                LinearUnit.METRE,
                { ByteArrayInputStream(AlignmentTestFiles.bytes("ejes.xml")) },
            ).shouldBeInstanceOf<AlignmentImportResult.Done>().summary.imported.single().id
        }
        val vm = viewModel()

        vm.open(feet, axis, ProfileGuide(0.0, 304.8, 30.48))

        val state = await(vm) { it.started && it.series != null }
        state.series.shouldNotBeNull().size shouldBe 11 // cada 100 ft hasta 1000 ft
    }

    @Test
    fun `S052 RF-10 una guia con el PK final fuera del eje no abre el replanteo`() {
        val vm = viewModel()

        vm.open(projectId, ids.getValue("Eje A"), ProfileGuide(0.0, 99_999.0, 100.0))
        await(vm) { it.loaded && it.project != null }

        vm.state.value.started shouldBe false
        vm.state.value.errors.keys shouldBe setOf(AxisField.END)
    }
}
