package es.topophone.app.ui.cogo

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.TypedCoordinates
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.survey.cogo.CogoPoint
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.PointSaveResult
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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

/** Elegir un punto de entrada de la lista o escribir su Este y su Norte, y los campos numéricos (S038/RF-3, RF-4, RF-5). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class CogoPointFieldTest {

    @get:Rule
    val compose = createComposeRule()

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db)
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Unconfined)
    private val inputs = CogoInputs(points, scope)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        scope.cancel()
        runBlocking { job.join() }
        db.close()
        Dispatchers.resetMain()
    }

    private val projectId: String = runBlocking {
        (projects.create(ProjectDefaults().toDraft().copy(name = "Parcela")) as SaveResult.Saved).id
    }

    private fun point(name: String, east: Double, north: Double, height: Double?) = Point(
        id = "id-$name",
        projectId = projectId,
        name = name,
        description = "",
        origin = PointOrigin.MANUAL,
        coordinates = PointCoordinates(GeodeticPosition(40.4, -3.7, height?.plus(50.0)), east, north, height),
        measurement = null,
        typed = TypedCoordinates(EntryForm.PROJECT_CRS, east, north, height, HeightKind.PROJECT),
        edited = false,
        hidden = false,
        copyOf = null,
        createdAtMs = 1_000,
        deletedAtMs = null,
    )

    private fun save(vararg all: Point) = all.forEach { runBlocking { points.create(it) } shouldBe PointSaveResult.Saved }

    /** Los valores cuando ya están resueltos los de [slots] (los puntos del proyecto se leen de la base, en otro hilo). */
    private fun values(unit: LinearUnit = LinearUnit.METRE, vararg slots: String) = runBlocking {
        withTimeout(5_000) { inputs.values(flowOf(unit)).first { map -> slots.all { it in map } } }
    }

    private fun found(slot: String, unit: LinearUnit = LinearUnit.METRE) =
        values(unit, slot).getValue(slot).shouldBeInstanceOf<CogoSlotValue.Found>()

    // --- Elegir de la lista ---------------------------------------------------------------------------------------------------------

    @Test
    fun `S038 RF-3 un punto del proyecto elegido sale con sus coordenadas y su altura`() {
        save(point("P1", 440_000.5, 4_474_000.25, 650.75))

        inputs.open("a")
        inputs.chooseProject("id-P1")

        val value = found("a")
        value.name shouldBe "P1"
        value.point.easting shouldBe 440_000.5
        value.point.northing shouldBe 4_474_000.25
        value.point.height shouldBe 650.75
        value.point.latitudeDeg shouldBe 40.4
    }

    @Test
    fun `S038 RF-3 un punto sin altura sale sin altura`() {
        save(point("P2", 1.0, 2.0, null))

        inputs.open("a")
        inputs.chooseProject("id-P2")

        found("a").point.height shouldBe null
    }

    @Test
    fun `S038 RF-3 el selector lista los puntos del proyecto y filtra por la busqueda sin distinguir mayusculas ni acentos`() {
        save(point("Esquina", 1.0, 2.0, null), point("ARQUETA", 3.0, 4.0, 1.0), point("P3", 5.0, 6.0, 2.0))

        inputs.open("a")
        val all = runBlocking { withTimeout(5_000) { inputs.picker(flowOf(projectId)).first { it.candidates.size == 3 } } }
        inputs.setQuery("arqu")
        val filtered = runBlocking { withTimeout(5_000) { inputs.picker(flowOf(projectId)).first { it.query == "arqu" } } }

        all.slot shouldBe "a"
        filtered.candidates.map { it.name } shouldBe listOf("ARQUETA")
        filtered.candidates.single().hasHeight shouldBe true
    }

    @Test
    fun `S038 RF-3 el selector cerrado no lee ningun punto`() {
        save(point("P1", 1.0, 2.0, null))

        runBlocking { withTimeout(5_000) { inputs.picker(flowOf(projectId)).first() } } shouldBe CogoPickerUi()
    }

    @Test
    fun `S038 RF-27 editar el punto del proyecto cambia el valor de la entrada`() {
        save(point("P1", 440_000.0, 4_474_000.0, 650.0))
        inputs.open("a")
        inputs.chooseProject("id-P1")

        runBlocking { points.update(point("P1", 440_010.0, 4_474_000.0, 650.0), es.topophone.core.model.point.PointChange.EDIT) }

        found("a").point.easting shouldBe 440_010.0
    }

    @Test
    fun `S038 RF-28 un punto borrado deja de estar disponible y se dice cual`() {
        save(point("P1", 440_000.0, 4_474_000.0, 650.0))
        inputs.open("a")
        inputs.chooseProject("id-P1")

        runBlocking { points.moveToTrash(listOf("id-P1")) }

        values(LinearUnit.METRE, "a").getValue("a") shouldBe CogoSlotValue.Missing("P1")
    }

    @Test
    fun `S038 RF-9 dos entradas se intercambian`() {
        save(point("P1", 1.0, 1.0, null), point("P2", 2.0, 2.0, null))
        inputs.open("a")
        inputs.chooseProject("id-P1")
        inputs.open("b")
        inputs.chooseProject("id-P2")

        values(LinearUnit.METRE, "a", "b")
        inputs.swap("a", "b")

        found("a").name shouldBe "P2"
        found("b").name shouldBe "P1"
    }

    // --- Escribir Este y Norte ------------------------------------------------------------------------------------------------------

    @Test
    fun `S038 RF-4 un Este y un Norte escritos con coma decimal dan un punto sin nombre ni altura`() {
        inputs.setTyped("a", "440036,125", "4474048,5")

        val value = found("a")

        value.name shouldBe null
        value.point shouldBe CogoPoint(440_036.125, 4_474_048.5)
    }

    @Test
    fun `S038 RF-4 un Este o un Norte que no es un numero deja la entrada incompleta`() {
        inputs.setTyped("a", "abc", "4474048,5")
        values(LinearUnit.METRE, "a").getValue("a") shouldBe CogoSlotValue.Incomplete

        inputs.setTyped("a", "440036", "")
        values(LinearUnit.METRE, "a").getValue("a") shouldBe CogoSlotValue.Incomplete
    }

    @Test
    fun `S038 RF-4 lo escrito en pies se pasa a metros`() {
        inputs.setTyped("a", "1000", "2000")

        val value = found("a", LinearUnit.INTERNATIONAL_FOOT)

        value.point.easting shouldBe (304.8 plusOrMinus 1e-9)
        value.point.northing shouldBe (609.6 plusOrMinus 1e-9)
    }

    // --- Pantalla --------------------------------------------------------------------------------------------------------------------

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { TopoPhoneTheme(EffectiveTheme.LIGHT) { content() } }
    }

    @Test
    fun `S038 RF-3 el campo muestra el nombre, el Este, el Norte y la altura, y abre el selector`() {
        val picked = mutableListOf<String>()
        show {
            CogoPointField("a", "A", CogoSlotValue.Found(CogoPoint(440_000.5, 4_474_000.25, 650.75), "P1"), LinearUnit.METRE) {
                picked += it
            }
        }

        compose.onNodeWithTag(cogoPointTag("a")).assertIsDisplayed().assertHeightIsAtLeast(56.dp)
            .assertTextContains("A: P1", substring = true)
            .assertTextContains("440.000,500", substring = true)
            .assertTextContains("650,750", substring = true)
            .performClick()

        picked shouldBe listOf("a")
    }

    @Test
    fun `S038 RF-3 sin altura el campo lo dice con texto`() {
        show { CogoPointField("a", "A", CogoSlotValue.Found(CogoPoint(1.0, 2.0), null), LinearUnit.METRE) {} }

        compose.onNodeWithTag(cogoPointTag("a")).assertTextContains("Sin altura", substring = true)
            .assertTextContains("coordenadas escritas", substring = true)
    }

    @Test
    fun `S038 RF-28 el campo con un punto que ya no esta lo dice`() {
        show { CogoPointField("a", "A", CogoSlotValue.Missing("P1"), LinearUnit.METRE) {} }

        compose.onNodeWithTag(cogoPointTag("a")).assertTextContains("ya no está en el proyecto", substring = true)
    }

    @Test
    fun `S038 RF-3 el selector lista los puntos, busca y elige, o devuelve lo escrito`() {
        val calls = mutableListOf<String>()
        show {
            var query by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
            CogoPickerDialog(
                CogoPickerUi("a", query, listOf(CogoCandidate("p1", "P1", true), CogoCandidate("p2", "P2", false))),
                label = "A",
                typed = null,
                onQuery = {
                    query = it
                    calls += "query:$it"
                },
                onChoose = { calls += "choose:$it" },
                onTyped = { slot, e, n -> calls += "typed:$slot:$e:$n" },
                onClose = {},
            )
        }

        compose.onNodeWithTag(cogoPickerPointTag("P2")).performClick()
        compose.onNodeWithTag(COGO_PICKER_QUERY_TAG).performTextInput("P")
        compose.onNodeWithTag(COGO_TYPED_EASTING_TAG).performTextInput("440036,1")
        compose.onNodeWithTag(COGO_TYPED_NORTHING_TAG).performTextInput("4474048,5")
        compose.onNodeWithTag(COGO_TYPED_USE_TAG).performScrollTo().performClick()

        calls shouldBe listOf("choose:p2", "query:P", "typed:a:440036,1:4474048,5")
    }

    @Test
    fun `S038 RF-4 un numero no valido se dice con texto y uno valido no muestra error`() {
        val changes = mutableListOf<String>()
        show {
            androidx.compose.foundation.layout.Column {
                CogoNumberField("distance", "Distancia", "12x", invalid = true, onChange = { changes += it })
                CogoNumberField("azimuth", "Azimut", "45", invalid = false, onChange = {})
            }
        }

        compose.onNodeWithTag(cogoNumberErrorTag("distance"), useUnmergedTree = true).assertExists().assertTextContains("Número no válido")
        compose.onNodeWithTag(cogoNumberTag("distance")).assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(cogoNumberTag("distance")).performTextInput("3")
        compose.onAllNodesCount(cogoNumberErrorTag("azimuth")) shouldBe 0
        changes.single().contains("3") shouldBe true
    }

    @Test
    fun `S038 RF-5 el selector de modo muestra cual esta activo y avisa del cambio`() {
        val modes = mutableListOf<DistanceMode>()
        show { CogoModeSelector(DistanceMode.GRID) { modes += it } }

        compose.onNodeWithTag(es.topophone.app.ui.geometry.GEOMETRY_ACTIVE_MODE_TAG).assertTextContains("uadr", substring = true)
        compose.onNodeWithTag(es.topophone.app.ui.geometry.GEOMETRY_MODE_GROUND_TAG).performClick()

        modes shouldBe listOf(DistanceMode.GROUND)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesCount(tag: String) =
        onAllNodesWithTag(tag).fetchSemanticsNodes().size
}
