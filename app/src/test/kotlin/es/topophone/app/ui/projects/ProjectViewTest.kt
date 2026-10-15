package es.topophone.app.ui.projects

import android.content.Context
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.receiver.FakePreferences
import es.topophone.app.ui.receiver.nmea
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
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
import es.topophone.core.survey.PositionUnavailable
import es.topophone.core.survey.ProjectPosition
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.transport.StreamTransport
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

/** Vista de proyecto (S006/RF-11, RF-28, RF-31). Interfaz en español (es-ES). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ProjectViewTest {

    @get:Rule
    val compose = createComposeRule()

    private val project = Project(
        id = "p1",
        name = "Calle Mayor 12",
        author = "S. S.",
        description = "",
        crs = ProjectCrs.ETRS89_UTM30,
        linearUnit = LinearUnit.US_SURVEY_FOOT,
        angularUnit = AngularUnit.GON,
        correctionsDatum = CorrectionsDatum.ETRS89,
        folderId = null,
        createdAtMs = 1_000,
        modifiedAtMs = 1_000,
        openedAtMs = null,
        deletedAtMs = null,
    )

    private fun show(project: Project, position: ProjectPosition? = null) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectViewScreen(ThemeState.DEFAULT, {}, ProjectViewUiState(project, position), onBack = {})
            }
        }
    }

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(sessionScope, NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE })

    @After
    fun closeSession() {
        session.disconnect()
        sessionScope.cancel()
    }

    private fun viewModel(repository: RoomProjectRepository) = ProjectViewModel(
        repository = repository,
        session = session,
        preferences = FakePreferences(),
        calculator = { CALCULATOR },
        samplePeriodMs = 0,
    )

    private val madrid = ProjectPosition(440_290.434, 4_474_257.414, 648.34, emptySet())

    @Test
    fun `S006 RF-28 al abrir un proyecto se ven su nombre, su SC, sus unidades y el datum de las correcciones`() {
        show(project)

        compose.onNodeWithText("Calle Mayor 12").assertIsDisplayed()
        compose.onNodeWithTag(PROJECT_INFO_TAG)
            .assert(hasText(text(R.string.project_crs_utm, 30)))
            .assert(hasText(text(R.string.unit_us_survey_foot), substring = true))
            .assert(hasText(text(R.string.unit_gon), substring = true))
            .assert(hasText(text(R.string.datum_etrs89), substring = true))
    }

    @Test
    fun `S006 RF-11 con correcciones en ITRF-WGS84 la vista de proyecto avisa`() {
        show(project.copy(correctionsDatum = CorrectionsDatum.ITRF_WGS84))

        compose.onNodeWithTag(DATUM_WARNING_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S006 RF-11 con correcciones en ETRS89 no hay aviso en la vista de proyecto`() {
        show(project)

        compose.onNodeWithTag(DATUM_WARNING_TAG).assertDoesNotExist()
    }

    @Test
    fun `S006 RF-31 mientras el proyecto no tenga puntos la vista lo dice`() {
        show(project)

        compose.onNodeWithTag(NO_POINTS_TAG).performScrollTo().assert(hasText(text(R.string.project_no_points)))
    }

    @Test
    fun `S006 RF-28 el ViewModel da el proyecto abierto y lo marca como abierto`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java).build()
        try {
            var now = 1_000L
            val repository = RoomProjectRepository(db, clock = { now })
            val id = runBlocking { (repository.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")) as SaveResult.Saved).id }
            now = 9_000
            val vm = viewModel(repository)

            vm.start(id)

            val state = runBlocking {
                val collector = CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
                try {
                    withTimeout(5_000) { vm.state.first { it.project?.openedAtMs == 9_000L } }
                } finally {
                    collector.cancel()
                }
            }
            state.project.shouldNotBeNull().name shouldBe "Calle Mayor 12"
        } finally {
            db.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `S006 RF-29 la posicion se muestra en el SC del proyecto con sus unidades`() {
        show(project.copy(linearUnit = LinearUnit.METRE), madrid)

        compose.onNodeWithTag(POSITION_EASTING_TAG).performScrollTo().assert(hasText("440.290,434 m", substring = true))
        compose.onNodeWithTag(POSITION_NORTHING_TAG).assert(hasText("4.474.257,414 m", substring = true))
        compose.onNodeWithTag(POSITION_HEIGHT_TAG).assert(hasText("648,340 m", substring = true))
    }

    @Test
    fun `S006 RF-29 en pies topograficos de EE UU las coordenadas se pasan a esa unidad`() {
        show(project.copy(linearUnit = LinearUnit.US_SURVEY_FOOT), madrid.copy(orthometricHeight = 1200.0))

        compose.onNodeWithTag(
            POSITION_HEIGHT_TAG,
        ).performScrollTo().assert(hasText("3.937,000 ${text(R.string.unit_symbol_us_survey_foot)}", substring = true))
    }

    @Test
    fun `S006 RF-29 sin posicion del receptor se dice`() {
        show(project, position = null)

        compose.onNodeWithText(text(R.string.project_no_position)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S006 RF-30 fuera del ambito del SC no hay coordenadas y se dice por que`() {
        show(project, ProjectPosition(null, null, null, setOf(PositionUnavailable.OUTSIDE_CRS_AREA)))

        compose.onNodeWithText(text(R.string.project_outside_crs)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(POSITION_EASTING_TAG).assertDoesNotExist()
    }

    @Test
    fun `S006 RF-30 fuera del geoide hay Este y Norte y la altitud dice por que falta`() {
        show(
            project.copy(linearUnit = LinearUnit.METRE),
            madrid.copy(orthometricHeight = null, unavailable = setOf(PositionUnavailable.OUTSIDE_GEOID)),
        )

        compose.onNodeWithTag(POSITION_EASTING_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(POSITION_HEIGHT_TAG).assert(hasText(text(R.string.unavailable_outside_geoid), substring = true))
    }

    @Test
    fun `S006 RF-29 S006 RF-15 el ViewModel calcula la posicion del receptor en el huso del proyecto`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java).build()
        val toApp = PipedOutputStream()
        try {
            val repository = RoomProjectRepository(db)
            val draft = ProjectDefaults().toDraft().copy(name = "Calle Mayor 12", crs = ProjectCrs.ETRS89_UTM29)
            val id = runBlocking { (repository.create(draft) as SaveResult.Saved).id }
            session.connect(StreamTransport("reach-RS3", PipedInputStream(toApp, 64 * 1024), ByteArrayOutputStream()))
            val gga = "GNGGA,101530.00,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001"
            toApp.write(nmea(gga).toByteArray())
            toApp.flush()
            val vm = viewModel(repository)
            vm.start(id)

            val state = runBlocking {
                val collector = CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
                try {
                    withTimeout(5_000) { vm.state.first { it.position != null } }
                } finally {
                    collector.cancel()
                }
            }

            val fix = session.state.value.gga.shouldNotBeNull()
            state.position shouldBe CALCULATOR.compute(fix, 1.8, ProjectCrs.ETRS89_UTM29)
        } finally {
            runCatching { toApp.close() }
            db.close()
            Dispatchers.resetMain()
        }
    }

    private companion object {
        val CALCULATOR = ProjectPositionCalculator()
    }
}
