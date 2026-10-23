package es.topophone.app.ui.receiver

import es.topophone.app.log.RecordingLog
import es.topophone.app.receiver.MemoryTiltPreferences
import es.topophone.app.receiver.currentTilt
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.log.LogLevel
import es.topophone.core.model.point.TiltMode
import es.topophone.data.receiver.ReceiverTiltSettings
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Elegir el modo de inclinación a mano y anotarlo en el log (S016/RF-3, RF-4; S014/RF-5). */
@OptIn(ExperimentalCoroutinesApi::class)
class TiltViewModelTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rs3 = "00:11:22:33:44:55"
    private val log = RecordingLog()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private fun viewModel(preferences: MemoryTiltPreferences): Pair<TiltViewModel, MemoryTiltPreferences> {
        val tilt = currentTilt(scope, preferences, address = rs3)
        runBlocking { withTimeout(5_000) { tilt.state.first { it.key == rs3 } } }
        return TiltViewModel(tilt, log) to preferences
    }

    private fun MemoryTiltPreferences.awaitMode(mode: TiltMode) =
        runBlocking { withTimeout(5_000) { values.first { it[rs3]?.mode == mode } } }.getValue(rs3)

    @Test
    fun `S016 RF-3 elegir un modo lo guarda sin comprobar y lo anota`() {
        val (vm, preferences) = viewModel(MemoryTiltPreferences())

        vm.choose(TiltMode.APP)

        preferences.awaitMode(TiltMode.APP) shouldBe ReceiverTiltSettings(TiltMode.APP, verified = false)
        val entry = Triple(LogLevel.INFO, LogCategory.RECEIVER, "Modo de inclinación: calculada por la app (sin comprobar)")
        runBlocking { withTimeout(5_000) { while (log.entries.isEmpty()) delay(10) } }
        log.entries shouldContain entry
    }

    @Test
    fun `S016 RF-4 jalon vertical con un receptor que compensa queda en el log`() {
        val compensating = ReceiverTiltSettings(TiltMode.RECEIVER, verified = true, detected = TiltMode.RECEIVER)
        val (vm, preferences) = viewModel(MemoryTiltPreferences(mapOf(rs3 to compensating)))

        vm.choose(TiltMode.VERTICAL)

        preferences.awaitMode(TiltMode.VERTICAL).detected shouldBe TiltMode.RECEIVER
        runBlocking { withTimeout(5_000) { while (log.messages.size < 2) delay(10) } }
        log.messages shouldContain "Jalón vertical con un receptor que compensa"
    }

    @Test
    fun `S016 RF-4 sin haberlo encontrado compensando no avisa`() {
        val (vm, preferences) = viewModel(MemoryTiltPreferences())

        vm.choose(TiltMode.VERTICAL)

        preferences.awaitMode(TiltMode.VERTICAL)
        log.messages shouldNotContain "Jalón vertical con un receptor que compensa"
    }
}
