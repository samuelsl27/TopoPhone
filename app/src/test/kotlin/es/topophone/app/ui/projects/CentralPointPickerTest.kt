package es.topophone.app.ui.projects

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.map.FakeMapController
import es.topophone.app.ui.map.FakeMapPreferences
import es.topophone.app.ui.map.MapTap
import es.topophone.app.ui.map.fakeMap
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.model.map.Basemap
import es.topophone.core.model.map.MapSettings
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.CentralPoint
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.data.codes.RoomCodeLibraryRepository
import es.topophone.data.crs.RoomProjectCrsHistoryRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.ProjectPreferences
import es.topophone.data.project.RoomProjectRepository
import es.topophone.receiver.session.ReceiverSession
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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

/** Elegir el punto central tocando el mapa, a pantalla completa y sin salir del formulario (S006/RF-33, RF-34). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CentralPointPickerTest {

    @get:Rule
    val compose = createComposeRule()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val repository = RoomProjectRepository(db)
    private val map = FakeMapController()
    private val mapPreferences = FakeMapPreferences(MapSettings(basemap = Basemap.PNOA_ORTHOPHOTO))

    private class FakeProjectPreferences : ProjectPreferences {
        override val defaults = MutableStateFlow(ProjectDefaults())

        override suspend fun saveDefaults(defaults: ProjectDefaults) {
            this.defaults.value = defaults
        }
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(sessionScope, NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE })

    @After
    fun tearDown() {
        session.disconnect()
        sessionScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun open(central: CentralPoint? = null): ProjectFormViewModel {
        val vm =
            ProjectFormViewModel(
                repository,
                FakeProjectPreferences(),
                session,
                mapPreferences,
                RoomCodeLibraryRepository(db),
                RoomProjectCrsHistoryRepository(db),
                centers(),
            )
        vm.start(null, folderId = null)
        runBlocking { withTimeout(5_000) { vm.state.first { it.loaded } } }
        if (central != null) vm.update(vm.state.value.draft.copy(centralPoint = central))
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
                        onCentralTap = vm::resolveCentralTap,
                        onCentralPicked = vm::setCentralPoint,
                        pickerStart = vm::pickerStart,
                    ),
                    map = fakeMap(map),
                )
            }
        }
        return vm
    }

    private fun openPicker() {
        compose.onNodeWithTag(CENTRAL_POINT_PICK_TAG).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun tap(lat: Double, lon: Double) {
        map.tap(MapTap(lat, lon, toleranceM = 5.0))
        compose.waitForIdle()
    }

    @Test
    fun `S006 RF-33 un toque coloca el punto y Aceptar lo pasa al formulario sin redondear`() {
        val vm = open()
        openPicker()

        tap(40.4168123456, -3.7038123456)
        map.centralPoint shouldBe GeoPoint(40.4168123456, -3.7038123456)
        vm.state.value.draft.centralPoint.shouldBeNull() // aún no se ha aceptado

        compose.onNodeWithTag(CENTRAL_POINT_PICKER_ACCEPT_TAG).performClick()

        vm.state.value.draft.centralPoint shouldBe CentralPoint(40.4168123456, -3.7038123456)
        compose.onNodeWithTag(CENTRAL_POINT_PICKER_TAG).assertDoesNotExist() // se vuelve al formulario
        compose.onNodeWithTag(CENTRAL_POINT_EASTING_TAG).performScrollTo().assertExists()
    }

    @Test
    fun `S006 RF-33 Cancelar vuelve al formulario sin cambiar el punto central`() {
        val vm = open(CentralPoint(41.0, -2.0))
        openPicker()
        tap(40.0, -3.0)

        compose.onNodeWithTag(CENTRAL_POINT_PICKER_CANCEL_TAG).performClick()

        vm.state.value.draft.centralPoint shouldBe CentralPoint(41.0, -2.0)
        compose.onNodeWithTag(CENTRAL_POINT_PICKER_TAG).assertDoesNotExist()
    }

    @Test
    fun `S006 RF-34 un toque fuera del ambito no coloca el punto e indica el motivo`() {
        val vm = open()
        openPicker()

        tap(-34.6, -58.4) // Buenos Aires, con un proyecto en ETRS89 / UTM 30

        map.centralPoint.shouldBeNull()
        compose.onNodeWithTag(CENTRAL_POINT_PICKER_ERROR_TAG).assert(hasText(text(R.string.central_point_outside_crs)))
        compose.onNodeWithTag(CENTRAL_POINT_PICKER_ACCEPT_TAG).assertIsNotEnabled()
        vm.state.value.draft.centralPoint.shouldBeNull()
    }

    @Test
    fun `S006 RF-34 un toque valido tras uno rechazado quita el aviso y deja aceptar`() {
        open()
        openPicker()
        tap(-34.6, -58.4)

        tap(40.4168, -3.7038)

        compose.onNodeWithTag(CENTRAL_POINT_PICKER_ERROR_TAG).assertDoesNotExist()
        compose.onNodeWithTag(CENTRAL_POINT_PICKER_ACCEPT_TAG).assertIsEnabled()
    }

    @Test
    fun `S006 RF-33 el selector empieza en el punto central si lo hay y usa el mapa elegido`() {
        open(CentralPoint(41.0, -2.0))

        openPicker()

        map.settings.last().basemap shouldBe Basemap.PNOA_ORTHOPHOTO
        map.moves.last().latitudeDeg shouldBe 41.0
        map.moves.last().longitudeDeg shouldBe -2.0
        map.centralPoint shouldBe GeoPoint(41.0, -2.0)
    }

    @Test
    fun `S006 RF-33 sin punto central ni posicion el selector empieza en Espana`() {
        open()

        openPicker()

        map.spainShown shouldBe true
        map.centralPoint.shouldBeNull()
    }

    @Test
    fun `S006 RNF-T8 los botones del selector miden al menos 56 dp`() {
        open()
        openPicker()

        listOf(CENTRAL_POINT_PICKER_ACCEPT_TAG, CENTRAL_POINT_PICKER_CANCEL_TAG).forEach {
            compose.onNodeWithTag(it).assertHeightIsAtLeast(56.dp)
        }
    }

    private fun centers() = es.topophone.app.ui.projects.ProjectCenterProvider(
        es.topophone.app.ui.map.FakePointRepository(),
        es.topophone.app.ui.map.FakeMapViewportRepository(),
    )
}
