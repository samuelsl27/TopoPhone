package es.topophone.app.ui.receiver

import es.topophone.app.log.RecordingLog
import es.topophone.app.receiver.CurrentTilt
import es.topophone.app.receiver.MemoryTiltPreferences
import es.topophone.app.receiver.currentTilt
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.log.LogLevel
import es.topophone.core.model.point.TiltMode
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.WizardInconclusive
import es.topophone.core.survey.WizardState
import es.topophone.data.receiver.ReceiverTiltSettings
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.transport.StreamTransport
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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

/** El apartado "Inclinación": elegir el modo y el asistente con épocas de la sesión (S016/RF-3 a RF-10; S014/RF-5). */
@OptIn(ExperimentalCoroutinesApi::class)
class TiltViewModelTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(sessionScope, NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE })
    private val toApp = PipedOutputStream()
    private val rs3 = "00:11:22:33:44:55"
    private val log = RecordingLog()
    private lateinit var tilt: CurrentTilt

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        runCatching { toApp.close() }
        session.disconnect()
        sessionScope.cancel()
        scope.cancel()
        Dispatchers.resetMain()
    }

    private fun viewModel(preferences: MemoryTiltPreferences = MemoryTiltPreferences()): TiltViewModel {
        tilt = currentTilt(scope, preferences, address = rs3)
        runBlocking { withTimeout(5_000) { tilt.state.first { it.key == rs3 } } }
        val vm = TiltViewModel(tilt, log, session, FakePreferences())
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        return vm
    }

    private fun await(vm: TiltViewModel, predicate: (TiltUiState) -> Boolean): TiltUiState =
        runBlocking { withTimeout(10_000) { vm.state.first(predicate) } }

    private fun awaitLog(message: String) = runBlocking { withTimeout(5_000) { while (message !in log.messages) delay(10) } }

    private fun MemoryTiltPreferences.awaitMode(mode: TiltMode) =
        runBlocking { withTimeout(5_000) { values.first { it[rs3]?.mode == mode } } }.getValue(rs3)

    /** Una época a 5 Hz con la GGA siempre en el mismo sitio (receptor que compensa) y el jalón inclinado [tiltDeg]. */
    private fun send(index: Int, tiltDeg: Double, status: Int = 4) {
        val time = "1015%02d.%02d".format(30 + index / 5, index % 5 * 20)
        toApp.write(nmea("GNGGA,$time,4025.0080000,N,00342.2228000,W,$status,24,0.6,650.123,M,51.234,M,1.0,0001").toByteArray())
        toApp.write(nmea(String.format(Locale.ROOT, "GNETC,%s,30,0,123.40,200.00,%.2f,1.013,0.993,1.021", time, tiltDeg)).toByteArray())
        toApp.flush()
    }

    private fun connect() = session.connect(StreamTransport("reach-RS3", PipedInputStream(toApp, 64 * 1024), ByteArrayOutputStream()))

    /** Abre el asistente y espera a que no falte nada para empezar (S016/RF-5). */
    private fun ready(vm: TiltViewModel) {
        vm.openWizard()
        connect()
        send(0, 1.0)
        send(1, 1.0)
        await(vm) { it.wizard == WizardState.Ready(emptySet()) }
    }

    @Test
    fun `S016 RF-3 elegir un modo lo guarda sin comprobar y lo anota`() {
        val preferences = MemoryTiltPreferences()
        val vm = viewModel(preferences)

        vm.choose(TiltMode.APP)

        preferences.awaitMode(TiltMode.APP) shouldBe ReceiverTiltSettings(TiltMode.APP, verified = false)
        awaitLog("Modo de inclinación: calculada por la app (sin comprobar)")
        log.entries shouldContain Triple(LogLevel.INFO, LogCategory.RECEIVER, "Modo de inclinación: calculada por la app (sin comprobar)")
    }

    @Test
    fun `S016 RF-4 jalon vertical con un receptor que compensa queda en el log`() {
        val compensating = ReceiverTiltSettings(TiltMode.RECEIVER, verified = true, detected = TiltMode.RECEIVER)
        val preferences = MemoryTiltPreferences(mapOf(rs3 to compensating))
        val vm = viewModel(preferences)

        vm.choose(TiltMode.VERTICAL)

        preferences.awaitMode(TiltMode.VERTICAL).detected shouldBe TiltMode.RECEIVER
        awaitLog("Jalón vertical con un receptor que compensa")
    }

    @Test
    fun `S016 RF-4 sin haberlo encontrado compensando no avisa`() {
        val preferences = MemoryTiltPreferences()
        val vm = viewModel(preferences)

        vm.choose(TiltMode.VERTICAL)

        preferences.awaitMode(TiltMode.VERTICAL)
        awaitLog("Modo de inclinación: jalón vertical (sin comprobar)")
        log.messages shouldNotContain "Jalón vertical con un receptor que compensa"
    }

    @Test
    fun `S016 RF-10 la prueba propone el modo y al confirmarlo queda comprobado`() {
        val preferences = MemoryTiltPreferences()
        val vm = viewModel(preferences)
        ready(vm)

        vm.startTest()
        (2..6).forEach { send(it, 1.0) }
        (7..12).forEach { send(it, 25.0) }

        val proposed = await(vm) { it.wizard is WizardState.Proposed }.wizard.shouldBeInstanceOf<WizardState.Proposed>()
        proposed.mode shouldBe TiltMode.RECEIVER
        vm.confirm()

        preferences.awaitMode(TiltMode.RECEIVER) shouldBe
            ReceiverTiltSettings(TiltMode.RECEIVER, verified = true, detected = TiltMode.RECEIVER)
        awaitLog("Modo de inclinación: compensada por el receptor (comprobado)")
        await(vm) { it.wizard == null }
        tilt.state.value.wizardOpen shouldBe false
    }

    @Test
    fun `S016 RF-9 sin FIX la prueba no es concluyente y se repite`() {
        val vm = viewModel()
        ready(vm)

        vm.startTest()
        send(2, 1.0, status = 5)
        send(3, 1.0)

        await(vm) { it.wizard == WizardState.Inconclusive(WizardInconclusive.LOST) }
        awaitLog("Asistente de la inclinación no concluyente: se ha perdido el FIX o la inclinación")
        vm.repeatTest()
        await(vm) { it.wizard is WizardState.Ready }
    }

    @Test
    fun `S016 RF-28 abrir el asistente lo marca abierto y cerrarlo no`() {
        val vm = viewModel()
        val before = tilt.state.value.revision

        vm.openWizard()
        runBlocking { withTimeout(5_000) { tilt.state.first { it.wizardOpen } } }.revision shouldBe before + 1
        vm.closeWizard()

        runBlocking { withTimeout(5_000) { tilt.state.first { !it.wizardOpen } } }
        await(vm) { it.wizard == null }
    }
}
