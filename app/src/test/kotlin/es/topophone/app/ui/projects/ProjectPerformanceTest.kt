package es.topophone.app.ui.projects

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import io.kotest.matchers.shouldBe
import kotlin.time.measureTimedValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Rendimiento con 500 proyectos (S006, RNF de la spec): lista en menos de 1 s y búsqueda en menos
 * de 300 ms. Robolectric sirve de cota; la medida en el móvil de referencia es de la demo.
 */
@RunWith(AndroidJUnit4::class)
class ProjectPerformanceTest {

    @get:Rule
    val compose = createComposeRule()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private var now = 1_000L
    private val repository = RoomProjectRepository(db, clock = { now })
    private val draft = ProjectDefaults().toDraft()

    @After
    fun close() = db.close()

    private fun create500(): List<Project> = runBlocking {
        repeat(PROJECTS) { i ->
            now += 1
            repository.create(draft.copy(name = "Proyecto %03d".format(i), description = "Obra número $i"))
        }
        repository.projects(folderId = null).first()
    }

    @Test
    fun `S006 RNF con 500 proyectos la lista sale en menos de 1 s y la busqueda en menos de 300 ms`() {
        create500()

        val (list, listTime) = measureTimedValue { runBlocking { repository.projects(folderId = null).first() } }
        val (found, searchTime) = measureTimedValue { runBlocking { repository.search("Proyecto 042").first() } }

        list.size shouldBe PROJECTS
        found.map { it.project.name } shouldBe listOf("Proyecto 042")
        (listTime.inWholeMilliseconds < 1_000) shouldBe true
        (searchTime.inWholeMilliseconds < 300) shouldBe true
    }

    @Test
    fun `S006 RNF la pantalla con 500 proyectos se pinta en menos de 1 s`() {
        val projects = create500()
        // La pantalla ya está compuesta (con un proyecto) cuando llegan los 500: se mide la lista, no el arranque del entorno.
        var state by mutableStateOf(ProjectListUiState(projects = projects.take(1)))
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { ProjectListScreen(ThemeState.DEFAULT, {}, state, ProjectListActions()) }
        }
        compose.waitForIdle()

        val (_, time) = measureTimedValue {
            state = ProjectListUiState(projects = projects)
            compose.waitForIdle()
        }

        (time.inWholeMilliseconds < 1_000) shouldBe true
        compose.onNodeWithTag(projectTag(projects.first().id)).assertIsDisplayed()
        // Solo se componen las filas visibles: la última no existe hasta que se llega a ella.
        compose.onNodeWithTag(projectTag(projects.last().id)).assertDoesNotExist()
        compose.onNodeWithTag(PROJECT_LIST_TAG).performScrollToNode(hasTestTag(projectTag(projects.last().id)))
        compose.onNodeWithTag(projectTag(projects.last().id)).assertIsDisplayed()
    }

    private companion object {
        const val PROJECTS = 500
    }
}
