package es.topophone.app.ui.receiver

import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.collector.MeasurementInProgress
import es.topophone.core.model.point.PoleKind
import es.topophone.core.survey.AntennaCatalog
import es.topophone.core.survey.AntennaProfile
import es.topophone.core.survey.GearProblem
import es.topophone.core.survey.GearProblemField
import es.topophone.core.survey.SavedPole
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/** La pantalla "Antena y jalón": altas, ediciones, borrados, perfil y jalón activos y sus avisos (S046/RF-3 a RF-13, RF-16 a RF-19, RF-27). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class GearViewModelTest {

    private val repository = FakeGearRepository(
        listOf(SavedPole("p1", "Jalón estándar", PoleKind.VERTICAL, 1.8), SavedPole("p2", "Jalón corto", PoleKind.VERTICAL, 1.2)),
    )
    private val gear = fakeCurrentGear(repository)
    private val inProgress = MeasurementInProgress()
    private lateinit var vm: GearViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        vm = GearViewModel(repository, gear, inProgress)
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun await(predicate: (GearUiState) -> Boolean): GearUiState = runBlocking { withTimeout(5_000) { vm.state.first(predicate) } }

    @Test
    fun `S046 RF-1 y RF-9 la pantalla lista el catalogo, los perfiles propios y los jalones`() {
        val state = await { it.poles.size == 2 && it.profiles.size == AntennaCatalog.ALL.size }

        state.profiles shouldBe AntennaCatalog.ALL
        state.poles.map { it.name } shouldBe listOf("Jalón estándar", "Jalón corto")
        state.gear.profile shouldBe AntennaProfile.RS3
        state.gear.pole.shouldNotBeNull().id shouldBe "p1"
    }

    @Test
    fun `S046 RF-3 RF-5 un perfil propio se crea y se edita y uno no valido da su aviso`() {
        var done: Boolean? = null
        vm.saveProfile(null, "Mi antena", "0,12", "0.06") { done = it }
        done shouldBe true
        await { it.profiles.size == AntennaCatalog.ALL.size + 1 }.message.shouldBeNull()

        vm.saveProfile(null, "Otra", "2", "") { done = it }

        done shouldBe false
        await { it.message != null }.message shouldBe GearMessage.Invalid(GearTarget.PROFILE, GearProblemField.OFFSET)
        val id = repository.ownFlow.value.single().ref.removePrefix("own:")
        vm.saveProfile(id, "Mi antena", "0.13", "0.05") { done = it }
        done shouldBe true
        repository.ownFlow.value.single().offsetM shouldBe 0.13
    }

    @Test
    fun `S046 RF-5 un modelo del catalogo da los datos de un perfil propio con el nombre de copia`() {
        val draft = vm.profileDraft("catalog:RS4", "copia").shouldNotBeNull()

        draft shouldBe ProfileDraft("Reach RS4/RS4 Pro (copia)", "0.095", "")
        vm.profileDraft("own:no-existe", "copia").shouldBeNull()
    }

    @Test
    fun `S046 RF-6 un perfil en uso no se borra y da el aviso`() {
        vm.saveProfile(null, "Mi antena", "0.12", "")
        val ref = repository.ownFlow.value.single().ref
        vm.selectProfile(ref)

        vm.deleteProfile(ref.removePrefix("own:"))

        await { it.message is GearMessage.ProfileInUse }.message shouldBe GearMessage.ProfileInUse(listOf(CURRENT))
    }

    @Test
    fun `S046 RF-10 y RF-16 un jalon inclinado se guarda con su tipo y uno no valido da su aviso`() {
        vm.savePole(null, "Inclinado", PoleKind.SLANT, "1,95")
        await { it.poles.size == 3 }.poles.last().kind shouldBe PoleKind.SLANT

        vm.savePole(null, "Largo", PoleKind.VERTICAL, "11")

        await { it.message != null }.message shouldBe GearMessage.Invalid(GearTarget.POLE, GearProblemField.HEIGHT)
    }

    @Test
    fun `S046 RF-11 y RF-12 no se borra el ultimo jalon ni el activo de un receptor`() {
        vm.selectPole("p2")
        await { it.gear.pole?.id == "p2" }

        vm.deletePole("p2")
        await { it.message is GearMessage.PoleActive }.message shouldBe GearMessage.PoleActive(listOf(CURRENT))

        vm.selectPole("p1")
        vm.deletePole("p2")
        await { it.poles.size == 1 }.message.shouldBeNull()
        vm.deletePole("p1")
        await { it.message == GearMessage.LastPole }.poles.size shouldBe 1
    }

    @Test
    fun `S046 RF-13 y RF-14 elegir el jalon activo y el perfil cambia la altura de antena`() {
        vm.selectPole("p2")
        vm.selectProfile("catalog:RS4")

        val state = await { it.gear.profile.ref == "catalog:RS4" && it.gear.pole?.id == "p2" }

        state.gear.antennaHeightM!! shouldBe (1.295 plusOrMinus 1e-12)
    }

    @Test
    fun `S046 RF-19 cambiar el perfil con un jalon inclinado activo avisa de que su altura vertical cambia`() {
        vm.saveProfile(null, "Con radio", "0.12", "0.06")
        vm.savePole(null, "Inclinado", PoleKind.SLANT, "1.95")
        val profile = repository.ownFlow.value.single().ref
        vm.selectPole(repository.polesFlow.value.last().id)

        vm.selectProfile(profile)

        val state = await { it.gear.profile.ref == profile && it.message != null }
        state.message shouldBe GearMessage.ProfileChanged("Inclinado")
        state.gear.problem.shouldBeNull()
        state.gear.verticalPoleM!! shouldBe (1.949076 plusOrMinus 1e-6)
        vm.selectProfile("catalog:RS3")
        await { it.gear.problem == GearProblem.NO_RADIUS }
    }

    @Test
    fun `S046 RF-27 con una medida en curso no se cambia el equipo`() {
        inProgress.set(true)

        vm.selectPole("p2")
        vm.selectProfile("catalog:RS4")

        val state = await { it.message == GearMessage.Locked }
        state.locked shouldBe true
        state.gear.pole?.id shouldBe "p1"
        state.gear.profile shouldBe AntennaProfile.RS3
        var done: Boolean? = null
        vm.savePole("p1", "Jalón estándar", PoleKind.VERTICAL, "2") { done = it }
        done shouldBe false
        repository.polesFlow.value.first().enteredM shouldBe 1.8

        inProgress.set(false)
        vm.selectPole("p2")
        await { it.gear.pole?.id == "p2" }.locked shouldBe false
    }

    private companion object {
        /** El receptor sin conectar de los tests: la clave por defecto de `CurrentGear`. */
        const val CURRENT = "none"
    }
}
