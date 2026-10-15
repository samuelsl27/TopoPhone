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
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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

    private fun show(project: Project) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { ProjectViewScreen(ThemeState.DEFAULT, {}, ProjectViewUiState(project), onBack = {}) }
        }
    }

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
            val vm = ProjectViewModel(repository)

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
}
