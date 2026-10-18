package es.topophone.app.ui.projects

import android.content.Context
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.HeightSystem
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
import org.robolectric.annotation.Config

/** Datos del proyecto, a un toque desde el mapa (S006/RF-11, RF-28, RF-31; S008/RF-1, RF-2; S009/RF-5). Interfaz en es-ES. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ProjectInfoTest {

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

    private fun show(project: Project, pointCount: Int = 0) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectInfoScreen(ThemeState.DEFAULT, {}, ProjectInfoUiState(project, pointCount), onBack = {})
            }
        }
    }

    @Test
    fun `S010 RF-21 con puntos los datos del proyecto dicen cuantos tiene`() {
        show(project, pointCount = 3)

        compose.onNodeWithTag(NO_POINTS_TAG).performScrollTo().assert(hasText("3 puntos"))
    }

    @Test
    fun `S010 RF-21 con un punto se dice en singular`() {
        show(project, pointCount = 1)

        compose.onNodeWithTag(NO_POINTS_TAG).performScrollTo().assert(hasText("1 punto"))
    }

    @Test
    fun `S006 RF-28 S009 RF-5 los datos del proyecto dan su SC, su altura, sus unidades y el datum de las correcciones`() {
        show(project)

        compose.onNodeWithTag(PROJECT_INFO_TAG)
            .assert(hasText(text(R.string.project_crs_utm, "ETRS89", 30)))
            .assert(hasText("Altitud (EGM08-REDNAP)"))
            .assert(hasText(text(R.string.unit_us_survey_foot), substring = true))
            .assert(hasText(text(R.string.unit_gon), substring = true))
            .assert(hasText(text(R.string.datum_etrs89), substring = true))
    }

    @Test
    fun `S008 RF-1 y RF-2 los datos nombran el SC con su datum y la altura elipsoidal`() {
        show(project.copy(crs = ProjectCrs.REGCAN95_GEOGRAPHIC, heightSystem = HeightSystem.ELLIPSOIDAL))

        compose.onNodeWithTag(PROJECT_INFO_TAG).assert(hasText("REGCAN95 / geográficas")).assert(hasText("Altura elipsoidal"))
    }

    @Test
    fun `S006 RF-11 S009 RF-5 con correcciones en ITRF-WGS84 los datos del proyecto avisan`() {
        show(project.copy(correctionsDatum = CorrectionsDatum.ITRF_WGS84))

        compose.onNodeWithTag(DATUM_WARNING_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S006 RF-11 con correcciones en ETRS89 no hay aviso`() {
        show(project)

        compose.onNodeWithTag(DATUM_WARNING_TAG).assertDoesNotExist()
    }

    @Test
    fun `S006 RF-31 S009 RF-5 mientras el proyecto no tenga puntos sus datos lo dicen`() {
        show(project)

        compose.onNodeWithTag(NO_POINTS_TAG).performScrollTo().assert(hasText(text(R.string.project_no_points)))
    }

    @Test
    fun `S009 RF-5 el ViewModel da el proyecto pedido`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java).build()
        try {
            val repository = RoomProjectRepository(db)
            val id = runBlocking { (repository.create(ProjectDefaults().toDraft().copy(name = "Muralla")) as SaveResult.Saved).id }
            val vm = ProjectInfoViewModel(repository, FakePointRepository())

            vm.start(id)

            val state = runBlocking {
                val collector = CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
                try {
                    withTimeout(5_000) { vm.state.first { it.project != null } }
                } finally {
                    collector.cancel()
                }
            }
            state.project.shouldNotBeNull().name shouldBe "Muralla"
        } finally {
            db.close()
            Dispatchers.resetMain()
        }
    }
}
