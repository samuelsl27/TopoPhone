package es.topophone.app.ui.projects

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.FakeMapController
import es.topophone.app.ui.map.FakeMapPreferences
import es.topophone.app.ui.map.fakeMap
import es.topophone.app.ui.receiver.FakePreferences
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.map.Basemap
import es.topophone.core.model.map.MapSettings
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.receiver.session.ReceiverSession
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Hoja "Capas": mapa base y Catastro, recordados para toda la app (S009/RF-6 a RF-8). Interfaz en es-ES. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class MapLayersTest {

    @get:Rule
    val compose = createComposeRule()

    private val project = Project(
        id = "p1",
        name = "Calle Mayor 12",
        author = "",
        description = "",
        crs = ProjectCrs.ETRS89_UTM30,
        linearUnit = LinearUnit.METRE,
        angularUnit = AngularUnit.DMS,
        correctionsDatum = CorrectionsDatum.ETRS89,
        folderId = null,
        createdAtMs = 1_000,
        modifiedAtMs = 1_000,
        openedAtMs = null,
        deletedAtMs = null,
    )

    private fun show(settings: MapSettings, onChange: (MapSettings) -> Unit = {}) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectViewScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectViewUiState(project, mapSettings = settings),
                    onBack = {},
                    onMapSettings = onChange,
                    map = fakeMap(FakeMapController()),
                )
            }
        }
        compose.onNodeWithTag(LAYERS_BUTTON_TAG).performClick()
    }

    @Test
    fun `S009 RF-6 Capas ofrece los cuatro mapas base con el elegido marcado`() {
        show(MapSettings(Basemap.PNOA_ORTHOPHOTO))

        Basemap.entries.forEach { compose.onNodeWithTag(basemapOptionTag(it)).performScrollTo().assertIsDisplayed() }
        compose.onNodeWithTag(basemapOptionTag(Basemap.PNOA_ORTHOPHOTO)).assertIsSelected()
    }

    @Test
    fun `S009 RF-6 elegir otro mapa base lo pide sin tocar el Catastro`() {
        var chosen: MapSettings? = null
        show(MapSettings(Basemap.IGN_VECTOR, cadastre = true)) { chosen = it }

        compose.onNodeWithTag(basemapOptionTag(Basemap.OSM_VECTOR)).performScrollTo().performClick()

        chosen shouldBe MapSettings(Basemap.OSM_VECTOR, cadastre = true)
    }

    @Test
    fun `S009 RF-7 el Catastro se superpone con su interruptor`() {
        var chosen: MapSettings? = null
        show(MapSettings(Basemap.NONE)) { chosen = it }

        compose.onNodeWithTag(CADASTRE_SWITCH_TAG).performScrollTo().assertIsOff().performClick()

        chosen shouldBe MapSettings(Basemap.NONE, cadastre = true)
    }

    // --- ViewModel ---

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(sessionScope, NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE })

    @After
    fun closeSession() {
        session.disconnect()
        sessionScope.cancel()
    }

    @Test
    fun `S009 RF-8 el ViewModel guarda los ajustes del mapa para toda la app y los da en el estado`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java).build()
        try {
            val repository = RoomProjectRepository(db)
            val id = runBlocking { (repository.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")) as SaveResult.Saved).id }
            val preferences = FakeMapPreferences()
            val vm =
                ProjectViewModel(repository, session, FakePreferences(), { ProjectPositionCalculator() }, preferences, samplePeriodMs = 0)
            vm.start(id)

            vm.setMapSettings(MapSettings(Basemap.PNOA_ORTHOPHOTO, cadastre = true))

            preferences.settings.value shouldBe MapSettings(Basemap.PNOA_ORTHOPHOTO, cadastre = true)
            val state = runBlocking {
                val collector = CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
                try {
                    withTimeout(5_000) { vm.state.first { it.mapSettings.cadastre } }
                } finally {
                    collector.cancel()
                }
            }
            state.mapSettings.basemap shouldBe Basemap.PNOA_ORTHOPHOTO
        } finally {
            db.close()
            Dispatchers.resetMain()
        }
    }
}
