package es.topophone.app.ui.projects

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.map.FakeMapPreferences
import es.topophone.app.ui.receiver.nmea
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.geodesy.Utm
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.CentralPoint
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.CentralPointRejection
import es.topophone.data.codes.RoomCodeLibraryRepository
import es.topophone.data.crs.RoomProjectCrsHistoryRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.ProjectPreferences
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
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
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** El punto central en el formulario de proyecto, nuevo y editado (S006/RF-32 a RF-35). Interfaz en español (es-ES). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ProjectFormCentralPointTest {

    @get:Rule
    val compose = createComposeRule()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val repository = RoomProjectRepository(db)
    private val central = CentralPoint(40.4168, -3.7038)

    private class FakeProjectPreferences : ProjectPreferences {
        override val defaults = MutableStateFlow(ProjectDefaults())

        override suspend fun saveDefaults(defaults: ProjectDefaults) {
            this.defaults.value = defaults
        }
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    private val toApp = PipedOutputStream()
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(sessionScope, NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE })

    @After
    fun tearDown() {
        runCatching { toApp.close() }
        session.disconnect()
        sessionScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun ProjectFormViewModel.awaitState(predicate: (ProjectFormUiState) -> Boolean): ProjectFormUiState = runBlocking {
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { state.collect {} }
        try {
            withTimeout(5_000) { state.first(predicate) }
        } finally {
            collector.cancel()
        }
    }

    /** GGA en FIX en [latitudeDeg], [longitudeDeg]. */
    private fun gga(latitudeDeg: Double, longitudeDeg: Double): String {
        fun dm(value: Double, degreeDigits: Int): String {
            val degrees = abs(value).toInt()
            return "%0${degreeDigits}d%010.7f".format(Locale.ROOT, degrees, (abs(value) - degrees) * 60.0)
        }
        val ns = if (latitudeDeg >= 0) "N" else "S"
        val ew = if (longitudeDeg >= 0) "E" else "W"
        return "GNGGA,101530.00,${dm(latitudeDeg, 2)},$ns,${dm(longitudeDeg, 3)},$ew,4,24,0.6,650.123,M,51.234,M,1.0,0001"
    }

    /** Receptor (o reproducción) recibiendo en la posición dada. */
    private fun receiverAt(latitudeDeg: Double, longitudeDeg: Double) {
        session.connect(StreamTransport("reach-RS3", PipedInputStream(toApp, 64 * 1024), ByteArrayOutputStream()))
        toApp.write(nmea(gga(latitudeDeg, longitudeDeg)).toByteArray())
        toApp.flush()
        runBlocking { withTimeout(5_000) { session.state.first { it.connection is ConnectionState.Receiving && it.gga != null } } }
    }

    /** El formulario conectado a su ViewModel, como en la app. */
    private fun open(projectId: String? = null): ProjectFormViewModel {
        val vm =
            ProjectFormViewModel(
                repository,
                FakeProjectPreferences(),
                session,
                FakeMapPreferences(),
                RoomCodeLibraryRepository(db),
                RoomProjectCrsHistoryRepository(db),
                centers(),
            )
        vm.start(projectId, folderId = null)
        vm.awaitState { it.loaded }
        compose.setContent {
            val state by vm.state.collectAsState()
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectFormScreen(
                    ThemeState.DEFAULT,
                    {},
                    state,
                    ProjectFormActions(
                        vm::update,
                        vm::save,
                        {},
                        onCentralTyped = vm::fixCentralTyped,
                        onCentralRemove = vm::clearCentralPoint,
                        onCentralPosition = vm::fixCentralFromReceiver,
                    ),
                )
            }
        }
        return vm
    }

    private fun saved(centralPoint: CentralPoint?): String = runBlocking {
        repository.create(ProjectDefaults().toDraft().copy(name = "Con punto", centralPoint = centralPoint))
            .shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private fun type(first: String, second: String) {
        compose.onNodeWithTag(CENTRAL_POINT_TYPE_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(CENTRAL_POINT_FIRST_TAG).performTextReplacement(first)
        compose.onNodeWithTag(CENTRAL_POINT_SECOND_TAG).performTextReplacement(second)
        compose.onNodeWithTag(CENTRAL_POINT_CONFIRM_TAG).performClick()
    }

    @Test
    fun `S006 RF-32 el punto central es opcional y por defecto no esta fijado`() {
        val vm = open()

        vm.state.value.draft.centralPoint.shouldBeNull()
        compose.onNodeWithTag(CENTRAL_POINT_EMPTY_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(CENTRAL_POINT_EMPTY_TAG).assert(hasText(text(R.string.central_point_unset)))
        compose.onNodeWithTag(CENTRAL_POINT_REMOVE_TAG).assertDoesNotExist()
    }

    @Test
    fun `S006 RF-33 escribir Este y Norte del SC del proyecto lo fija sin redondear`() {
        val vm = open()
        val utm = Utm.forward(40.4168, -3.7038, 30)

        type(String.format(Locale.ROOT, "%.4f", utm.easting), String.format(Locale.ROOT, "%.4f", utm.northing))

        val point = vm.state.value.draft.centralPoint.shouldNotBeNull()
        point.latitudeDeg shouldBe (40.4168 plusOrMinus 1e-8)
        point.longitudeDeg shouldBe (-3.7038 plusOrMinus 1e-8)
        vm.state.value.centralPointError.shouldBeNull()
        compose.onNodeWithTag(CENTRAL_POINT_EASTING_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(CENTRAL_POINT_NORTHING_TAG).assertIsDisplayed()
    }

    @Test
    fun `S006 RF-34 unas coordenadas fuera del ambito no se fijan e indican el motivo`() {
        val vm = open()

        type("500000", "100000") // latitud cerca de 1°: fuera de España

        vm.state.value.draft.centralPoint.shouldBeNull()
        vm.state.value.centralPointError shouldBe CentralPointRejection.OUTSIDE_CRS_AREA
        compose.onNodeWithTag(CENTRAL_POINT_ERROR_TAG).performScrollTo().assert(hasText(text(R.string.central_point_outside_crs)))
    }

    @Test
    fun `S006 RF-34 unas coordenadas que no son numeros no se fijan e indican el motivo`() {
        val vm = open()

        type("abc", "4474257")

        vm.state.value.draft.centralPoint.shouldBeNull()
        compose.onNodeWithTag(CENTRAL_POINT_ERROR_TAG).performScrollTo().assert(hasText(text(R.string.central_point_invalid)))
    }

    @Test
    fun `S006 RF-34 un rechazo no quita el punto central que ya habia`() {
        val vm = open(saved(central))

        type("500000", "100000")

        vm.state.value.draft.centralPoint shouldBe central
        vm.state.value.centralPointError shouldBe CentralPointRejection.OUTSIDE_CRS_AREA
    }

    @Test
    fun `S006 RF-32 al editar el formulario parte del punto central guardado y lo muestra en el SC`() {
        val id =
            saved(
                CentralPoint(
                    Utm.inverse(440_290.434, 4_474_257.414, 30).latitudeDeg,
                    Utm.inverse(440_290.434, 4_474_257.414, 30).longitudeDeg,
                ),
            )

        val vm = open(id)

        vm.state.value.draft.centralPoint.shouldNotBeNull()
        compose.onNodeWithTag(CENTRAL_POINT_EASTING_TAG).performScrollTo().assert(hasText("440.290,434 m", substring = true))
        compose.onNodeWithTag(CENTRAL_POINT_NORTHING_TAG).assert(hasText("4.474.257,414 m", substring = true))
    }

    @Test
    fun `S006 RF-35 quitar el punto central lo borra del formulario y del proyecto al guardar`() {
        val id = saved(central)
        val vm = open(id)

        compose.onNodeWithTag(CENTRAL_POINT_REMOVE_TAG).performScrollTo().performClick()
        vm.state.value.draft.centralPoint.shouldBeNull()
        compose.onNodeWithTag(CENTRAL_POINT_EMPTY_TAG).performScrollTo().assertIsDisplayed()

        vm.save()
        vm.awaitState { it.savedId == id }
        runBlocking { repository.project(id) }!!.centralPoint.shouldBeNull()
    }

    @Test
    fun `S006 RF-32 el punto central fijado se guarda con el proyecto nuevo`() {
        val vm = open()
        vm.update(vm.state.value.draft.copy(name = "Nuevo"))
        val utm = Utm.forward(40.4168, -3.7038, 30)
        type(String.format(Locale.ROOT, "%.4f", utm.easting), String.format(Locale.ROOT, "%.4f", utm.northing))

        vm.save()

        val id = vm.awaitState { it.savedId != null }.savedId!!
        runBlocking { repository.project(id) }!!.centralPoint.shouldNotBeNull().latitudeDeg shouldBe (40.4168 plusOrMinus 1e-8)
    }

    @Test
    fun `S006 RF-33 usar mi posicion fija el punto central con la del receptor sin redondear`() {
        receiverAt(40.4168123, -3.7038456)
        val vm = open()

        compose.onNodeWithTag(CENTRAL_POINT_POSITION_TAG).performScrollTo().performClick()

        val point = vm.state.value.draft.centralPoint.shouldNotBeNull()
        point.latitudeDeg shouldBe (40.4168123 plusOrMinus 1e-9)
        point.longitudeDeg shouldBe (-3.7038456 plusOrMinus 1e-9)
        vm.state.value.centralPointError.shouldBeNull()
    }

    @Test
    fun `S006 RF-34 sin posicion del receptor no se fija e indica el motivo`() {
        val vm = open()

        compose.onNodeWithTag(CENTRAL_POINT_POSITION_TAG).performScrollTo().performClick()

        vm.state.value.draft.centralPoint.shouldBeNull()
        vm.state.value.centralPointError shouldBe CentralPointRejection.NO_POSITION
        compose.onNodeWithTag(CENTRAL_POINT_ERROR_TAG).performScrollTo().assert(hasText(text(R.string.central_point_no_position)))
    }

    @Test
    fun `S006 RF-34 una posicion fuera del ambito del SC no se fija e indica el motivo`() {
        receiverAt(-34.6, -58.4) // Buenos Aires, con un proyecto en ETRS89 / UTM 30
        val vm = open()

        compose.onNodeWithTag(CENTRAL_POINT_POSITION_TAG).performScrollTo().performClick()

        vm.state.value.draft.centralPoint.shouldBeNull()
        vm.state.value.centralPointError shouldBe CentralPointRejection.OUTSIDE_CRS_AREA
    }

    @Test
    fun `S006 RNF-T8 el boton de usar mi posicion mide al menos 56 dp`() {
        open()

        compose.onNodeWithTag(CENTRAL_POINT_POSITION_TAG).performScrollTo().assertHeightIsAtLeast(56.dp)
    }

    @Test
    fun `S006 RNF-T8 los botones del punto central miden al menos 56 dp`() {
        open(saved(central))

        listOf(CENTRAL_POINT_TYPE_TAG, CENTRAL_POINT_REMOVE_TAG).forEach {
            compose.onNodeWithTag(it).performScrollTo().assertHeightIsAtLeast(56.dp)
        }
    }

    private fun centers() = es.topophone.app.ui.projects.ProjectCenterProvider(
        es.topophone.app.ui.map.FakePointRepository(),
        es.topophone.app.ui.map.FakeMapViewportRepository(),
    )
}
