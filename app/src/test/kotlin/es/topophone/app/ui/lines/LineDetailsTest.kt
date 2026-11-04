package es.topophone.app.ui.lines

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.LibraryChoice
import es.topophone.core.model.codes.PointAttribute
import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.codes.SurveyCode
import es.topophone.core.model.line.LineChange
import es.topophone.core.model.line.LineHistoryItem
import es.topophone.core.model.line.LineHistoryLine
import es.topophone.core.model.line.LineVertex
import es.topophone.core.model.line.ProjectLine
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.codes.DefaultLibrarySource
import es.topophone.data.codes.ProjectLibraryLinker
import es.topophone.data.codes.RoomCodeLibraryRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.line.LineSaveResult
import es.topophone.data.line.NewLine
import es.topophone.data.line.RoomLineRepository
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
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
import org.robolectric.annotation.Config

/** La ficha de una línea: vértices con su PK, edición con historial, cierre, atributos y cambio de código (S025/RF-10, RF-16 a RF-22). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class LineDetailsTest {

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
                listOf(CodeAttribute("Material", AttributeType.TEXT, "Granito")),
            ),
            SurveyCode(
                "g2",
                "TAPIA",
                "Tapia",
                CodeGeometry.POLYGON,
                0xD32F2F,
                listOf(CodeAttribute("Material", AttributeType.TEXT, "Ladrillo"), CodeAttribute("Grosor", AttributeType.NUMBER, "0.3")),
            ),
            SurveyCode("g3", "ARB", "Árbol", CodeGeometry.POINT, 0x689F38),
        ),
    )
    private val defaults = DefaultLibrarySource { listOf(general) }
    private val libraries = RoomCodeLibraryRepository(db, defaults)
    private val projects = RoomProjectRepository(db, libraries = ProjectLibraryLinker(db, defaults))
    private val points = RoomPointRepository(db)
    private val lines = RoomLineRepository(db, points)
    private val viewModels = mutableListOf<LineDetailsViewModel>()

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
    private val bord =
        PointCode("BORD", "Bordillo", 0x1E88E5, CodeSymbol.CIRCLE, listOf(PointAttribute("Material", AttributeType.TEXT, "Granito")))

    private fun point(name: String, easting: Double) = Point(
        id = "id-$name",
        projectId = projectId,
        name = name,
        description = "",
        origin = PointOrigin.MANUAL,
        coordinates = PointCoordinates(GeodeticPosition(40.4, -3.7, null), easting, 4_472_000.0, null),
        measurement = null,
        typed = null,
        edited = false,
        hidden = false,
        copyOf = null,
        createdAtMs = 3_000,
        deletedAtMs = null,
    )

    /** Cuatro puntos cada 10 m en Este y una línea abierta BORD_1 con los tres primeros. */
    private fun lineWith(vararg ids: String): String {
        runBlocking {
            listOf("1" to 0.0, "2" to 10.0, "3" to 30.0, "4" to 60.0).forEach { (n, e) -> points.create(point(n, 440_000.0 + e)) }
        }
        val result =
            runBlocking { lines.createFromPoints(NewLine(projectId, "BORD_1", bord, CodeGeometry.LINE, false, ids.map { "id-$it" })) }
        return (result as LineSaveResult.Saved).id
    }

    private fun viewModel(lineId: String): LineDetailsViewModel {
        val vm = LineDetailsViewModel(lines, points, projects, RoomCollectorCodeSource(libraries, points))
        viewModels += vm
        vm.start(projectId, lineId)
        CoroutineScopeForTest.launch { vm.state.collect {} }
        await(vm) { it.loaded && it.line != null }
        return vm
    }

    private object CoroutineScopeForTest : kotlinx.coroutines.CoroutineScope {
        override val coroutineContext = Dispatchers.Unconfined
    }

    private fun await(vm: LineDetailsViewModel, predicate: (LineDetailsUi) -> Boolean): LineDetailsUi = runBlocking {
        try {
            withTimeout(10_000) { vm.state.first(predicate) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("La ficha no llegó al estado esperado; se quedó en ${vm.state.value}", e)
        }
    }

    private fun awaitPicker(vm: LineDetailsViewModel) =
        runBlocking { withTimeout(10_000) { vm.changeCode.ui.first { it.picker.options.isNotEmpty() } } }.picker

    private fun names(vm: LineDetailsViewModel) = vm.state.value.vertices.map { it.name }

    @Test
    fun `S025 RF-16 la ficha lista los vertices con el nombre del punto y su PK desde el primero`() {
        val vm = viewModel(lineWith("1", "2", "3"))

        val ui = vm.state.value
        ui.vertices.map { it.name } shouldBe listOf("1", "2", "3")
        ui.vertices.map { it.chainageM ?: -1.0 }.let {
            it[0] shouldBe 0.0
            it[1] shouldBe (10.0 plusOrMinus 1e-9)
            it[2] shouldBe (30.0 plusOrMinus 1e-9)
        }
    }

    @Test
    fun `S025 RF-16 se anade un punto al final, entre dos vertices y se cambia el punto de un vertice`() {
        val vm = viewModel(lineWith("1", "2", "3"))

        vm.openPicker(VertexPick.Append)
        vm.pickPoint("id-4")
        await(vm) { names(vm) == listOf("1", "2", "3", "4") }
        vm.openPicker(VertexPick.Insert(1))
        vm.pickPoint("id-4")
        await(vm) { names(vm) == listOf("1", "4", "2", "3", "4") }
        vm.openPicker(VertexPick.Replace(0))
        vm.pickPoint("id-3")

        await(vm) { names(vm) == listOf("3", "4", "2", "3", "4") }
    }

    @Test
    fun `S025 RF-16 se quita un vertice, se mueve en el orden y se invierte el sentido`() {
        val vm = viewModel(lineWith("1", "2", "3"))

        vm.moveVertex(0, 2)
        await(vm) { names(vm) == listOf("2", "3", "1") }
        vm.reverse()
        await(vm) { names(vm) == listOf("1", "3", "2") }
        vm.removeVertex(1)

        await(vm) { names(vm) == listOf("1", "2") }
    }

    @Test
    fun `S025 RF-19 una edicion que deja menos de 2 vertices no se guarda y se dice el motivo`() {
        val vm = viewModel(lineWith("1", "2"))

        vm.removeVertex(0)

        await(vm) { it.issue == LineEditIssue.TOO_FEW_VERTICES }
        names(vm) shouldBe listOf("1", "2")
    }

    @Test
    fun `S025 RF-18 cada edicion deja en el historial su fecha y el valor anterior`() {
        val vm = viewModel(lineWith("1", "2", "3"))

        vm.removeVertex(2)

        val item = await(vm) {
            it.history.isNotEmpty() && names(vm) == listOf("1", "2") && it.history.single().lines.isNotEmpty()
        }.history.single()
        item.change shouldBe LineChange.VERTICES
        (item.lines.single() as LineHistoryLine.Vertices).removed shouldBe listOf("3")
    }

    @Test
    fun `S025 RF-17 editar los vertices no modifica los puntos`() {
        val id = lineWith("1", "2", "3")
        val before = runBlocking { points.points(projectId).first() }
        val vm = viewModel(id)

        vm.removeVertex(1)
        await(vm) { names(vm) == listOf("1", "3") }

        runBlocking { points.points(projectId).first() } shouldBe before
    }

    @Test
    fun `S025 RF-17 si cambia un punto la linea lo refleja al momento`() {
        val vm = viewModel(lineWith("1", "2", "3"))
        val moved = runBlocking { points.point("id-2").first() }.shouldNotBeNull()

        runBlocking {
            points.update(
                moved.copy(coordinates = moved.coordinates.copy(easting = 440_020.0)),
                es.topophone.core.model.point.PointChange.EDIT,
            )
        }

        await(vm) { ui -> ui.vertices.getOrNull(1)?.chainageM?.let { kotlin.math.abs(it - 20.0) < 1e-6 } == true }
    }

    @Test
    fun `S025 RF-10 cerrar una linea de 3 vertices la convierte en poligono y abrirla la devuelve`() {
        val vm = viewModel(lineWith("1", "2", "3"))

        vm.toggleClosed()
        await(vm) { it.line?.closed == true }
        vm.toggleClosed()

        await(vm) { it.line?.closed == false && it.history.size == 2 }
    }

    @Test
    fun `S025 RF-11 una linea de 2 vertices no se cierra y se dice el motivo`() {
        val vm = viewModel(lineWith("1", "2"))

        vm.toggleClosed()

        await(vm) { it.issue == LineEditIssue.TOO_FEW_VERTICES }.line?.closed shouldBe false
    }

    @Test
    fun `S025 RF-18 se cambia el nombre y uno repetido se rechaza`() {
        val id = lineWith("1", "2", "3")
        runBlocking { lines.createFromPoints(NewLine(projectId, "OTRA", bord, CodeGeometry.LINE, false, listOf("id-3", "id-4"))) }
        val vm = viewModel(id)

        vm.startRename()
        vm.setRename("OTRA")
        vm.confirmRename()
        await(vm) { it.issue == LineEditIssue.NAME_TAKEN }
        vm.setRename("ACERA")
        vm.confirmRename()

        await(vm) { it.line?.name == "ACERA" && it.renaming == null }
    }

    @Test
    fun `S025 RF-18 los valores de los atributos se corrigen con su historial y uno no valido no se guarda`() {
        val vm = viewModel(lineWith("1", "2", "3"))

        vm.startAttributes()
        vm.setAttribute(0, "Hormigon")
        vm.saveAttributes()

        val ui = await(vm) { it.line?.code?.attributes?.first()?.value == "Hormigon" && it.history.isNotEmpty() }
        ui.history.single().change shouldBe LineChange.ATTRIBUTES
    }

    @Test
    fun `S025 RF-20 cambiar el codigo conserva los atributos del mismo nombre y solo ofrece linea y poligono`() {
        val vm = viewModel(lineWith("1", "2", "3"))

        vm.openChangeCode()
        val picker = awaitPicker(vm)
        picker.options.map { it.code } shouldBe listOf("BORD", "TAPIA")
        vm.changeCode.choose(picker.options.first { it.code == "TAPIA" })

        val ui = await(vm) { it.line?.code?.code == "TAPIA" }
        ui.line?.geometry shouldBe CodeGeometry.POLYGON
        ui.line?.code?.attributes?.map { it.value } shouldBe listOf("Granito", "0.3")
        await(vm) { it.history.isNotEmpty() }.history.single().change shouldBe LineChange.CODE
    }

    @Test
    fun `S025 RF-20 al cambiar a un codigo sin ese atributo avisa y solo cambia al confirmar`() {
        val vm = viewModel(lineWith("1", "2", "3"))
        runBlocking { lines.changeCode(vm.state.value.line!!.id, general.codes[1]) }
        await(vm) { it.line?.code?.code == "TAPIA" }

        vm.openChangeCode()
        vm.changeCode.choose(awaitPicker(vm).options.first { it.code == "BORD" })
        val warning = runBlocking { withTimeout(10_000) { vm.changeCode.ui.first { it.warning != null } } }.warning.shouldNotBeNull()
        warning.map { it.attribute } shouldBe listOf("Grosor")
        vm.state.value.line?.code?.code shouldBe "TAPIA"
        vm.changeCode.confirm()

        await(vm) { it.line?.code?.code == "BORD" }
    }

    // --- Pantalla -------------------------------------------------------------------------------------------------------------------

    private val line = ProjectLine(
        id = "l1",
        projectId = "p1",
        name = "BORD_1",
        code = bord,
        geometry = CodeGeometry.LINE,
        closed = false,
        hidden = false,
        active = true,
        vertices = listOf(LineVertex("a"), LineVertex("b")),
        createdAtMs = 0,
    )
    private val ui = LineDetailsUi(
        loaded = true,
        line = line,
        vertices = listOf(VertexRow(0, "a", "1", 0.0), VertexRow(1, "b", "2", 12.5)),
    )

    private fun show(state: LineDetailsUi, actions: LineDetailsActions) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { LineDetailsScreen(ThemeState.DEFAULT, {}, state, actions, onBack = {}) }
        }
    }

    @Test
    fun `S025 RF-22 la ficha dice si es abierta o poligono y si esta activa, con texto`() {
        show(ui, LineDetailsActions())

        compose.onNodeWithTag(LINE_STATE_TAG).assertIsDisplayed()
        compose.onNodeWithText("Abierta · Activa").assertExists()
    }

    @Test
    fun `S025 RF-22 un poligono se llama poligono`() {
        show(ui.copy(line = line.copy(closed = true, active = false)), LineDetailsActions())

        compose.onNodeWithText("Polígono").assertExists()
    }

    @Test
    fun `S025 RF-16 cada vertice enseña su PK y sus acciones llaman a la ficha`() {
        var removed = -1
        var moved = -1 to -1
        show(ui, LineDetailsActions(onRemoveVertex = { removed = it }, onMoveVertex = { a, b -> moved = a to b }))

        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(lineVertexMenuTag(1)))
        compose.onNodeWithTag(lineVertexMenuTag(1)).performClick()
        compose.onNodeWithTag(lineVertexUpTag(1)).performClick()
        compose.onNodeWithTag(lineVertexMenuTag(1)).performClick()
        compose.onNodeWithTag(lineVertexRemoveTag(1)).performClick()

        moved shouldBe (1 to 0)
        removed shouldBe 1
        compose.onNodeWithText("2. 2 · PK 0+012,50").assertExists()
    }

    @Test
    fun `S025 RF-16 los botones de cerrar, invertir y anadir llaman a la ficha`() {
        var closed = 0
        var reversed = 0
        var added = 0
        show(ui, LineDetailsActions(onToggleClosed = { closed++ }, onReverse = { reversed++ }, onAddVertex = { added++ }))

        compose.onNodeWithTag(LINE_TOGGLE_CLOSED_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(LINE_REVERSE_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(LINE_ADD_VERTEX_TAG).performScrollTo().performClick()

        listOf(closed, reversed, added) shouldBe listOf(1, 1, 1)
    }

    @Test
    fun `S025 RF-18 el historial muestra la fecha y los valores anteriores`() {
        val item = LineHistoryItem(
            1_792_346_639_419,
            LineChange.VERTICES,
            listOf(LineHistoryLine.Vertices(3, 2, removed = listOf("3"), added = emptyList())),
        )
        show(ui.copy(history = listOf(item)), LineDetailsActions())

        compose.onNodeWithTag(LINE_HISTORY_BUTTON_TAG).performScrollTo().performClick()

        compose.onNode(hasTestTag(LINE_HISTORY_TAG)).assertExists()
        compose.onNodeWithText("Vértices: 3 → 2. Quitados: 3").assertExists()
    }

    @Test
    fun `S025 RF-16 el selector de puntos lista los candidatos y elegir uno llama a la ficha`() {
        var picked: String? = null
        val state = ui.copy(pick = VertexPick.Append, candidates = listOf(WizardPoint("a", "1"), WizardPoint("c", "3")))
        show(state, LineDetailsActions(onPickPoint = { picked = it }))

        compose.onNodeWithTag(linePickerPointTag("3")).performClick()

        picked shouldBe "c"
    }

    @Test
    fun `S025 RF-22 la ficha muestra el codigo con sus atributos`() {
        show(ui, LineDetailsActions())
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(LINE_CODE_TAG))

        compose.onNodeWithTag(LINE_CODE_TAG).assertIsDisplayed()
        compose.onNodeWithText("Material: Granito").assertExists()
    }
}
