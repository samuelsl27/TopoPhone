package es.topophone.app.ui.guide

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.receiver.CurrentTilt
import es.topophone.core.model.guide.CheckStatus
import es.topophone.core.model.guide.ConfigListId
import es.topophone.core.model.guide.ConfigLists
import es.topophone.core.model.receiver.ReceiverKey
import es.topophone.data.config.RoomConfigProgressRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.receiver.ReceiverTiltSettings
import es.topophone.data.receiver.TiltPreferences
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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

/** La Guía: el progreso de cada lista es de cada receptor, sobrevive a un ViewModel nuevo y los contadores salen de ahí (S043/RF-1 a RF-4). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class GuideViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val progress = RoomConfigProgressRepository(db)
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val address = MutableStateFlow<String?>("AA:BB")
    private val replaying = MutableStateFlow(false)
    private val tilt = CurrentTilt(
        replaying,
        address,
        object : TiltPreferences {
            override fun settings(key: String): Flow<ReceiverTiltSettings> = flowOf(ReceiverTiltSettings.NONE)

            override suspend fun save(key: String, settings: ReceiverTiltSettings) = Unit
        },
        scope,
    )
    private val jobs = mutableListOf<Job>()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        jobs.forEach { it.cancel() }
        scope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    /** Se crea con el despachador de pruebas ya puesto: el `viewModelScope` usa `Dispatchers.Main`. */
    private fun viewModel(): GuideViewModel {
        val vm = GuideViewModel(progress, tilt)
        jobs += CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        return vm
    }

    private fun GuideViewModel.await(predicate: (GuideUiState) -> Boolean): GuideUiState =
        runBlocking { withTimeout(10_000) { state.first(predicate) } }

    @Test
    fun `S043 RF-1 con un receptor real hay cuatro listas y ninguna con pasos hechos`() {
        val vm = viewModel()

        val state = vm.await { it.applies }

        state.lists.map { it.id } shouldBe ConfigListId.entries
        state.done.values.all { it.isEmpty() } shouldBe true
        state.counts(ConfigLists.ntripRover).done shouldBe 0
    }

    @Test
    fun `S043 RF-3 marcar y desmarcar un paso se recuerda para ese receptor`() {
        val vm = viewModel()
        vm.await { it.applies }

        vm.setDone(ConfigListId.NTRIP_ROVER, "ntrip_rate", true)
        vm.setDone(ConfigListId.NTRIP_ROVER, "ntrip_fix", true)
        vm.await { it.done[ConfigListId.NTRIP_ROVER] == setOf("ntrip_rate", "ntrip_fix") }
        vm.setDone(ConfigListId.NTRIP_ROVER, "ntrip_rate", false)

        vm.await { it.done[ConfigListId.NTRIP_ROVER] == setOf("ntrip_fix") }.counts(ConfigLists.ntripRover).done shouldBe 1
    }

    @Test
    fun `S043 RF-3 un ViewModel nuevo, como al reabrir la pantalla, recupera el progreso`() {
        val first = viewModel()
        first.await { it.applies }
        first.setDone(ConfigListId.WIFI_ROVER, "wifi_tcp", true)
        first.await { it.done[ConfigListId.WIFI_ROVER] == setOf("wifi_tcp") }

        val second = viewModel()

        second.await { it.done[ConfigListId.WIFI_ROVER] == setOf("wifi_tcp") }
    }

    @Test
    fun `S043 RF-3 el progreso es por receptor y cambiar de receptor muestra el suyo`() {
        val vm = viewModel()
        vm.await { it.applies }
        vm.setDone(ConfigListId.NTRIP_ROVER, "ntrip_fix", true)
        vm.await { it.done[ConfigListId.NTRIP_ROVER] == setOf("ntrip_fix") }

        address.value = ReceiverKey.wifi("w1")

        vm.await { it.receiverKey == ReceiverKey.wifi("w1") && it.done[ConfigListId.NTRIP_ROVER] == emptySet<String>() }
    }

    @Test
    fun `S043 RF-3 reiniciar vacia solo esa lista`() {
        val vm = viewModel()
        vm.await { it.applies }
        vm.setDone(ConfigListId.NTRIP_ROVER, "ntrip_fix", true)
        vm.setDone(ConfigListId.WIFI_ROVER, "wifi_tcp", true)
        vm.await { it.done[ConfigListId.NTRIP_ROVER]?.isNotEmpty() == true && it.done[ConfigListId.WIFI_ROVER]?.isNotEmpty() == true }

        vm.reset(ConfigListId.NTRIP_ROVER)

        val state = vm.await { it.done[ConfigListId.NTRIP_ROVER] == emptySet<String>() }
        state.done[ConfigListId.WIFI_ROVER] shouldBe setOf("wifi_tcp")
    }

    @Test
    fun `S043 RF-1 con el movil o una reproduccion no aplica y no se guarda nada`() {
        val vm = viewModel()
        address.value = ReceiverKey.PHONE
        vm.await { it.receiverKey == ReceiverKey.PHONE }.applies shouldBe false

        vm.setDone(ConfigListId.NTRIP_ROVER, "ntrip_fix", true)

        runBlocking { progress.done(ReceiverKey.PHONE, ConfigListId.NTRIP_ROVER).first() } shouldBe emptySet()
    }

    @Test
    fun `S043 RF-4 los contadores dicen cuantos pasos estan hechos y cuantos comprobados`() {
        val list = ConfigLists.ntripRover
        val state = GuideUiState(
            applies = true,
            receiverKey = "AA:BB",
            done = mapOf(ConfigListId.NTRIP_ROVER to setOf("ntrip_output", "ntrip_rate", "ntrip_fix")),
            stepStatus = mapOf(
                "ntrip_rate" to CheckStatus.OK,
                "ntrip_messages" to CheckStatus.FAIL,
                "ntrip_fix" to CheckStatus.WARNING,
                "ntrip_time" to CheckStatus.OK,
            ),
        )

        val counts = state.counts(list)

        counts.done shouldBe 3
        counts.total shouldBe list.steps.size
        counts.checked shouldBe 2
        counts.checkable shouldBe list.steps.count { it.checkable }
    }
}
