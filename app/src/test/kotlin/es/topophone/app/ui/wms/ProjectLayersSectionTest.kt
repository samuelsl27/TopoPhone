package es.topophone.app.ui.wms

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.FakeMapController
import es.topophone.app.ui.map.fakeMap
import es.topophone.app.ui.projects.LAYERS_BUTTON_TAG
import es.topophone.app.ui.projects.ProjectViewScreen
import es.topophone.app.ui.projects.ProjectViewUiState
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.wms.ProjectMapLayer
import es.topophone.core.model.wms.WmsLayerInfo
import es.topophone.core.model.wms.WmsRequestMode
import es.topophone.core.model.wms.WmsService
import es.topophone.data.wms.ProjectLayerView
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Las capas del proyecto en la hoja «Capas»: visibilidad, opacidad, orden, quitar, no disponible, tope de 10 y zonas de 56 dp (S036/RF-18 a RF-23, RF-26 a RF-28). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ProjectLayersSectionTest {

    @get:Rule
    val compose = createComposeRule()

    private val current = mutableStateOf(ProjectLayersUiState())
    private val calls = mutableListOf<String>()
    private val actions = ProjectLayersActions(
        onVisible = { id, visible -> calls += "visible:$id:$visible" },
        onOpacity = { id, percent -> calls += "opacity:$id:$percent" },
        onMoveUp = { calls += "up:$it" },
        onMoveDown = { calls += "down:$it" },
        onRemove = { calls += "remove:$it" },
    )

    private fun service(id: String, name: String) = WmsService(
        id = id,
        name = name,
        address = "https://$id.es/wms",
        title = name,
        version = "1.3.0",
        attribution = "",
        presetKey = null,
        requestMode = WmsRequestMode.TILES,
        mapFormats = listOf("image/png"),
        infoFormats = emptyList(),
        createdAtMs = 0L,
        updatedAtMs = 0L,
    )

    private fun view(
        id: String,
        title: String,
        position: Int,
        visible: Boolean = true,
        opacity: Int = 100,
        available: Boolean = true,
        showService: Boolean = false,
        service: WmsService = service("s1", "Servicio A"),
    ): ProjectLayerView {
        val layer = ProjectMapLayer(id, "p1", service.id, title, position, visible, opacity)
        val info = if (available) WmsLayerInfo(0, 0, title, title, "", true, true, "EPSG:3857") else null
        return ProjectLayerView(layer, service, info, showService)
    }

    private fun show(layers: List<ProjectLayerView>, onAddLayer: () -> Unit = {}, onAddService: () -> Unit = {}) {
        current.value = ProjectLayersUiState(layers)
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                // Como en la hoja «Capas», que se desplaza: los nodos fuera de la vista miden menos de lo que son.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ProjectLayersSection(current.value, actions, onAddLayer, onAddService)
                }
            }
        }
    }

    private val three = listOf(view("a", "Ortofoto", 0), view("b", "Límites", 1, opacity = 45), view("c", "Catastro", 2, visible = false))

    @Test
    fun `S036 RF-18 sin capas dice que no hay y deja anadir una capa o un servicio`() {
        val opened = mutableListOf<String>()
        show(emptyList(), onAddLayer = { opened += "layer" }, onAddService = { opened += "service" })

        compose.onNodeWithTag(PROJECT_LAYERS_EMPTY_TAG).assertIsDisplayed()
        compose.onNodeWithTag(PROJECT_LAYERS_ADD_LAYER_TAG).assertIsEnabled().assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithTag(PROJECT_LAYERS_ADD_SERVICE_TAG).assertHeightIsAtLeast(56.dp).performClick()

        opened shouldBe listOf("layer", "service")
    }

    @Test
    fun `S036 RF-20 cada capa muestra si es visible y se marca o se desmarca sin quitarla`() {
        show(three)

        compose.onNodeWithTag(projectLayerVisibleTag("a")).assertIsOn().assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithTag(projectLayerVisibleTag("c")).performScrollTo().assertIsOff().performClick()

        calls shouldBe listOf("visible:a:false", "visible:c:true")
    }

    @Test
    fun `S036 RF-21 la opacidad se ve en por ciento y al soltar llega de 5 en 5`() {
        show(three)

        compose.onNodeWithTag(projectLayerOpacityValueTag("a")).assertTextContains("Opacidad: 100 %")
        compose.onNodeWithTag(projectLayerOpacityValueTag("b")).assertTextContains("Opacidad: 45 %")
        compose.onNodeWithTag(projectLayerOpacityZoneTag("b")).performScrollTo().assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(projectLayerOpacityTag("b"))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(30f) }

        calls shouldBe listOf("opacity:b:30")
        compose.onNodeWithTag(projectLayerOpacityValueTag("b")).assertTextContains("Opacidad: 30 %")
    }

    @Test
    fun `S036 RF-22 subir y bajar llegan a quien los atiende y la primera no sube ni la ultima baja`() {
        show(three)

        compose.onNodeWithTag(projectLayerUpTag("a")).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(projectLayerDownTag("c")).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(projectLayerUpTag("b")).performScrollTo().assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithTag(projectLayerDownTag("b")).performScrollTo().assertHeightIsAtLeast(56.dp).performClick()

        calls shouldBe listOf("up:b", "down:b")
    }

    @Test
    fun `S036 RF-22 arrastrar el asa hacia abajo baja la capa y hacia arriba la sube`() {
        show(three)

        compose.onNodeWithTag(projectLayerDragTag("b")).performScrollTo().performTouchInput {
            down(center)
            moveBy(Offset(0f, DRAG_PX))
            up()
        }
        val afterDown = calls.toList()
        compose.onNodeWithTag(projectLayerDragTag("b")).performScrollTo().performTouchInput {
            down(center)
            moveBy(Offset(0f, -DRAG_PX))
            up()
        }

        afterDown.isNotEmpty() shouldBe true
        afterDown.all { it == "down:b" } shouldBe true
        calls.drop(afterDown.size).let { up -> up.isNotEmpty() && up.all { it == "up:b" } } shouldBe true
    }

    @Test
    fun `S036 RF-23 quitar una capa llega a quien lo atiende`() {
        show(three)

        compose.onNodeWithTag(projectLayerRemoveTag("c")).performScrollTo().assertHeightIsAtLeast(56.dp).performClick()

        calls shouldBe listOf("remove:c")
    }

    @Test
    fun `S036 RF-27 una capa que el servicio ya no ofrece sale como no disponible y se puede quitar`() {
        show(listOf(view("a", "Ortofoto", 0, available = false), view("b", "Límites", 1)))

        compose.onNodeWithTag(projectLayerUnavailableTag("a")).assertTextContains("No disponible: el servicio ya no ofrece esta capa.")
        compose.onNodeWithTag(projectLayerUnavailableTag("b")).assertDoesNotExist()
        compose.onNodeWithTag(projectLayerRemoveTag("a")).performClick()

        calls shouldBe listOf("remove:a")
    }

    @Test
    fun `S036 RF-28 con el mismo titulo en dos servicios cada capa lleva el nombre del suyo`() {
        val a = service("s1", "Servicio A")
        val b = service("s2", "Servicio B")
        show(listOf(view("a", "Ortofoto", 0, showService = true, service = a), view("b", "Ortofoto", 1, showService = true, service = b)))

        compose.onNodeWithText("Servicio: Servicio A").assertExists()
        compose.onNodeWithText("Servicio: Servicio B").assertExists()
    }

    @Test
    fun `S036 RF-26 con 10 capas no se puede anadir otra y se dice por que`() {
        show((0 until 10).map { view("l$it", "Capa $it", it) })

        compose.onNodeWithTag(PROJECT_LAYERS_ADD_LAYER_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(PROJECT_LAYERS_FULL_TAG).assertTextContains("Un proyecto admite hasta 10 capas.")
        compose.onNodeWithTag(PROJECT_LAYERS_ADD_SERVICE_TAG).assertIsEnabled()
    }

    @Test
    fun `S036 RF-26 con menos de 10 capas no sale el aviso del tope`() {
        show(three)

        compose.onNodeWithTag(PROJECT_LAYERS_FULL_TAG).assertDoesNotExist()
        compose.onNodeWithTag(PROJECT_LAYERS_ADD_LAYER_TAG).assertIsEnabled()
    }

    private companion object {
        const val DRAG_PX = 1500f
    }
}

/** La sección dentro de la hoja «Capas» de la vista de proyecto y la biblioteca que se abre desde ella (S036/RF-18, RF-19). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ProjectLayersSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val project = Project(
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

    private val service =
        WmsService("s1", "Servicio A", "https://s1.es/wms", "A", "1.3.0", "", null, WmsRequestMode.TILES, emptyList(), emptyList(), 0L, 0L)
    private val layers = listOf(
        ProjectLayerView(
            ProjectMapLayer("a", "p1", "s1", "ortofoto", 0, true, 100),
            service,
            WmsLayerInfo(0, 0, "ortofoto", "Ortofoto", "", true, true, "EPSG:3857"),
            false,
        ),
    )

    private fun show(layersState: ProjectLayersUiState, library: (WmsLayerPicker?) -> Unit) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectViewScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectViewUiState(project),
                    onBack = {},
                    map = fakeMap(FakeMapController()),
                    projectLayers = layersState,
                    librarySheet = { pick, _ -> library(pick) },
                )
            }
        }
        compose.onNodeWithTag(LAYERS_BUTTON_TAG).performClick()
    }

    @Test
    fun `S036 RF-18 la hoja Capas muestra las capas del proyecto con sus controles`() {
        show(ProjectLayersUiState(layers)) {}

        compose.onNodeWithTag(PROJECT_LAYERS_SECTION_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(projectLayerVisibleTag("a")).performScrollTo().assertTextContains("Ortofoto")
        compose.onNodeWithTag(PROJECT_LAYERS_ADD_LAYER_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(PROJECT_LAYERS_ADD_SERVICE_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S036 RF-19 Anadir capa abre la biblioteca para elegir con las capas que ya tiene y su tope`() {
        var picked: WmsLayerPicker? = null
        show(ProjectLayersUiState(layers)) { picked = it }

        compose.onNodeWithTag(PROJECT_LAYERS_ADD_LAYER_TAG).performScrollTo().performClick()
        compose.waitForIdle()

        val picker = picked.shouldNotBeNull()
        picker.inProject shouldBe setOf("s1" to "ortofoto")
        picker.full shouldBe false
        picker.message shouldBe null
    }

    @Test
    fun `S036 RF-1 Anadir servicio abre la biblioteca sin elegir capa`() {
        val opened = mutableListOf<WmsLayerPicker?>()
        show(ProjectLayersUiState(layers)) { opened += it }

        compose.onNodeWithTag(PROJECT_LAYERS_ADD_SERVICE_TAG).performScrollTo().performClick()
        compose.waitForIdle()

        opened shouldBe listOf(null)
    }
}
