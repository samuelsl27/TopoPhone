package es.topophone.app.ui.projects

import android.content.Context
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.FakeMapController
import es.topophone.app.ui.map.fakeMap
import es.topophone.app.ui.receiver.FakePreferences
import es.topophone.app.ui.receiver.nmea
import es.topophone.core.designsystem.theme.TopoPhoneTheme
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
import es.topophone.core.survey.ProjectPosition
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.transport.StreamTransport
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
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

/** Vista de proyecto con el mapa a pantalla completa y el panel de posición (S009/RF-1 a RF-5). Interfaz en es-ES. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ProjectMapScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val project = Project(
        id = "p1",
        name = "Calle Mayor 12",
        author = "S. S.",
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

    private val madrid = ProjectPosition(
        easting = 440_290.434,
        northing = 4_474_257.414,
        height = 648.34,
        scaleFactor = 0.999_612_345_678,
        convergenceRad = -0.012_345_678,
        combinedFactor = 0.999_510_000_123,
        positionZone = 30,
    )

    private val map = FakeMapController()

    private fun show(state: ProjectViewUiState, onInfo: (String) -> Unit = {}) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectViewScreen(ThemeState.DEFAULT, {}, state, onBack = {}, onInfo = onInfo, map = fakeMap(map))
            }
        }
    }

    @Test
    fun `S009 RF-1 al abrir un proyecto el mapa ocupa la vista, con el nombre arriba y el panel de posicion abajo`() {
        show(ProjectViewUiState(project, madrid))

        compose.onNodeWithText("Calle Mayor 12").assertIsDisplayed()
        val mapBounds = compose.onNodeWithTag(PROJECT_MAP_TAG).assertIsDisplayed().getBoundsInRoot()
        val panelBounds = compose.onNodeWithTag(POSITION_PANEL_TAG).assertIsDisplayed().getBoundsInRoot()
        // El panel va encima del mapa, en su parte inferior.
        (panelBounds.bottom <= mapBounds.bottom) shouldBe true
        (panelBounds.top > mapBounds.top) shouldBe true
    }

    @Test
    fun `S009 RF-2 el panel da las coordenadas y la altura en el SC del proyecto`() {
        show(ProjectViewUiState(project, madrid))

        compose.onNodeWithTag(POSITION_EASTING_TAG).performScrollTo().assert(hasText("440.290,434 m", substring = true))
        compose.onNodeWithTag(POSITION_NORTHING_TAG).performScrollTo().assert(hasText("4.474.257,414 m", substring = true))
        compose.onNodeWithTag(POSITION_HEIGHT_TAG).performScrollTo().assert(hasText("648,340 m", substring = true))
    }

    @Test
    fun `S009 RF-3 el panel da la precision horizontal en las unidades del proyecto`() {
        show(ProjectViewUiState(project.copy(linearUnit = LinearUnit.INTERNATIONAL_FOOT), madrid, accuracyM = 0.012))

        compose.onNodeWithTag(ACCURACY_TAG).performScrollTo().assert(hasText("Precisión H")).assert(hasText("0,039 ft", substring = true))
    }

    @Test
    fun `S009 RF-3 sin frase GST no hay precision`() {
        show(ProjectViewUiState(project, madrid, accuracyM = null))

        compose.onNodeWithTag(ACCURACY_TAG).assertDoesNotExist()
    }

    @Test
    fun `S009 RF-4 los factores y la convergencia aparecen al desplegar el panel y se van al plegarlo`() {
        show(ProjectViewUiState(project, madrid))
        compose.onNodeWithTag(SCALE_FACTOR_TAG).assertDoesNotExist()

        compose.onNodeWithText("Más datos").performScrollTo().performClick()
        compose.onNodeWithTag(SCALE_FACTOR_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(CONVERGENCE_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(COMBINED_FACTOR_TAG).performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("Menos datos").performScrollTo().performClick()
        compose.onNodeWithTag(SCALE_FACTOR_TAG).assertDoesNotExist()
    }

    @Test
    fun `S009 RF-5 Datos del proyecto lleva a los datos de ese proyecto`() {
        var opened: String? = null
        show(ProjectViewUiState(project, madrid), onInfo = { opened = it })

        compose.onNodeWithTag(PROJECT_INFO_BUTTON_TAG).performClick()

        opened shouldBe "p1"
    }

    @Test
    fun `S009 RF-6 RF-10 el mapa recibe los ajustes del mapa y la unidad del proyecto`() {
        show(ProjectViewUiState(project.copy(linearUnit = LinearUnit.US_SURVEY_FOOT), madrid))
        compose.waitForIdle()

        map.settings.last() shouldBe MapSettings()
        map.unit shouldBe LinearUnit.US_SURVEY_FOOT
    }

    // --- ViewModel: la precisión horizontal sale de la GST ---

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(sessionScope, NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE })

    @After
    fun closeSession() {
        session.disconnect()
        sessionScope.cancel()
    }

    @Test
    fun `S009 RF-3 el ViewModel da la precision horizontal de la GST`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java).build()
        val toApp = PipedOutputStream()
        try {
            val repository = RoomProjectRepository(db)
            val id = runBlocking { (repository.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")) as SaveResult.Saved).id }
            session.connect(StreamTransport("reach-RS3", PipedInputStream(toApp, 64 * 1024), ByteArrayOutputStream()))
            toApp.write(nmea("GNGGA,101530.00,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001").toByteArray())
            toApp.write(nmea("GNGST,101530.00,0.012,0.010,0.007,45.0,0.008,0.006,0.015").toByteArray())
            toApp.flush()
            val vm = ProjectViewModel(repository, session, FakePreferences(), { ProjectPositionCalculator() }, samplePeriodMs = 0)
            vm.start(id)

            val state = runBlocking {
                val collector = CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
                try {
                    withTimeout(5_000) { vm.state.first { it.accuracyM != null } }
                } finally {
                    collector.cancel()
                }
            }

            state.accuracyM.shouldNotBeNull() shouldBe (0.010 plusOrMinus 1e-12) // √(0,008² + 0,006²)
        } finally {
            runCatching { toApp.close() }
            db.close()
            Dispatchers.resetMain()
        }
    }
}
