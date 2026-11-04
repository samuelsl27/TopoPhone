package es.topophone.app.ui.collector

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.log.RecordingLog
import es.topophone.app.ui.receiver.nmea
import es.topophone.app.ui.stakeout.StakeTarget
import es.topophone.core.model.codes.AttributeError
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeAttribute
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeLibrary
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.LibraryChoice
import es.topophone.core.model.codes.PointAttribute
import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.codes.SurveyCode
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.codes.DefaultLibrarySource
import es.topophone.data.codes.ProjectLibraryLinker
import es.topophone.data.codes.RoomCodeLibraryRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.CollectorPreferences
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.receiver.platform.Alerts
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.transport.StreamTransport
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
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

/** El codigo y los atributos del siguiente punto en el recolector (S024/RF-1 a RF-4, RF-8, RF-9, RF-11). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CollectorCodeViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val general = CodeLibrary(
        "default:general",
        "General",
        true,
        listOf(
            SurveyCode("g1", "ARB", "Árbol", CodeGeometry.POINT, 0x689F38),
            SurveyCode("g2", "POS", "Poste", CodeGeometry.POINT, 0xD32F2F),
            SurveyCode(
                "g3",
                "ARQ",
                "Arqueta",
                CodeGeometry.POINT,
                0x1E88E5,
                listOf(
                    CodeAttribute("Material", AttributeType.LIST, "PVC", listOf("PVC", "Hormigon")),
                    CodeAttribute("Diametro", AttributeType.NUMBER, "0.25"),
                    CodeAttribute("Unidades", AttributeType.INTEGER),
                    CodeAttribute("Nota", AttributeType.TEXT),
                ),
            ),
            SurveyCode("g4", "CAM", "Camino", CodeGeometry.LINE, 0xE64A19),
        ),
    )
    private val defaults = DefaultLibrarySource { listOf(general) }
    private val libraries = RoomCodeLibraryRepository(db, defaults)
    private val projects = RoomProjectRepository(db, libraries = ProjectLibraryLinker(db, defaults))
    private val points = RoomPointRepository(db)
    private val collectorPreferences = FakeCollectorPreferences()
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(
        sessionScope,
        NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE },
        noDataTimeoutMs = 60_000, // las épocas se mandan a mano
        watchdogPeriodMs = 1_000,
    )
    private val toApp = PipedOutputStream()
    private val log = RecordingLog()
    private val alerts = object : Alerts {
        override fun connectionLost() = Unit

        override fun correctionsLost() = Unit

        override fun pointSaved() = Unit

        override fun solutionLost() = Unit

        override fun tiltLost() = Unit
    }

    private class FakeCollectorPreferences : CollectorPreferences {
        override val settings = MutableStateFlow(CollectorSettings())

        override suspend fun save(settings: CollectorSettings) {
            this.settings.value = settings
        }
    }

    private val viewModels = mutableListOf<CollectorViewModel>()
    private val choice = CollectorCodeChoice()

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

    private val projectId: String = runBlocking {
        (
            projects.create(
                ProjectDefaults().toDraft().copy(name = "Calle Mayor 12", libraryChoice = LibraryChoice.Library("default:general")),
            ) as SaveResult.Saved
            ).id
    }

    private fun viewModel(): CollectorViewModel {
        val vm = CollectorViewModel(
            projects,
            points,
            collectorPreferences,
            session,
            alerts,
            { ProjectPositionCalculator() },
            MeasurementInProgress(),
            log,
            codeSource = RoomCollectorCodeSource(libraries, points),
            codeChoice = choice,
        )
        viewModels += vm
        vm.statsIntervalMs = 0
        vm.start(projectId)
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        await(vm) { it.loaded }
        runBlocking { withTimeout(10_000) { while (!vm.isProjectLoaded) kotlinx.coroutines.delay(10) } }
        return vm
    }

    private fun await(vm: CollectorViewModel, predicate: (CollectorUiState) -> Boolean): CollectorUiState = runBlocking {
        try {
            withTimeout(10_000) { vm.state.first(predicate) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("El recolector no llegó al estado esperado; se quedó en ${vm.state.value}; log ${log.messages}", e)
        }
    }

    private fun sendEpoch(second: Int) {
        val t = 36_930 + second
        val time = "%02d%02d%02d.00".format(t / 3_600, t / 60 % 60, t % 60)
        toApp.write(nmea("GNGGA,$time,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001").toByteArray())
        toApp.write(nmea("GNGST,$time,0.012,0.010,0.007,45.0,0.008,0.006,0.015").toByteArray())
        toApp.flush()
    }

    private fun receiving(vm: CollectorViewModel) {
        session.connect(StreamTransport("reach-RS3", PipedInputStream(toApp, 64 * 1024), ByteArrayOutputStream()))
        sendEpoch(0)
        sendEpoch(1)
        runBlocking { withTimeout(5_000) { session.state.first { it.connection is ConnectionState.Receiving && it.epoch != null } } }
        await(vm) { it.live != null && it.blocker == null }
    }

    private var next = 2

    /** Mide un punto instantáneo y espera a que se guarde con [name]. */
    private fun measure(vm: CollectorViewModel, name: String) {
        sendEpoch(next++)
        vm.measure()
        sendEpoch(next++)
        await(vm) { it.savedName == name }
    }

    private fun code(vm: CollectorViewModel, text: String): SurveyCode = vm.state.value.code.options.first { it.code == text }

    private fun used(name: String, code: String, at: Long) = Point(
        "u-$name",
        projectId,
        name,
        "",
        PointOrigin.MANUAL,
        PointCoordinates(GeodeticPosition(40.4, -3.7, null), 1.0, 2.0, null),
        null,
        null,
        false,
        false,
        null,
        at,
        null,
        code = PointCode(code, "", 0, CodeSymbol.CIRCLE),
    )

    @Test
    fun `S024 RF-1 el selector ofrece los codigos Punto por orden alfabetico y no los de linea`() {
        val vm = viewModel()
        await(vm) { it.code.totalCodes > 0 }

        vm.state.value.code.options.map { it.code } shouldBe listOf("ARB", "ARQ", "POS")
        vm.state.value.code.selected shouldBe null
        vm.state.value.code.recentCount shouldBe 0
    }

    @Test
    fun `S024 RF-1 los recientes salen primero, el mas reciente antes`() {
        runBlocking {
            points.create(used("a", "ARB", 1_000))
            points.create(used("b", "POS", 2_000))
        }
        val vm = viewModel()

        val ui = await(vm) { it.code.recentCount == 2 }.code

        ui.options.map { it.code } shouldBe listOf("POS", "ARB", "ARQ")
    }

    @Test
    fun `S024 RF-2 el buscador filtra por codigo o descripcion sin mayusculas ni acentos`() {
        val vm = viewModel()
        await(vm) { it.code.totalCodes > 0 }

        vm.setCodeQuery("ARBOL")
        await(vm) { it.code.query == "ARBOL" }.code.options.map { it.code } shouldBe listOf("ARB")
        vm.setCodeQuery("ar")
        await(vm) { it.code.options.size == 2 }.code.options.map { it.code } shouldBe listOf("ARB", "ARQ")
    }

    @Test
    fun `S024 RF-3 el codigo elegido se conserva y Sin codigo lo quita`() {
        val vm = viewModel()
        await(vm) { it.code.totalCodes > 0 }

        vm.selectCode(code(vm, "POS"))
        await(vm) { it.code.selected?.code == "POS" }
        vm.selectCode(null)

        await(vm) { it.code.selected == null }
    }

    @Test
    fun `S024 RF-4 con la descripcion vacia se propone la del codigo y una escrita no se pisa`() {
        val vm = viewModel()
        await(vm) { it.code.totalCodes > 0 }

        vm.selectCode(code(vm, "ARB"))
        await(vm) { it.description == "Árbol" }
        vm.selectCode(code(vm, "POS"))
        await(vm) { it.description == "Poste" }
        vm.setDescription("esquina norte")
        vm.selectCode(code(vm, "ARB"))

        await(vm) { it.code.selected?.code == "ARB" }.description shouldBe "esquina norte"
    }

    @Test
    fun `S024 RF-8 los atributos nacen con el valor por defecto y el numero con el signo decimal de la interfaz`() {
        val vm = viewModel()
        await(vm) { it.code.totalCodes > 0 }

        vm.selectCode(code(vm, "ARQ"))

        val fields = await(vm) { it.code.selected != null }.code.fields
        fields.map { it.name } shouldBe listOf("Material", "Diametro", "Unidades", "Nota")
        fields.map { it.text } shouldBe listOf("PVC", "0${interfaceDecimal()}25", "", "")
    }

    @Test
    fun `S024 RF-9 un valor no valido bloquea medir con su motivo y uno vacio es valido`() {
        val vm = viewModel()
        receiving(vm)
        await(vm) { it.code.totalCodes > 0 }
        vm.selectCode(code(vm, "ARQ"))
        await(vm) { it.code.selected != null && it.blocker == null }

        vm.setCodeAttribute(2, "3,5")
        val bad = await(vm) { it.blocker == MeasureBlocker.ATTRIBUTES }
        bad.code.fields[2].error shouldBe AttributeError.NOT_INTEGER

        vm.setCodeAttribute(2, "")
        await(vm) { it.blocker == null }.code.valid shouldBe true
    }

    @Test
    fun `S024 RF-11 con una medida en marcha no se cambia de codigo ni de valores`() {
        val vm = viewModel()
        receiving(vm)
        await(vm) { it.code.totalCodes > 0 }
        vm.selectCode(code(vm, "ARQ"))
        await(vm) { it.code.selected?.code == "ARQ" && it.blocker == null }

        vm.measure()
        await(vm) { it.measuring }
        vm.selectCode(code(vm, "POS"))
        vm.setCodeAttribute(3, "cambiado")

        vm.state.value.code.selected?.code shouldBe "ARQ"
        vm.state.value.code.fields[3].text shouldBe ""
    }

    private fun savedPoint(name: String) = runBlocking { points.points(projectId).first() }.single { it.name == name }

    @Test
    fun `S024 RF-10 el punto se guarda con el codigo, su color, simbolo, descripcion y atributos sin redondear`() {
        val vm = viewModel()
        receiving(vm)
        await(vm) { it.code.totalCodes > 0 }
        vm.selectCode(code(vm, "ARQ"))
        await(vm) { it.code.selected != null }
        vm.setCodeAttribute(1, "0${interfaceDecimal()}123456789012345")
        vm.setCodeAttribute(2, "7")
        vm.setCodeAttribute(3, "  junto al muro ")

        measure(vm, "1")

        val code = savedPoint("1").code.shouldNotBeNull()
        code.code shouldBe "ARQ"
        code.description shouldBe "Arqueta"
        code.color shouldBe 0x1E88E5
        code.symbol shouldBe CodeSymbol.CIRCLE
        code.attributes shouldBe listOf(
            PointAttribute("Material", AttributeType.LIST, "PVC"),
            PointAttribute("Diametro", AttributeType.NUMBER, "0.123456789012345"),
            PointAttribute("Unidades", AttributeType.INTEGER, "7"),
            PointAttribute("Nota", AttributeType.TEXT, "junto al muro"),
        )
    }

    @Test
    fun `S024 RF-3 tras guardar el codigo se conserva y los atributos vuelven a los valores por defecto`() {
        val vm = viewModel()
        receiving(vm)
        await(vm) { it.code.totalCodes > 0 }
        vm.selectCode(code(vm, "ARQ"))
        await(vm) { it.code.selected != null }
        vm.setCodeAttribute(3, "una nota")

        measure(vm, "1")

        val ui = await(vm) { it.savedName == "1" && it.code.fields.getOrNull(3)?.text == "" }.code
        ui.selected?.code shouldBe "ARQ"
        ui.fields.map { it.text } shouldBe listOf("PVC", "0${interfaceDecimal()}25", "", "")
    }

    @Test
    fun `S024 RF-10 sin codigo el punto se guarda sin codigo ni atributos`() {
        val vm = viewModel()
        receiving(vm)

        measure(vm, "1")

        savedPoint("1").code.shouldBeNull()
    }

    @Test
    fun `S024 RF-10 el punto guardado no cambia si despues se borra el codigo de la biblioteca`() {
        val vm = viewModel()
        receiving(vm)
        await(vm) { it.code.totalCodes > 0 }
        vm.selectCode(code(vm, "POS"))
        await(vm) { it.code.selected != null }
        measure(vm, "1")

        db.openHelper.writableDatabase.execSQL("DELETE FROM code_libraries")

        savedPoint("1").code.shouldNotBeNull().code shouldBe "POS"
    }

    @Test
    fun `S024 RF-20 un punto replanteado hereda por valor el codigo y los atributos del punto de diseno`() {
        val vm = viewModel()
        receiving(vm)
        measure(vm, "1")
        val design = StakeTarget(
            id = "design-1",
            name = "12",
            description = "",
            coordinates = savedPoint("1").coordinates,
            code = PointCode(
                "ZZZ",
                "Codigo de otra biblioteca",
                0x123456,
                CodeSymbol.STAR,
                listOf(PointAttribute("Nota", AttributeType.TEXT, "x")),
            ),
        )

        vm.setStakeTarget(design)
        await(vm) { it.name == "12_stkd" }
        vm.measure()
        sendEpoch(next++)
        await(vm) { it.savedName == "12_stkd" }

        val staked = savedPoint("12_stkd")
        staked.origin shouldBe PointOrigin.STAKED
        staked.code shouldBe design.code
    }

    @Test
    fun `S024 RF-10 una reproduccion en lugar del receptor guarda el codigo igual`() {
        val vm = viewModel()
        receiving(vm)
        await(vm) { it.code.totalCodes > 0 }
        vm.selectCode(code(vm, "ARB"))
        await(vm) { it.code.selected != null }

        measure(vm, "1")

        savedPoint("1").code.shouldNotBeNull().code shouldBe "ARB"
    }

    @Test
    fun `S024 RF-5 al volver del editor el recolector elige el codigo creado`() {
        val vm = viewModel()
        await(vm) { it.code.totalCodes > 0 }

        runBlocking {
            libraries.saveProjectCode(projectId, SurveyCode("", "VAL", "Valla", CodeGeometry.POINT, 0x123456, symbol = CodeSymbol.STAR))
        }
        choice.key.value = "val"

        val ui = await(vm) { it.code.selected?.code == "VAL" && it.description == "Valla" }
        ui.description shouldBe "Valla"
        ui.code.selected?.symbol shouldBe CodeSymbol.STAR
        ui.code.ownIds.size shouldBe 1
        choice.key.value shouldBe null
    }

    @Test
    fun `S024 RF-6 usar el existente elige el codigo de la biblioteca`() {
        val vm = viewModel()
        await(vm) { it.code.totalCodes > 0 }

        choice.key.value = "arb"

        await(vm) { it.code.selected?.code == "ARB" }
    }
}
