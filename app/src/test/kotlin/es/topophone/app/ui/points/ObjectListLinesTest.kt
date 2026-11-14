package es.topophone.app.ui.points

import android.content.Context
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
import es.topophone.app.ui.export.ExportSelection
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.app.ui.points.PointFixtures.manual
import es.topophone.app.ui.points.PointFixtures.measured
import es.topophone.app.ui.projects.CONFIRM_TAG
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.line.LineSummary
import es.topophone.core.model.line.LineVertex
import es.topophone.core.model.line.ProjectLine
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.line.LineSaveResult
import es.topophone.data.line.TrashedLine
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
import org.robolectric.annotation.Config

/** Unas líneas en memoria para probar la lista de objetos, la papelera y la ficha del punto (S025/RF-21 a RF-25). */
class FakeObjectLines(initial: List<ProjectLine> = emptyList()) : ObjectLines {
    val lines = MutableStateFlow(initial)
    val usedBy = mutableMapOf<String, List<String>>()

    private fun summary(line: ProjectLine) =
        LineSummary(line.id, line.name, line.code, line.closed, line.hidden, line.active, line.vertices.size)

    override fun summaries(projectId: String): Flow<List<LineSummary>> =
        lines.map { all -> all.filter { it.deletedAtMs == null }.map(::summary) }

    override fun lines(projectId: String): Flow<List<ProjectLine>> = lines.map { all -> all.filter { it.deletedAtMs == null } }

    override suspend fun setHidden(id: String, hidden: Boolean) {
        lines.value = lines.value.map { if (it.id == id) it.copy(hidden = hidden) else it }
    }

    override suspend fun linesUsing(pointIds: List<String>): List<String> = pointIds.flatMap { usedBy[it].orEmpty() }.distinct().sorted()

    override fun trash(projectId: String): Flow<List<TrashedLine>> =
        lines.map { all -> all.filter { it.deletedAtMs != null }.map { TrashedLine(it, 30) } }

    override suspend fun restore(id: String, newName: String?): LineSaveResult {
        val line = lines.value.first { it.id == id }
        val name = newName ?: line.name
        if (lines.value.any { it.id != id && it.deletedAtMs == null && it.name == name }) return LineSaveResult.DuplicateName
        lines.value = lines.value.map { if (it.id == id) it.copy(name = name, deletedAtMs = null) else it }
        return LineSaveResult.Saved(id)
    }

    override suspend fun deletePermanently(id: String) {
        lines.value = lines.value.filter { it.id != id }
    }

    override suspend fun suggestName(projectId: String, code: String?): String = "${code ?: "LINE"}_9"
}

/** Las líneas junto a los puntos en la lista de objetos, ocultarlas, el aviso al borrar un vértice y la papelera (S025/RF-21 a RF-25). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ObjectListLinesTest {

    @get:Rule
    val compose = createComposeRule()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val projectId = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Calle")).shouldBeInstanceOf<SaveResult.Saved>().id
    }
    private val bord = PointCode("BORD", "Bordillo", 0x1E88E5, CodeSymbol.CIRCLE)

    private fun line(id: String, name: String, closed: Boolean = false, code: PointCode? = bord, vararg points: String) = ProjectLine(
        id = id,
        projectId = projectId,
        name = name,
        code = code,
        geometry = if (closed) CodeGeometry.POLYGON else CodeGeometry.LINE,
        closed = closed,
        hidden = false,
        active = false,
        vertices = points.map { LineVertex(it) },
        createdAtMs = 1,
    )

    private val lines = FakeObjectLines(
        listOf(
            line("l1", "BORD_1", false, bord, "id-1", "id-2", "id-3"),
            line("l2", "PARC_1", true, PointCode("PARC", "", 0x43A047, CodeSymbol.CIRCLE), "id-1", "id-2", "id-3"),
        ),
    )
    private val repository = FakePointRepository(
        listOf(measured("1", projectId = projectId), measured("2", projectId = projectId), manual("E1", projectId = projectId)),
        clock = { 10_000 },
    )
    private var collector: kotlinx.coroutines.Job? = null
    private lateinit var vm: ObjectListViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        vm = ObjectListViewModel(repository, projects, ExportSelection(), lines = lines) { ProjectPositionCalculator() }
        collector = CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        vm.start(projectId)
    }

    @After
    fun tearDown() {
        collector?.cancel()
        Dispatchers.resetMain()
        db.close()
    }

    private fun await(predicate: (ObjectListUiState) -> Boolean): ObjectListUiState =
        runBlocking { withTimeout(5_000) { vm.state.first(predicate) } }

    @Test
    fun `S025 RF-21 la lista da las lineas junto a los puntos con su nombre, codigo, vertices y estado`() {
        val state = await { it.loaded && it.lines.isNotEmpty() }

        state.lines.map { it.name } shouldBe listOf("BORD_1", "PARC_1")
        state.lines.map { it.vertexCount } shouldBe listOf(3, 3)
        state.lines.map { it.closed } shouldBe listOf(false, true)
        state.lines.first().code?.code shouldBe "BORD"
        state.points.size shouldBe 3
    }

    @Test
    fun `S025 RF-21 la busqueda encuentra las lineas por nombre o por codigo`() {
        await { it.lines.size == 2 }

        vm.search("parc")
        await { it.query == "parc" }.lines.map { it.name } shouldBe listOf("PARC_1")
        vm.search("bordillo")
        await { it.query == "bordillo" }.lines shouldBe emptyList()
        vm.search("bord")
        await { it.query == "bord" }.lines.map { it.name } shouldBe listOf("BORD_1")
    }

    @Test
    fun `S025 RF-23 ocultar y mostrar una linea se recuerda y no toca los puntos`() {
        await { it.lines.size == 2 }

        vm.setLineHidden("l1", true)

        await { state -> state.lines.first { it.id == "l1" }.hidden }
        repository.points.value.none { it.hidden } shouldBe true
        vm.setLineHidden("l1", false)
        await { state -> !state.lines.first { it.id == "l1" }.hidden }
    }

    @Test
    fun `S025 RF-25 al seleccionar puntos que son vertices la lista sabe de que lineas`() {
        lines.usedBy["id-2"] = listOf("BORD_1", "PARC_1")
        await { it.loaded }

        vm.toggleSelection("id-2")

        await { it.selectedInLines == listOf("BORD_1", "PARC_1") }
        vm.toggleSelection("id-2")
        vm.toggleSelection("id-E1")
        await { it.selected == setOf("id-E1") && it.selectedInLines.isEmpty() }
    }

    // --- Pantalla -------------------------------------------------------------------------------------------------------------------

    private val calls = mutableListOf<String>()

    private fun show(state: ObjectListUiState) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ObjectListScreen(
                    ThemeState.DEFAULT,
                    {},
                    state,
                    ObjectListActions(
                        onOpenLine = { calls += "open:$it" },
                        onSetLineHidden = { id, hidden -> calls += "hidden:$id:$hidden" },
                        onDeleteSelected = { calls += "delete" },
                        onLineFromPoints = { calls += "wizard" },
                        onThreePointAngle = { calls += "angle" },
                        onCogo = { calls += "cogo" },
                        onStakeoutLine = { calls += "stakeout:$it" },
                    ),
                    onBack = {},
                )
            }
        }
    }

    private val uiState = ObjectListUiState(
        loaded = true,
        points = listOf(measured("2", createdAtMs = 2_000).summary()),
        total = 1,
        lines = listOf(
            LineSummary("l1", "BORD_1", bord, closed = false, hidden = false, active = true, vertexCount = 3),
            LineSummary("l2", "PARC_1", null, closed = true, hidden = true, active = false, vertexCount = 1),
        ),
    )

    @Test
    fun `S025 RF-21 cada linea dice si esta abierta o es poligono y si esta activa, con texto`() {
        show(uiState)

        compose.onNodeWithTag(objectLineRowTag("l1")).assertIsDisplayed()
        compose.onNodeWithText("Abierta · 3 vértices · Activa").assertExists()
        compose.onNodeWithText("Polígono · 1 vértice").assertExists()
        compose.onNodeWithTag(objectLineCodeTag("l1"), useUnmergedTree = true).assertExists()
    }

    @Test
    fun `S025 RF-22 tocar una linea abre su ficha y el ojo la oculta o la muestra`() {
        show(uiState)

        compose.onNodeWithTag(objectLineRowTag("l1")).performClick()
        compose.onNodeWithTag(objectLineVisibilityTag("l1")).performClick()
        compose.onNodeWithTag(objectLineVisibilityTag("l2")).performClick()

        calls shouldBe listOf("open:l1", "hidden:l1:true", "hidden:l2:false")
    }

    @Test
    fun `S025 RF-25 el aviso de borrar puntos nombra las lineas que los usan`() {
        show(uiState.copy(selected = setOf("id-2"), selectedInLines = listOf("BORD_1", "PARC_1")))

        compose.onNodeWithTag(SELECTION_DELETE_TAG).performClick()

        compose.onNode(hasText("BORD_1, PARC_1", substring = true)).assertExists()
        compose.onNodeWithTag(CONFIRM_TAG).performClick()
        calls shouldBe listOf("delete")
    }

    @Test
    fun `S025 RF-25 sin lineas que lo usen el aviso de borrar no las menciona`() {
        show(uiState.copy(selected = setOf("id-2")))

        compose.onNodeWithTag(SELECTION_DELETE_TAG).performClick()

        compose.onAllNodes(hasText("vértice en", substring = true)).fetchSemanticsNodes().size shouldBe 0
    }

    @Test
    fun `S025 RF-12 el menu de la lista ofrece Linea desde puntos`() {
        show(uiState)

        compose.onNodeWithTag(OBJECTS_MENU_TAG).performClick()
        compose.onNodeWithTag(OBJECTS_LINE_FROM_POINTS_TAG).performScrollTo().performClick()

        calls shouldBe listOf("wizard")
    }

    @Test
    fun `S026 RF-13 el menu de la lista ofrece Angulo entre tres puntos`() {
        show(uiState)

        compose.onNodeWithTag(OBJECTS_MENU_TAG).performClick()
        compose.onNodeWithTag(OBJECTS_ANGLE_TAG).performScrollTo().performClick()

        calls shouldBe listOf("angle")
    }

    @Test
    fun `S038 RF-1 el menu de la lista ofrece COGO`() {
        show(uiState)

        compose.onNodeWithTag(OBJECTS_MENU_TAG).performClick()
        compose.onNodeWithTag(OBJECTS_COGO_TAG).performScrollTo().performClick()

        calls shouldBe listOf("cogo")
    }

    @Test
    fun `S027 RF-1 el menu de la fila de una linea ofrece Replantear`() {
        show(uiState)

        compose.onNodeWithTag(objectLineMenuTag("l1")).performClick()
        compose.onNodeWithTag(objectLineStakeoutTag("l1")).performClick()

        calls shouldBe listOf("stakeout:l1")
    }
}
