package es.topophone.app.ui.equipment

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.receiver.CurrentTilt
import es.topophone.app.ui.collector.MeasurementInProgress
import es.topophone.app.ui.receiver.FakeGearRepository
import es.topophone.app.ui.receiver.fakeCurrentGear
import es.topophone.core.model.guide.EquipmentProfile
import es.topophone.core.model.receiver.ReceiverKey
import es.topophone.core.survey.AntennaCatalog
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.equipment.RoomEquipmentRepository
import es.topophone.data.receiver.ReceiverTiltSettings
import es.topophone.data.receiver.TiltPreferences
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/** «Datos del receptor»: se guarda por receptor, solo con uno de verdad, y el perfil de antena solo cambia al pulsar (S043/RF-18, RF-21, RF-22). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class EquipmentViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val repository = RoomEquipmentRepository(db)
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
    private val gearRepository = FakeGearRepository()
    private val gear = fakeCurrentGear(gearRepository)
    private val inProgress = MeasurementInProgress()

    /** Se crea con el despachador de pruebas ya puesto: el `viewModelScope` usa `Dispatchers.Main`. */
    private val vm by lazy { EquipmentViewModel(repository, tilt, gear, inProgress) }
    private val rs3 = EquipmentProfile("Reach RS3", "RS3-0042", "2026.2")

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun await(predicate: (EquipmentUiState) -> Boolean): EquipmentUiState {
        val job = CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        try {
            return runBlocking { withTimeout(10_000) { vm.state.first(predicate) } }
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `S043 RF-18 guardar escribe los datos del receptor en uso y se ven al instante`() {
        val job = CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        await { it.applies }

        vm.save("  Reach RS3 ", "RS3-0042", "2026.2")

        await { it.saved == rs3 }.message shouldBe EquipmentMessage.Saved
        runBlocking { repository.profile("AA:BB").first() } shouldBe rs3
        job.cancel()
    }

    @Test
    fun `S043 RF-18 cada receptor lleva sus datos y al cambiar de receptor se ven los suyos`() {
        val job = CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        runBlocking { repository.save(ReceiverKey.wifi("w1"), EquipmentProfile("Reach RS3", "OTRO", "2026.3")) }
        await { it.applies }
        vm.save("Reach RS3", "RS3-0042", "2026.2")
        await { it.saved == rs3 }

        address.value = ReceiverKey.wifi("w1")

        await { it.receiverKey == ReceiverKey.wifi("w1") && it.saved?.serial == "OTRO" }
        job.cancel()
    }

    @Test
    fun `S043 RF-22 con el movil, el virtual o una reproduccion no aplica y no se guarda nada`() {
        val job = CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        for (fixed in listOf(ReceiverKey.PHONE, ReceiverKey.VIRTUAL)) {
            address.value = fixed
            await { it.receiverKey == fixed }.applies shouldBe false
            vm.save("Reach RS3", "1", "2")
            runBlocking { repository.profile(fixed).first() }.shouldBeNull()
        }
        address.value = "AA:BB"
        replaying.value = true
        await { it.receiverKey == TiltPreferences.REPLAY }.applies shouldBe false
        vm.save("Reach RS3", "1", "2")
        runBlocking { repository.profile(TiltPreferences.REPLAY).first() }.shouldBeNull()
        job.cancel()
    }

    @Test
    fun `S043 RF-21 elegir un modelo del catalogo lo propone sin cambiar el perfil del receptor`() {
        val job = CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        await { it.applies }
        val before = gear.state.value.profile.ref

        vm.pick("catalog:RS4") shouldBe "Reach RS4/RS4 Pro"

        await { it.picked?.ref == "catalog:RS4" }.currentProfile?.ref shouldBe before
        gear.state.value.profile.ref shouldBe before
        job.cancel()
    }

    @Test
    fun `S043 RF-21 usar el perfil propuesto cambia el del receptor y quita la propuesta`() {
        val job = CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        await { it.applies }
        vm.pick("catalog:RS4")
        await { it.picked != null }

        vm.useProposedProfile()

        await { it.message is EquipmentMessage.ProfileUsed }.picked.shouldBeNull()
        val changed = runBlocking { withTimeout(10_000) { gear.state.first { it.profile.ref == "catalog:RS4" } } }
        changed.profile.name shouldBe AntennaCatalog.byRef("catalog:RS4")!!.name
        job.cancel()
    }

    @Test
    fun `S043 RF-21 con una medida en curso el perfil de antena no cambia y se avisa`() {
        val job = CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        await { it.applies }
        vm.pick("catalog:RS4")
        await { it.picked != null }
        inProgress.set(true)

        vm.useProposedProfile()

        await { it.message == EquipmentMessage.Locked }.currentProfile?.ref shouldBe gear.state.value.profile.ref
        gear.state.value.profile.ref shouldBe AntennaCatalog.RS3.ref
        job.cancel()
    }
}
