package es.topophone.app.ui.projects

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
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
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.project.ProjectDraft
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.ProjectPreferences
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.transport.StreamTransport
import io.kotest.matchers.shouldBe
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
import org.robolectric.annotation.Config

/** SC del proyecto en el formulario: datum, proyección, altura y huso propuesto (S008/RF-1 a RF-3, RF-11, RF-17 a RF-19). Interfaz en es-ES. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ProjectFormCrsTest {

    @get:Rule
    val compose = createComposeRule()

    private val initial = ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")

    /** Formulario con su estado, como el ViewModel: devuelve el borrador actual. */
    private fun show(draft: ProjectDraft = initial, editing: Boolean = false, proposed: ProjectCrs? = null): () -> ProjectDraft {
        var current by mutableStateOf(draft)
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectFormScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectFormUiState(draft = current, loaded = true, editing = editing, proposedCrs = proposed),
                    ProjectFormActions(onChange = { current = it }),
                )
            }
        }
        return { current }
    }

    private fun click(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()

    private fun shown(tag: String) = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()

    @Test
    fun `S008 RF-1 se elige el datum y despues la proyeccion de ese datum`() {
        val draft = show()

        GeodeticDatum.entries.forEach { shown(geodeticDatumOptionTag(it)) }
        listOf(ProjectCrs.ETRS89_UTM29, ProjectCrs.ETRS89_UTM30, ProjectCrs.ETRS89_UTM31, ProjectCrs.ETRS89_GEOGRAPHIC).forEach {
            shown(crsOptionTag(it))
        }

        click(geodeticDatumOptionTag(GeodeticDatum.REGCAN95))

        draft().crs shouldBe ProjectCrs.REGCAN95_UTM28
        listOf(ProjectCrs.REGCAN95_UTM27, ProjectCrs.REGCAN95_UTM28, ProjectCrs.REGCAN95_GEOGRAPHIC).forEach { shown(crsOptionTag(it)) }
        compose.onNodeWithTag(crsOptionTag(ProjectCrs.ETRS89_UTM30)).assertDoesNotExist()

        click(crsOptionTag(ProjectCrs.REGCAN95_GEOGRAPHIC))
        draft().crs shouldBe ProjectCrs.REGCAN95_GEOGRAPHIC
        compose.onNodeWithText(text(R.string.project_projection_geographic)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S008 RF-2 se elige altitud o altura elipsoidal`() {
        val draft = show()

        shown(heightOptionTag(HeightSystem.ORTHOMETRIC)).assertIsSelected()
        click(heightOptionTag(HeightSystem.ELLIPSOIDAL))

        draft().heightSystem shouldBe HeightSystem.ELLIPSOIDAL
    }

    @Test
    fun `S008 RF-3 en ED50 no se ofrece la altura elipsoidal y se pasa a altitud`() {
        val draft = show(initial.copy(heightSystem = HeightSystem.ELLIPSOIDAL))

        click(geodeticDatumOptionTag(GeodeticDatum.ED50))

        draft().crs shouldBe ProjectCrs.ED50_UTM30
        draft().heightSystem shouldBe HeightSystem.ORTHOMETRIC
        compose.onNodeWithTag(heightOptionTag(HeightSystem.ELLIPSOIDAL)).assertDoesNotExist()
        compose.onNodeWithTag(crsOptionTag(ProjectCrs.ETRS89_GEOGRAPHIC)).assertDoesNotExist() // ED50 solo en UTM
    }

    @Test
    fun `S008 RF-17 el datum de las correcciones sigue al SC elegido hasta que el usuario lo cambia`() {
        val draft = show()

        click(geodeticDatumOptionTag(GeodeticDatum.REGCAN95))
        draft().correctionsDatum shouldBe CorrectionsDatum.REGCAN95

        click(datumOptionTag(CorrectionsDatum.ITRF_WGS84))
        draft().correctionsDatum shouldBe CorrectionsDatum.ITRF_WGS84

        click(geodeticDatumOptionTag(GeodeticDatum.ED50))
        draft().correctionsDatum shouldBe CorrectionsDatum.ETRS89
    }

    @Test
    fun `S008 RF-18 si las correcciones no estan en el datum del SC se avisa`() {
        show(initial.copy(crs = ProjectCrs.REGCAN95_UTM28, correctionsDatum = CorrectionsDatum.ETRS89))

        compose.onNodeWithTag(DATUM_WARNING_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.project_datum_warning_general, text(R.string.datum_etrs89), "REGCAN95")).assertIsDisplayed()
    }

    @Test
    fun `S008 RF-18 con el datum de las correcciones del SC no hay aviso`() {
        show(initial.copy(crs = ProjectCrs.REGCAN95_UTM28, correctionsDatum = CorrectionsDatum.REGCAN95))

        compose.onNodeWithTag(DATUM_WARNING_TAG).assertDoesNotExist()
    }

    @Test
    fun `S008 RF-19 al editar no se puede cambiar el datum, solo la proyeccion del mismo datum`() {
        val draft = show(initial.copy(crs = ProjectCrs.ED50_UTM30), editing = true)

        compose.onNodeWithTag(geodeticDatumOptionTag(GeodeticDatum.ETRS89)).performScrollTo().assertIsNotEnabled()
        click(geodeticDatumOptionTag(GeodeticDatum.ETRS89))
        draft().crs shouldBe ProjectCrs.ED50_UTM30

        click(crsOptionTag(ProjectCrs.ED50_UTM31))
        draft().crs shouldBe ProjectCrs.ED50_UTM31
    }

    // --- S008/RF-11: huso propuesto por la posición del receptor al crear un proyecto ---

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(sessionScope, NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE })
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val toApp = PipedOutputStream()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        runCatching { toApp.close() }
        session.disconnect()
        sessionScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private class FixedPreferences(initial: ProjectDefaults) : ProjectPreferences {
        override val defaults = MutableStateFlow(initial)

        override suspend fun saveDefaults(defaults: ProjectDefaults) {
            this.defaults.value = defaults
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

    /** Receptor recibiendo en la posición dada. */
    private fun receiverAt(latitudeDeg: Double, longitudeDeg: Double) {
        session.connect(StreamTransport("reach-RS3", PipedInputStream(toApp, 64 * 1024), ByteArrayOutputStream()))
        toApp.write(nmea(gga(latitudeDeg, longitudeDeg)).toByteArray())
        toApp.flush()
        runBlocking { withTimeout(5_000) { session.state.first { it.connection is ConnectionState.Receiving && it.gga != null } } }
    }

    /** Abre el formulario (proyecto nuevo, o [projectId] para editar) con [defaults] en Ajustes. */
    private fun openForm(defaults: ProjectDefaults, projectId: String? = null): ProjectFormUiState {
        val vm = ProjectFormViewModel(RoomProjectRepository(db), FixedPreferences(defaults), session)
        vm.start(projectId = projectId, folderId = null)
        return runBlocking { withTimeout(5_000) { vm.state.first { it.loaded } } }
    }

    private val settings = ProjectDefaults(author = "S. S.", crs = ProjectCrs.ETRS89_UTM30, correctionsDatum = CorrectionsDatum.ITRF_WGS84)

    @Test
    fun `S008 RF-11 en la Peninsula se propone el huso de la posicion y lo demas sigue como en Ajustes`() {
        receiverAt(42.8782, -8.5448) // Santiago, huso 29

        val state = openForm(settings)

        state.draft shouldBe settings.toDraft().copy(crs = ProjectCrs.ETRS89_UTM29)
        state.proposedCrs shouldBe ProjectCrs.ETRS89_UTM29
    }

    @Test
    fun `S008 RF-11 en Canarias se propone REGCAN95 con sus correcciones`() {
        receiverAt(28.4636, -16.2518) // Santa Cruz de Tenerife, huso 28

        val state = openForm(settings)

        state.draft.crs shouldBe ProjectCrs.REGCAN95_UTM28
        state.draft.correctionsDatum shouldBe CorrectionsDatum.REGCAN95
    }

    @Test
    fun `S008 RF-11 con ED50 por defecto se propone el huso en ED50`() {
        receiverAt(41.3874, 2.1686) // Barcelona, huso 31

        openForm(settings.copy(crs = ProjectCrs.ED50_UTM30)).draft.crs shouldBe ProjectCrs.ED50_UTM31
    }

    @Test
    fun `S008 RF-11 con geograficas por defecto no se propone huso`() {
        receiverAt(42.8782, -8.5448)

        val state = openForm(settings.copy(crs = ProjectCrs.ETRS89_GEOGRAPHIC))

        state.draft.crs shouldBe ProjectCrs.ETRS89_GEOGRAPHIC
        state.proposedCrs shouldBe null
    }

    @Test
    fun `S008 RF-11 sin posicion del receptor no se propone nada`() {
        val state = openForm(settings)

        state.draft shouldBe settings.toDraft()
        state.proposedCrs shouldBe null
    }

    @Test
    fun `S008 RF-11 al editar un proyecto no se cambia su huso por la posicion`() {
        val id = runBlocking {
            (RoomProjectRepository(db).create(settings.toDraft().copy(name = "Calle Mayor 12")) as SaveResult.Saved).id
        }
        receiverAt(42.8782, -8.5448)

        val state = openForm(settings, projectId = id)

        state.draft.crs shouldBe ProjectCrs.ETRS89_UTM30
        state.proposedCrs shouldBe null
    }

    @Test
    fun `S008 RF-11 el formulario dice que el huso es el propuesto hasta que se cambia`() {
        show(initial.copy(crs = ProjectCrs.ETRS89_UTM29), proposed = ProjectCrs.ETRS89_UTM29)

        compose.onNodeWithTag(ZONE_PROPOSED_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.project_zone_proposed)).assertIsDisplayed()

        click(crsOptionTag(ProjectCrs.ETRS89_UTM30))
        compose.onNodeWithTag(ZONE_PROPOSED_TAG).assertDoesNotExist()
    }
}
