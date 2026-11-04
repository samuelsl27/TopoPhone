package es.topophone.app.ui.lines

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.collector.RoomCollectorCodeSource
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeAttribute
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeLibrary
import es.topophone.core.model.codes.LibraryChoice
import es.topophone.core.model.codes.SurveyCode
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.PointSummary
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.codes.DefaultLibrarySource
import es.topophone.data.codes.ProjectLibraryLinker
import es.topophone.data.codes.RoomCodeLibraryRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.line.RoomLineRepository
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
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

/** El asistente «Línea desde puntos»: elegir en orden, ordenar, invertir, código, nombre y crear sin tocar los puntos (S025/RF-12 a RF-15). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class LineFromPointsTest {

    @get:Rule
    val compose = createComposeRule()

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val general = CodeLibrary(
        "default:general",
        "General",
        true,
        listOf(
            SurveyCode(
                "g1",
                "BORD",
                "Bordillo",
                CodeGeometry.LINE,
                0x1E88E5,
                listOf(CodeAttribute("Alto", AttributeType.NUMBER, "0.15")),
            ),
            SurveyCode("g2", "PARC", "Parcela", CodeGeometry.POLYGON, 0x43A047),
            SurveyCode("g3", "ARB", "Árbol", CodeGeometry.POINT, 0x689F38),
        ),
    )
    private val defaults = DefaultLibrarySource { listOf(general) }
    private val libraries = RoomCodeLibraryRepository(db, defaults)
    private val projects = RoomProjectRepository(db, libraries = ProjectLibraryLinker(db, defaults))
    private val points = RoomPointRepository(db)
    private val lines = RoomLineRepository(db, points)
    private val viewModels = mutableListOf<LineFromPointsViewModel>()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        db.close()
        Dispatchers.resetMain()
    }

    private val projectId: String = runBlocking {
        (
            projects.create(
                ProjectDefaults().toDraft().copy(name = "Calle Mayor 12", libraryChoice = LibraryChoice.Library("default:general")),
            ) as SaveResult.Saved
            ).id
    }

    private fun point(name: String) = Point(
        id = "id-$name",
        projectId = projectId,
        name = name,
        description = "",
        origin = PointOrigin.MANUAL,
        coordinates = PointCoordinates(GeodeticPosition(40.4, -3.7, null), 440_000.0 + name.hashCode() % 50, 4_472_000.0, null),
        measurement = null,
        typed = null,
        edited = false,
        hidden = false,
        copyOf = null,
        createdAtMs = 3_000,
        deletedAtMs = null,
    )

    private fun viewModel(vararg names: String): LineFromPointsViewModel {
        runBlocking { names.forEach { points.create(point(it)) } }
        val vm = LineFromPointsViewModel(points, lines, RoomCollectorCodeSource(libraries, points))
        viewModels += vm
        vm.start(projectId)
        await(vm) { it.loaded && it.points.size == names.size }
        return vm
    }

    private fun await(vm: LineFromPointsViewModel, predicate: (LineFromPointsUi) -> Boolean): LineFromPointsUi = runBlocking {
        try {
            withTimeout(10_000) { vm.state.first(predicate) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("El asistente no llegó al estado esperado; se quedó en ${vm.state.value}", e)
        }
    }

    private fun pick(vm: LineFromPointsViewModel, code: String) {
        await(vm) { ui -> ui.code.options.any { it.code == code } }
        vm.selectCode(vm.state.value.code.options.first { it.code == code })
        await(vm) { it.code.selected?.code == code }
    }

    private fun id(name: String) = "id-$name"

    @Test
    fun `S025 RF-12 los puntos se eligen en el orden en que se tocan y tocar otra vez los quita`() {
        val vm = viewModel("1", "2", "3")

        vm.toggle(id("3"))
        vm.toggle(id("1"))
        vm.toggle(id("2"))
        vm.toggle(id("1"))

        val ui = await(vm) { it.selected.size == 2 }
        ui.selected.map { it.name } shouldBe listOf("3", "2")
        ui.positionOf(id("2")) shouldBe 2
        ui.positionOf(id("1")) shouldBe null
    }

    @Test
    fun `S025 RF-12 ordenar por nombre respeta los numeros`() {
        val vm = viewModel("1", "2", "10", "3")
        listOf("10", "2", "3", "1").forEach { vm.toggle(id(it)) }

        vm.sortByName()

        await(vm) { it.selected.map { p -> p.name } == listOf("1", "2", "3", "10") }
    }

    @Test
    fun `S025 RF-13 invertir cambia el orden y quitar un punto lo saca de la lista`() {
        val vm = viewModel("1", "2", "3")
        listOf("1", "2", "3").forEach { vm.toggle(id(it)) }

        vm.reverse()
        await(vm) { it.selected.map { p -> p.name } == listOf("3", "2", "1") }
        vm.toggle(id("2"))

        await(vm) { it.selected.map { p -> p.name } == listOf("3", "1") }
    }

    @Test
    fun `S025 RF-13 un codigo de poligono propone la linea cerrada y el nombre del codigo`() {
        val vm = viewModel("1", "2", "3")

        pick(vm, "PARC")

        val ui = await(vm) { it.name == "PARC_1" }
        ui.closed shouldBe true
        vm.setClosed(false)
        pick(vm, "BORD")
        await(vm) { it.name == "BORD_1" }.closed shouldBe false
    }

    @Test
    fun `S025 RF-13 el selector solo ofrece codigos de linea y de poligono`() {
        val vm = viewModel("1", "2")

        await(vm) { it.code.totalCodes > 0 }.code.options.map { it.code } shouldBe listOf("BORD", "PARC")
    }

    @Test
    fun `S025 RF-14 al confirmar se crea la linea con los puntos en su orden, su codigo y atributos, sin modificar los puntos`() {
        val vm = viewModel("1", "2", "3")
        val before = runBlocking { points.points(projectId).first() }
        pick(vm, "BORD")
        listOf("3", "1", "2").forEach { vm.toggle(id(it)) }
        vm.setCodeAttribute(0, "0,2")

        vm.create()

        await(vm) { it.finished }
        val line = runBlocking { lines.lines(projectId).first() }.single()
        line.name shouldBe "BORD_1"
        line.pointIds shouldBe listOf(id("3"), id("1"), id("2"))
        line.code.shouldNotBeNull().attributes.single().value shouldBe "0.2"
        line.closed shouldBe false
        runBlocking { points.points(projectId).first() } shouldBe before
    }

    @Test
    fun `S025 RF-14 el nombre escrito sustituye al propuesto`() {
        val vm = viewModel("1", "2")
        listOf("1", "2").forEach { vm.toggle(id(it)) }

        vm.setName("Acera norte")
        pick(vm, "BORD")
        vm.create()

        await(vm) { it.finished }
        runBlocking { lines.lines(projectId).first() }.single().name shouldBe "Acera norte"
    }

    @Test
    fun `S025 RF-15 con menos puntos de los necesarios no se crea y se dice el motivo`() {
        val vm = viewModel("1", "2")
        vm.toggle(id("1"))

        vm.create()

        await(vm) { it.issue == LineWizardIssue.TOO_FEW_VERTICES }
        runBlocking { lines.lines(projectId).first() } shouldBe emptyList()
    }

    @Test
    fun `S025 RF-15 un poligono de 2 puntos no se crea`() {
        val vm = viewModel("1", "2")
        listOf("1", "2").forEach { vm.toggle(id(it)) }
        vm.setClosed(true)

        vm.create()

        await(vm) { it.issue == LineWizardIssue.TOO_FEW_VERTICES }
    }

    @Test
    fun `S025 RF-15 un nombre repetido o un atributo no valido no crean la linea`() {
        val vm = viewModel("1", "2")
        listOf("1", "2").forEach { vm.toggle(id(it)) }
        pick(vm, "BORD")
        vm.create()
        await(vm) { it.finished }
        runBlocking { lines.lines(projectId).first() }.map { it.name } shouldBe listOf("BORD_1")
        val again = LineFromPointsViewModel(points, lines, RoomCollectorCodeSource(libraries, points)).also { viewModels += it }
        again.start(projectId)
        await(again) { it.loaded && it.points.size == 2 }
        listOf("1", "2").forEach { again.toggle(id(it)) }
        pick(again, "BORD")
        again.setName("BORD_1")

        again.create()
        try {
            await(again) { it.issue == LineWizardIssue.NAME_TAKEN }
        } catch (e: AssertionError) {
            throw AssertionError("lineas ${runBlocking { lines.lines(projectId).first() }.map { it.name }}", e)
        }
        again.setName("otra")
        again.setCodeAttribute(0, "no es un numero")
        again.create()

        await(again) { it.issue == LineWizardIssue.ATTRIBUTES }
        runBlocking { lines.lines(projectId).first() }.size shouldBe 1
    }

    // --- Pantalla -------------------------------------------------------------------------------------------------------------------

    private fun summary(name: String) = PointSummary(
        id = id(name),
        name = name,
        description = "",
        origin = PointOrigin.MANUAL,
        latitudeDeg = 40.4,
        longitudeDeg = -3.7,
        hasHeight = false,
        outOfTolerance = false,
        edited = false,
        hidden = false,
        createdAtMs = 0,
    )

    private fun show(state: LineFromPointsUi, actions: LineFromPointsActions) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { LineFromPointsScreen(ThemeState.DEFAULT, {}, state, actions, onBack = {}) }
        }
    }

    @Test
    fun `S025 RF-12 la pantalla numera los puntos elegidos y tocar uno llama a elegirlo`() {
        var toggled: String? = null
        val state = LineFromPointsUi(
            loaded = true,
            points = listOf(summary("1"), summary("2")),
            selected = listOf(WizardPoint(id("2"), "2")),
            name = "BORD_1",
        )
        show(state, LineFromPointsActions(onToggle = { toggled = it }))

        compose.onNodeWithTag(LINE_WIZARD_SELECTED_TAG).assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(wizardPointTag("1")))
        compose.onNodeWithTag(wizardPointTag("1")).performClick()

        toggled shouldBe id("1")
    }

    @Test
    fun `S025 RF-15 la pantalla muestra el motivo cuando no se puede crear`() {
        val state = LineFromPointsUi(loaded = true, points = listOf(summary("1")), issue = LineWizardIssue.TOO_FEW_VERTICES)
        var created = 0
        show(state, LineFromPointsActions(onCreate = { created++ }))

        compose.onNodeWithTag(LINE_WIZARD_ISSUE_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(LINE_WIZARD_CREATE_TAG).performScrollTo().performClick()

        created shouldBe 1
    }
}
