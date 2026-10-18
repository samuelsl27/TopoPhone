package es.topophone.app.ui.projects

import android.content.Context
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.receiver.nmea
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.format.AngleFormat
import es.topophone.core.model.format.FactorFormat
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.HeightSystem
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
import java.util.Locale
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

/**
 * Vista de proyecto con los SC de España: geográficas, altura elipsoidal, ED50, otro huso y factores
 * (S008/RF-4 a RF-6, RF-8, RF-12 a RF-16). Interfaz en es-ES.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ProjectViewCrsTest {

    @get:Rule
    val compose = createComposeRule()

    private val es = Locale.forLanguageTag("es-ES")

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

    private val geographic =
        ProjectPosition(height = 700.1, latitudeDeg = 40.418_678_912_3, longitudeDeg = -3.703_790_123_4, positionZone = 30)

    private fun show(project: Project, position: ProjectPosition?) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectViewScreen(ThemeState.DEFAULT, {}, ProjectViewUiState(project, position), onBack = {})
            }
        }
    }

    private fun row(tag: String) = compose.onNodeWithTag(tag).performScrollTo()

    /** Los factores están en el panel desplegado (S009/RF-4). */
    private fun moreData() = compose.onNodeWithText("Más datos").performScrollTo().performClick()

    @Test
    fun `S008 RF-5 con altura elipsoidal la vista da la altura elipsoidal del punto del suelo en las unidades del proyecto`() {
        show(project.copy(heightSystem = HeightSystem.ELLIPSOIDAL), madrid.copy(height = 700.1))

        row(POSITION_HEIGHT_TAG).assert(hasText("Altura elipsoidal (h)")).assert(hasText("700,100 m", substring = true))
    }

    @Test
    fun `S008 RF-5 con altitud la vista la llama altitud EGM08-REDNAP`() {
        show(project, madrid)

        row(POSITION_HEIGHT_TAG).assert(hasText("Altitud (H, EGM08-REDNAP)")).assert(hasText("648,340 m", substring = true))
    }

    @Test
    fun `S008 RF-6 en geograficas la vista da latitud y longitud en las unidades angulares del proyecto`() {
        show(project.copy(crs = ProjectCrs.ETRS89_GEOGRAPHIC), geographic)

        row(
            POSITION_LATITUDE_TAG,
        ).assert(hasText("Latitud (φ)")).assert(hasText(AngleFormat.latitude(40.418_678_912_3, AngularUnit.DMS, es)))
        row(POSITION_LONGITUDE_TAG).assert(hasText(AngleFormat.longitude(-3.703_790_123_4, AngularUnit.DMS, es)))
        compose.onNodeWithTag(POSITION_EASTING_TAG).assertDoesNotExist()
    }

    @Test
    fun `S008 RF-6 en gon la latitud se da en gon`() {
        show(project.copy(crs = ProjectCrs.REGCAN95_GEOGRAPHIC, angularUnit = AngularUnit.GON), geographic)

        row(POSITION_LATITUDE_TAG).assert(hasText(AngleFormat.latitude(40.418_678_912_3, AngularUnit.GON, es)))
    }

    @Test
    fun `S008 RF-8 fuera de la rejilla ED50 no hay coordenadas y se dice por que`() {
        show(project.copy(crs = ProjectCrs.ED50_UTM30), ProjectPosition(unavailable = setOf(PositionUnavailable.OUTSIDE_ED50_GRID)))

        compose.onNodeWithText("Fuera de la transformación ED50 del IGN (rejilla SPED2ETV2).").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(POSITION_EASTING_TAG).assertDoesNotExist()
    }

    @Test
    fun `S008 RF-4 en Canarias, fuera de su geoide, la altitud dice por que falta`() {
        show(
            project.copy(crs = ProjectCrs.REGCAN95_UTM28),
            madrid.copy(height = null, unavailable = setOf(PositionUnavailable.OUTSIDE_GEOID), positionZone = 28),
        )

        row(POSITION_HEIGHT_TAG).assert(hasText(text(R.string.unavailable_outside_geoid_canarias), substring = true))
    }

    @Test
    fun `S008 RF-12 en otro huso se avisa y se siguen dando las coordenadas en el huso del proyecto`() {
        show(project, madrid.copy(positionZone = 29))

        compose.onNodeWithTag(OTHER_ZONE_TAG).performScrollTo().assert(hasText("Estás en el huso 29; el proyecto usa el 30."))
        row(POSITION_EASTING_TAG).assertIsDisplayed()
    }

    @Test
    fun `S008 RF-12 en el huso del proyecto no hay aviso`() {
        show(project, madrid)

        compose.onNodeWithTag(OTHER_ZONE_TAG).assertDoesNotExist()
    }

    @Test
    fun `S008 RF-13 RF-14 RF-15 en UTM se dan el factor de escala, la convergencia y el factor combinado`() {
        show(project, madrid)
        moreData()

        row(SCALE_FACTOR_TAG).assert(hasText("Factor de escala")).assert(hasText(FactorFormat.factor(0.999_612_345_678, es)))
        row(CONVERGENCE_TAG).assert(hasText("Convergencia")).assert(hasText(AngleFormat.convergence(-0.012_345_678, AngularUnit.DMS, es)))
        row(COMBINED_FACTOR_TAG).assert(hasText("Factor combinado")).assert(hasText(FactorFormat.factor(0.999_510_000_123, es)))
    }

    @Test
    fun `S008 RF-14 la convergencia sale en las unidades angulares del proyecto`() {
        show(project.copy(angularUnit = AngularUnit.GON), madrid)
        moreData()

        row(CONVERGENCE_TAG).assert(hasText(AngleFormat.convergence(-0.012_345_678, AngularUnit.GON, es)))
    }

    @Test
    fun `S008 RF-16 en geograficas no hay factores ni convergencia`() {
        show(
            project.copy(crs = ProjectCrs.ETRS89_GEOGRAPHIC),
            geographic.copy(scaleFactor = 1.0, convergenceRad = 0.0, combinedFactor = 1.0),
        )

        row(POSITION_LATITUDE_TAG).assertIsDisplayed()
        compose.onNodeWithText("Más datos").assertDoesNotExist() // S009/RF-4: nada que desplegar
        compose.onNodeWithTag(SCALE_FACTOR_TAG).assertDoesNotExist()
        compose.onNodeWithTag(CONVERGENCE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(COMBINED_FACTOR_TAG).assertDoesNotExist()
    }

    @Test
    fun `S008 RF-1 la lista nombra el SC con su datum`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectListScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectListUiState(projects = listOf(project.copy(crs = ProjectCrs.ED50_UTM30))),
                    ProjectListActions(),
                )
            }
        }

        compose.onNodeWithTag(projectTag("p1")).assert(hasText("ED50 / UTM huso 30"))
    }

    // --- ViewModel: la posición sale en el SC y la altura del proyecto ---

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(sessionScope, NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE })

    @After
    fun closeSession() {
        session.disconnect()
        sessionScope.cancel()
    }

    @Test
    fun `S008 RF-5 RF-6 el ViewModel calcula la posicion en las geograficas REGCAN95 con altura elipsoidal`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java).build()
        val toApp = PipedOutputStream()
        try {
            val repository = RoomProjectRepository(db)
            val draft = ProjectDefaults().toDraft()
                .copy(name = "Puerto de la Cruz", crs = ProjectCrs.REGCAN95_GEOGRAPHIC, heightSystem = HeightSystem.ELLIPSOIDAL)
            val id = runBlocking { (repository.create(draft) as SaveResult.Saved).id }
            session.connect(StreamTransport("reach-RS3", PipedInputStream(toApp, 64 * 1024), ByteArrayOutputStream()))
            toApp.write(nmea("GNGGA,101530.00,2827.8160000,N,01615.1080000,W,4,24,0.6,30.123,M,40.234,M,1.0,0001").toByteArray())
            toApp.flush()
            val vm = projectViewModel(repository, session, calculator = CALCULATOR)
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
            state.position shouldBe CALCULATOR.compute(fix, 1.8, ProjectCrs.REGCAN95_GEOGRAPHIC, HeightSystem.ELLIPSOIDAL)
            state.position.shouldNotBeNull().latitudeDeg.shouldNotBeNull()
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
