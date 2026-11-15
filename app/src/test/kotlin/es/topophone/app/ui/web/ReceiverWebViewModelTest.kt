package es.topophone.app.ui.web

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.receiver.CurrentTilt
import es.topophone.core.model.receiver.ReceiverKey
import es.topophone.core.model.receiver.WifiReceiverInput
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.equipment.RoomEquipmentRepository
import es.topophone.data.receiver.ReceiverTiltSettings
import es.topophone.data.receiver.RoomWifiReceiverRepository
import es.topophone.data.receiver.TiltPreferences
import io.kotest.matchers.nulls.shouldBeNull
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
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** La interfaz web: un Wi-Fi abre el puerto 80, un Bluetooth pide la IP y la recuerda, solo IP privadas y el fallo a los 10 s (S043/RF-12 a RF-16). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ReceiverWebViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val equipment = RoomEquipmentRepository(db)
    private val wifi = RoomWifiReceiverRepository(db)
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val address = MutableStateFlow<String?>("AA:BB")
    private val tilt = CurrentTilt(
        MutableStateFlow(false),
        address,
        object : TiltPreferences {
            override fun settings(key: String): Flow<ReceiverTiltSettings> = flowOf(ReceiverTiltSettings.NONE)

            override suspend fun save(key: String, settings: ReceiverTiltSettings) = Unit
        },
        scope,
    )
    private val jobs = mutableListOf<Job>()

    /** El tiempo del temporizador de 10 s es el de este planificador: los tests lo avanzan sin esperar de verdad. */
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))

    @After
    fun tearDown() {
        jobs.forEach { it.cancel() }
        scope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    /** Se crea con el despachador de pruebas ya puesto: el `viewModelScope` usa `Dispatchers.Main`. */
    private fun viewModel(): ReceiverWebViewModel {
        val vm = ReceiverWebViewModel(equipment, wifi, tilt)
        jobs += CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        return vm
    }

    private fun ReceiverWebViewModel.await(predicate: (ReceiverWebUiState) -> Boolean): ReceiverWebUiState =
        runBlocking { withTimeout(10_000) { state.first(predicate) } }

    private fun addWifi(host: String): String = runBlocking { wifi.add(WifiReceiverInput("RS3", host, 9001, null)) }

    @Test
    fun `S043 RF-12 un receptor Wi-Fi abre el puerto 80 de su direccion guardada`() {
        val id = addWifi("192.168.42.1")
        address.value = ReceiverKey.wifi(id)
        val vm = viewModel()

        val state = vm.await { it.url != null }

        state.url shouldBe "http://192.168.42.1:80/"
        state.phase shouldBe WebPhase.LOADING
    }

    @Test
    fun `S043 RF-13 un receptor Bluetooth pide la IP, la valida y la recuerda para ese receptor`() {
        val vm = viewModel()

        vm.await { it.phase == WebPhase.ASK_ADDRESS }
        vm.submitAddress(" 192.168.42.1 ")

        vm.await { it.url == "http://192.168.42.1/" }.phase shouldBe WebPhase.LOADING
        runBlocking { equipment.webAddress("AA:BB").first() } shouldBe "192.168.42.1"
    }

    @Test
    fun `S043 RF-13 la IP recordada se usa la proxima vez sin pedirla`() {
        runBlocking { equipment.saveWebAddress("AA:BB", "10.0.0.7:8080") }

        val vm = viewModel()

        vm.await { it.url != null }.url shouldBe "http://10.0.0.7:8080/"
    }

    @Test
    fun `S043 RF-13 cada receptor Bluetooth recuerda la suya`() {
        runBlocking { equipment.saveWebAddress("AA:BB", "192.168.1.5") }
        val vm = viewModel()
        vm.await { it.url == "http://192.168.1.5/" }

        address.value = "CC:DD"

        vm.await { it.receiverKey == "CC:DD" }.phase shouldBe WebPhase.ASK_ADDRESS
    }

    @Test
    fun `S043 RF-14 una IP publica o un nombre se rechazan y no se guardan`() {
        val vm = viewModel()
        vm.await { it.phase == WebPhase.ASK_ADDRESS }

        for (text in listOf("8.8.8.8", "reach.local", "127.0.0.1", "::1", "192.168.1", "172.32.0.1", "http://192.168.1.1")) {
            vm.submitAddress(text)
            vm.await { it.addressRejected }.phase shouldBe WebPhase.ASK_ADDRESS
        }
        runBlocking { equipment.webAddress("AA:BB").first() }.shouldBeNull()
    }

    @Test
    fun `S043 RF-14 con una direccion buena despues de una rechazada desaparece el rechazo`() {
        val vm = viewModel()
        vm.await { it.phase == WebPhase.ASK_ADDRESS }
        vm.submitAddress("8.8.8.8")
        vm.await { it.addressRejected }

        vm.submitAddress("169.254.10.1")

        vm.await { it.url == "http://169.254.10.1/" }.addressRejected shouldBe false
    }

    @Test
    fun `S043 RF-14 un receptor Wi-Fi cuya direccion es un nombre pide la IP`() {
        val id = addWifi("reach.local")
        address.value = ReceiverKey.wifi(id)
        val vm = viewModel()

        vm.await { it.phase == WebPhase.ASK_ADDRESS }
        vm.submitAddress("192.168.42.1")

        vm.await { it.url != null }.url shouldBe "http://192.168.42.1/"
    }

    @Test
    fun `S043 RF-16 si la pagina termina de cargar se queda cargada y el temporizador no la rompe`() {
        runBlocking { equipment.saveWebAddress("AA:BB", "192.168.1.5") }
        val vm = viewModel()
        vm.await { it.phase == WebPhase.LOADING }

        vm.onPageFinished()
        scheduler.advanceTimeBy(60_000)
        scheduler.runCurrent()

        vm.state.value.phase shouldBe WebPhase.LOADED
    }

    @Test
    fun `S043 RF-16 a los 10 segundos sin carga sale el fallo con sus causas`() {
        runBlocking { equipment.saveWebAddress("AA:BB", "192.168.1.5") }
        val vm = viewModel()
        vm.await { it.phase == WebPhase.LOADING }

        scheduler.advanceTimeBy(9_999)
        scheduler.runCurrent()
        vm.state.value.phase shouldBe WebPhase.LOADING
        scheduler.advanceTimeBy(2)
        scheduler.runCurrent()

        val state = vm.await { it.phase == WebPhase.FAILED }
        state.causes shouldBe WebFailureCause.entries
        state.url shouldBe "http://192.168.1.5/"
    }

    @Test
    fun `S043 RF-16 un error de la pagina principal tambien da el fallo`() {
        runBlocking { equipment.saveWebAddress("AA:BB", "192.168.1.5") }
        val vm = viewModel()
        vm.await { it.phase == WebPhase.LOADING }

        vm.onLoadError()

        vm.await { it.phase == WebPhase.FAILED }.causes.isNotEmpty() shouldBe true
    }

    @Test
    fun `S043 RF-16 Reintentar vuelve a cargar con un temporizador nuevo y Abrir en el navegador da la misma URL`() {
        runBlocking { equipment.saveWebAddress("AA:BB", "192.168.1.5") }
        val vm = viewModel()
        vm.await { it.phase == WebPhase.LOADING }
        scheduler.advanceTimeBy(10_001)
        scheduler.runCurrent()
        val first = vm.await { it.phase == WebPhase.FAILED }

        vm.retry()

        val retried = vm.await { it.loadId > first.loadId }
        retried.phase shouldBe WebPhase.LOADING
        vm.browserUrl() shouldBe "http://192.168.1.5/"
        scheduler.advanceTimeBy(10_001)
        scheduler.runCurrent()
        vm.await { it.phase == WebPhase.FAILED }.loadId shouldBe retried.loadId
    }

    @Test
    fun `S043 RF-22 con el movil, el virtual o una reproduccion no aplica y no se carga nada`() {
        val vm = viewModel()
        for (key in listOf(ReceiverKey.PHONE, ReceiverKey.VIRTUAL)) {
            address.value = key

            vm.await { it.receiverKey == key }.phase shouldBe WebPhase.NOT_APPLICABLE
        }
        vm.browserUrl().shouldBeNull()
    }
}
