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
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.data.project.TrashedProject
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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

/** Borrar un proyecto y la papelera (S006/RF-22 a RF-27). Interfaz en español (es-ES). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class TrashTest {

    @get:Rule
    val compose = createComposeRule()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private var now = 1_000L
    private val repository = RoomProjectRepository(db, clock = { now })
    private val draft = ProjectDefaults().toDraft()
    private val day = 24L * 60 * 60 * 1000

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun project(id: String, name: String) = Project(
        id = id,
        name = name,
        author = "",
        description = "",
        crs = draft.crs,
        linearUnit = draft.linearUnit,
        angularUnit = draft.angularUnit,
        correctionsDatum = draft.correctionsDatum,
        folderId = null,
        createdAtMs = 1_000,
        modifiedAtMs = 1_000,
        openedAtMs = null,
        deletedAtMs = 2_000,
    )

    private fun create(name: String): String = runBlocking { (repository.create(draft.copy(name = name)) as SaveResult.Saved).id }

    /** Espera a que se cumpla [until]: las acciones del ViewModel terminan en otro hilo. */
    private fun <T> await(block: suspend () -> T, until: (T) -> Boolean): T = runBlocking {
        withTimeout(5_000) {
            var value = block()
            while (!until(value)) {
                delay(10)
                value = block()
            }
            value
        }
    }

    @Test
    fun `S006 RF-22 borrar un proyecto pide confirmacion y lo manda a la papelera`() {
        var deleted: String? = null
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectListScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectListUiState(projects = listOf(project("p1", "Parcela 23"))),
                    ProjectListActions(onDelete = { deleted = it }),
                )
            }
        }

        compose.onNodeWithTag(projectMenuTag("p1")).performClick()
        compose.onNodeWithText(text(R.string.project_delete)).performScrollTo().performClick() // el menú, con más elementos, se desplaza
        compose.onNodeWithText(text(R.string.project_delete_confirm, "Parcela 23")).assertIsDisplayed()
        deleted shouldBe null
        compose.onNodeWithTag(CONFIRM_TAG).performClick()

        deleted shouldBe "p1"
    }

    @Test
    fun `S006 RF-22 el ViewModel de la lista mueve el proyecto a la papelera`() {
        val id = create("Parcela 23")
        val vm = ProjectListViewModel(repository)

        vm.delete(id)

        await({ repository.trash().first() }) { it.isNotEmpty() }.single().project.id shouldBe id
        runBlocking { repository.projects(null).first() } shouldBe emptyList()
    }

    @Test
    fun `S006 RF-23 la papelera muestra los proyectos borrados con los dias que les quedan`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                TrashScreen(ThemeState.DEFAULT, {}, TrashUiState(listOf(TrashedProject(project("p1", "Parcela 23"), 28))), TrashActions())
            }
        }

        compose.onNodeWithTag(trashedTag("p1")).assert(hasText("Parcela 23")).assert(hasText(text(R.string.trash_days_left, 28)))
    }

    @Test
    fun `S006 RF-23 sin proyectos borrados la papelera lo dice`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { TrashScreen(ThemeState.DEFAULT, {}, TrashUiState(), TrashActions()) }
        }

        compose.onNodeWithText(text(R.string.trash_empty)).assertIsDisplayed()
    }

    @Test
    fun `S006 RF-24 S006 RF-26 la papelera ofrece restaurar y borrar definitivamente, esto con confirmacion`() {
        var restored: String? = null
        var deleted: String? = null
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                TrashScreen(
                    ThemeState.DEFAULT,
                    {},
                    TrashUiState(listOf(TrashedProject(project("p1", "Parcela 23"), 30))),
                    TrashActions(onRestore = { restored = it }, onDeleteForever = { deleted = it }),
                )
            }
        }

        compose.onNodeWithTag(restoreTag("p1")).performClick()
        restored shouldBe "p1"

        compose.onNodeWithTag(deleteForeverTag("p1")).performClick()
        compose.onNodeWithText(text(R.string.trash_delete_forever_confirm, "Parcela 23")).assertIsDisplayed()
        deleted shouldBe null
        compose.onNodeWithTag(CONFIRM_TAG).performClick()
        deleted shouldBe "p1"
    }

    @Test
    fun `S006 RF-24 S006 RF-25 el ViewModel restaura con la palabra de la interfaz si el nombre esta ocupado`() {
        val id = create("Parcela 23")
        runBlocking { repository.moveToTrash(id) }
        create("Parcela 23")
        val vm = TrashViewModel(repository)

        vm.restore(id, restoredLabel = text(R.string.project_restored_label))

        await({ repository.project(id) }) { it?.deletedAtMs == null }?.name shouldBe "Parcela 23 (restaurado)"
    }

    @Test
    fun `S006 RF-26 el ViewModel borra definitivamente`() {
        val id = create("Parcela 23")
        runBlocking { repository.moveToTrash(id) }
        val vm = TrashViewModel(repository)

        vm.deleteForever(id)

        await({ repository.project(id) }) { it == null } shouldBe null
    }

    @Test
    fun `S006 RF-23 el ViewModel de la papelera da los proyectos borrados`() {
        val id = create("Parcela 23")
        runBlocking { repository.moveToTrash(id) }
        val vm = TrashViewModel(repository)

        val state = runBlocking {
            val collector = CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
            try {
                withTimeout(5_000) { vm.state.first { it.projects.isNotEmpty() } }
            } finally {
                collector.cancel()
            }
        }

        state.projects.single().project.id shouldBe id
    }

    @Test
    fun `S006 RF-27 al abrir la app se purga la papelera una sola vez por proceso`() {
        val expired = create("Caducado")
        now = 2 * day
        runBlocking { repository.moveToTrash(expired) }
        now = 40 * day
        val purge = TrashPurge(repository, RoomPointRepository(db, clock = { now }))

        runBlocking { purge.runOnce() } shouldBe true
        runBlocking { purge.runOnce() } shouldBe false

        runBlocking { repository.project(expired) } shouldBe null
    }

    @Test
    fun `S011 RF-26 al abrir la app se purgan tambien los puntos que llevan 30 dias en la papelera`() {
        val id = create("Con puntos")
        val points = RoomPointRepository(db, clock = { now })
        val point = Point(
            id = "p1",
            projectId = id,
            name = "1",
            description = "",
            origin = PointOrigin.MANUAL,
            coordinates = PointCoordinates(GeodeticPosition(40.0, -3.7, null), null, null, null),
            measurement = null,
            typed = null,
            edited = false,
            hidden = false,
            copyOf = null,
            createdAtMs = now,
            deletedAtMs = null,
        )
        runBlocking {
            points.create(point)
            now = 2 * day
            points.moveToTrash(listOf("p1"))
        }
        now = 40 * day

        runBlocking { TrashPurge(repository, points).runOnce() } shouldBe true

        runBlocking { points.point("p1").first() } shouldBe null
    }
}
