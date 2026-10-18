package es.topophone.app.ui.points

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.FakeMapController
import es.topophone.app.ui.map.MapPoints
import es.topophone.app.ui.map.fakeMap
import es.topophone.app.ui.points.PointFixtures.manual
import es.topophone.app.ui.points.PointFixtures.measured
import es.topophone.app.ui.projects.CONFIRM_TAG
import es.topophone.app.ui.projects.ProjectViewScreen
import es.topophone.app.ui.projects.ProjectViewUiState
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.point.PrecisionFailure
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Pantalla de la lista de objetos y botón "Objetos" del mapa (S011/RF-1 a RF-4, RF-22). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ObjectListScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = ObjectListActions(
        onQueryChange = { calls += "query:$it" },
        onOpen = { calls += "open:$it" },
        onToggleSelection = { calls += "select:$it" },
        onClearSelection = { calls += "clear" },
        onSetHidden = { id, hidden -> calls += "hidden:$id:$hidden" },
        onHideSelected = { calls += "hideSelected" },
        onShowSelected = { calls += "showSelected" },
        onDeleteSelected = { calls += "deleteSelected" },
        onCorrectPole = { calls += "pole:$it" },
        onDismissPoleOutcome = { calls += "dismissPole" },
    )

    private val summaries = listOf(
        manual("E1", createdAtMs = 3_000).summary(),
        measured("2", description = "pozo", createdAtMs = 2_000, failures = setOf(PrecisionFailure.NOT_FIX)).summary(),
        measured("1", createdAtMs = 1_000).copy(edited = true, hidden = true).summary(),
    )

    private fun show(state: ObjectListUiState) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { ObjectListScreen(ThemeState.DEFAULT, {}, state, actions, onBack = {}) }
        }
    }

    @Test
    fun `S011 RF-1 cada punto muestra su nombre, su descripcion y si es medido o manual`() {
        show(ObjectListUiState(loaded = true, points = summaries, total = 3))

        compose.onNodeWithTag(objectRowTag("id-E1")).assert(hasText("E1", substring = true)).assert(hasText("esquina", substring = true))
            .assert(hasText("Manual", substring = true))
        compose.onNodeWithTag(objectRowTag("id-2")).assert(hasText("pozo", substring = true)).assert(hasText("Medido", substring = true))
    }

    @Test
    fun `S011 RF-3 las marcas de fuera de tolerancia, editado y sin altura llevan texto`() {
        show(ObjectListUiState(loaded = true, points = summaries, total = 3))

        compose.onNodeWithTag(objectRowTag("id-E1")).assert(hasText("Sin altura", substring = true))
        compose.onNodeWithTag(objectRowTag("id-2")).assert(hasText("Fuera de tolerancia", substring = true))
        compose.onNodeWithTag(objectRowTag("id-1")).assert(hasText("Editado", substring = true))
    }

    @Test
    fun `S011 RF-1 sin puntos la lista lo dice`() {
        show(ObjectListUiState(loaded = true))

        compose.onNodeWithText("Todavía no hay puntos.").assertIsDisplayed()
    }

    @Test
    fun `S011 RF-2 se escribe en el buscador y sin resultados se dice`() {
        show(ObjectListUiState(loaded = true, query = "farola", total = 3))

        compose.onNodeWithText("Ningún punto contiene «farola».").assertIsDisplayed()
        compose.onNodeWithTag(OBJECT_SEARCH_TAG).performTextReplacement("farolas")
        calls.last() shouldBe "query:farolas"
    }

    @Test
    fun `S011 RF-4 el ojo de cada punto lo oculta o lo muestra en el mapa`() {
        show(ObjectListUiState(loaded = true, points = summaries, total = 3))

        compose.onNodeWithTag(objectVisibilityTag("id-2")).assert(hasContentDescription("Ocultar 2 en el mapa")).performClick()
        compose.onNodeWithTag(objectVisibilityTag("id-1")).assert(hasContentDescription("Mostrar 1 en el mapa")).performClick()

        calls shouldBe listOf("hidden:id-2:true", "hidden:id-1:false")
    }

    @Test
    fun `S011 RF-4 y RF-5 una pulsacion larga selecciona y fuera de la seleccion pulsar abre el punto`() {
        show(ObjectListUiState(loaded = true, points = summaries, total = 3))

        compose.onNodeWithTag(objectRowTag("id-2")).performTouchInput { longClick() }
        compose.onNodeWithTag(objectRowTag("id-1")).performClick()

        calls shouldBe listOf("select:id-2", "open:id-1")
    }

    @Test
    fun `S011 RF-4 y RF-22 con seleccion se ocultan, se muestran o se borran tras confirmar`() {
        show(ObjectListUiState(loaded = true, points = summaries, total = 3, selected = setOf("id-1", "id-2")))

        compose.onNodeWithTag(SELECTION_BAR_TAG).assertIsDisplayed()
        compose.onNodeWithText("2 seleccionados").assertIsDisplayed()
        compose.onNodeWithTag(objectRowTag("id-E1")).performClick()
        compose.onNodeWithTag(SELECTION_HIDE_TAG).performClick()
        compose.onNodeWithTag(SELECTION_SHOW_TAG).performClick()
        compose.onNodeWithTag(SELECTION_DELETE_TAG).performClick()
        compose.onNodeWithText("¿Mover 2 puntos a la papelera? Se pueden recuperar durante 30 días.").assertIsDisplayed()
        compose.onNodeWithTag(CONFIRM_TAG).performClick()
        compose.onNodeWithTag(SELECTION_CANCEL_TAG).performClick()

        calls shouldBe listOf("select:id-E1", "hideSelected", "showSelected", "deleteSelected", "clear")
    }

    @Test
    fun `S011 RF-14 la altura del jalon de la seleccion se escribe en metros y no vale fuera de 0 a 10 m`() {
        show(ObjectListUiState(loaded = true, points = summaries, total = 3, selected = setOf("id-1", "id-2")))

        compose.onNodeWithTag(SELECTION_POLE_TAG).performClick()
        compose.onNodeWithTag(POLE_FIELD_TAG).performTextReplacement("12")
        compose.onNodeWithText("Escribe una altura entre 0 y 10 m.").assertIsDisplayed()
        compose.onNodeWithTag(POLE_APPLY_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(POLE_FIELD_TAG).performTextReplacement("2,05")
        compose.onNodeWithTag(POLE_APPLY_TAG).performClick()

        calls shouldBe listOf("pole:2.05")
    }

    @Test
    fun `S011 RF-14 y RF-15 tras corregir el jalon se dice cuantos han cambiado, cuantos manuales no y si hay editados`() {
        show(ObjectListUiState(loaded = true, points = summaries, total = 3, poleOutcome = PoleOutcome(2, 1, keptEdited = true)))

        compose.onNodeWithText("Jalón corregido en 2 puntos.").assertIsDisplayed()
        compose.onNodeWithText("1 punto manual no tiene jalón y no ha cambiado.").assertIsDisplayed()
        compose.onNodeWithText("Las coordenadas editadas se mantienen; se ha recalculado la medida.").assertIsDisplayed()
        compose.onNodeWithTag(POLE_OUTCOME_DISMISS_TAG).performClick()

        calls shouldBe listOf("dismissPole")
    }

    @Test
    fun `S011 RF-1 el boton Objetos del mapa abre la lista del proyecto`() {
        var opened: String? = null
        val project = Project(
            id = "p1",
            name = "Calle Mayor 12",
            author = "",
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
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectViewScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectViewUiState(project),
                    onBack = {},
                    map = fakeMap(FakeMapController()),
                    onObjects = { opened = it },
                )
            }
        }

        compose.onNodeWithTag(OBJECTS_BUTTON_TAG).assert(hasContentDescription("Objetos del proyecto")).performClick()

        opened shouldBe "p1"
    }

    @Test
    fun `S011 RF-4 los puntos ocultos no van al mapa`() {
        MapPoints.from(summaries).map { it.id } shouldBe listOf("id-E1", "id-2")
    }
}
