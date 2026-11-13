package es.topophone.app.ui.map

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.projects.ProjectViewScreen
import es.topophone.app.ui.projects.ProjectViewUiState
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.geodesy.GeoPoint
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
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.wms.ProjectLayerView
import io.kotest.matchers.shouldBe
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private val AREA = VisibleArea(south = 40.0, west = -4.0, north = 41.0, east = -3.0, widthPx = 1080, heightPx = 1800)
private val PRESS = MapPress.of(40.4167754, -3.7037902, 540f, 900f, AREA)
private val PROJECT = Project(
    id = "p1",
    name = "Calle Mayor 12",
    author = "",
    description = "",
    crs = ProjectCrs.ETRS89_UTM30,
    linearUnit = LinearUnit.METRE,
    angularUnit = AngularUnit.DMS,
    correctionsDatum = CorrectionsDatum.ETRS89,
    folderId = null,
    createdAtMs = 0L,
    modifiedAtMs = 0L,
    openedAtMs = null,
    deletedAtMs = null,
)

private fun queryableLayer(id: String): ProjectLayerView {
    val service = WmsService(
        id = "s1",
        name = "Servicio",
        address = "https://s1.es/wms",
        title = "S",
        version = "1.3.0",
        attribution = "",
        presetKey = null,
        requestMode = WmsRequestMode.TILES,
        mapFormats = emptyList(),
        infoFormats = emptyList(),
        createdAtMs = 0L,
        updatedAtMs = 0L,
    )
    val layer = ProjectMapLayer(id, "p1", "s1", "capa", 0, true, 100)
    return ProjectLayerView(layer, service, WmsLayerInfo(0, 0, "capa", "Capa", "", true, true, "EPSG:3857"), showService = false)
}

private fun menu(
    screen: MapMenuScreen = MapMenuScreen.MENU,
    visible: Int = 2,
    queryable: Int = 1,
    results: List<LayerInfoResult> = emptyList(),
    coordinates: MapCoordinates? = null,
) = MapMenuUiState(
    press = PRESS,
    screen = screen,
    visibleLayers = visible,
    queryable = List(queryable) { queryableLayer("q$it") },
    results = results,
    coordinates = coordinates,
)

/** El menú del mapa: las opciones, el desactivado con su motivo, la información de las capas, las coordenadas y copiar (S036/RF-34 a RF-41). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class MapMenuSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = MapMenuActions(
        onQuery = { calls += "query" },
        onCoordinates = { calls += "coordinates" },
        onBack = { calls += "back" },
        onClose = { calls += "close" },
        onCopy = { calls += "copy:$it" },
    )
    private val calculator = ProjectPositionCalculator()

    private fun show(state: MapMenuUiState, project: Project? = PROJECT) {
        compose.setContent { TopoPhoneTheme(EffectiveTheme.LIGHT) { MapMenuContent(state, project, actions) } }
    }

    private fun results(vararg statuses: LayerInfoStatus) =
        statuses.mapIndexed { i, status -> LayerInfoResult("l$i", "Capa $i", "Servicio $i", status) }

    @Test
    fun `S036 RF-34 el menu ofrece consultar las capas y ver coordenadas en zonas de 56 dp`() {
        show(menu())

        compose.onNodeWithText("Punto marcado").assertIsDisplayed()
        compose.onNodeWithTag(MAP_MENU_QUERY_TAG).assertTextContains("Consultar información de las capas").assertIsEnabled()
            .assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithTag(MAP_MENU_COORDINATES_TAG).assertTextContains("Ver coordenadas").assertIsEnabled()
            .assertHeightIsAtLeast(56.dp).performClick()

        calls shouldBe listOf("query", "coordinates")
        compose.onNodeWithTag(MAP_MENU_REASON_TAG).assertDoesNotExist()
    }

    @Test
    fun `S036 RF-37 sin ninguna capa visible Consultar queda desactivado y dice por que`() {
        show(menu(visible = 0, queryable = 0))

        compose.onNodeWithTag(MAP_MENU_QUERY_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(MAP_MENU_REASON_TAG).assertTextContains("No hay ninguna capa visible.")
        compose.onNodeWithTag(MAP_MENU_COORDINATES_TAG).assertIsEnabled()
    }

    @Test
    fun `S036 RF-37 con capas visibles pero ninguna consultable lo dice`() {
        show(menu(visible = 3, queryable = 0))

        compose.onNodeWithTag(MAP_MENU_QUERY_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(MAP_MENU_REASON_TAG).assertTextContains("Ninguna capa visible se puede consultar.")
    }

    @Test
    fun `S036 RF-40 sin el SC de un proyecto Ver coordenadas no se puede elegir`() {
        show(menu(), project = null)

        compose.onNodeWithTag(MAP_MENU_COORDINATES_TAG).assertIsNotEnabled()
    }

    @Test
    fun `S036 RF-36 la informacion muestra por cada capa su nombre, el de su servicio y su respuesta`() {
        show(
            menu(
                MapMenuScreen.INFO,
                results = results(LayerInfoStatus.Text("Parcela: 123"), LayerInfoStatus.NoData, LayerInfoStatus.Loading),
            ),
        )

        compose.onNodeWithTag(
            mapMenuLayerTag("l0"),
        ).assertTextContains("Capa 0").assertTextContains("Servicio 0").assertTextContains("Parcela: 123")
        compose.onNodeWithTag(mapMenuLayerTag("l1")).assertTextContains("Sin datos en este punto.")
        compose.onNodeWithTag(mapMenuLayerTag("l2")).assertTextContains("Consultando…")
    }

    @Test
    fun `S036 RF-39 cada motivo de fallo de una capa tiene su texto y las demas siguen a la vista`() {
        val failures = listOf(
            LayerInfoStatus.Failed(LayerInfoProblem.TIMEOUT) to "El servicio no ha respondido en 20 s.",
            LayerInfoStatus.Failed(LayerInfoProblem.NO_RESPONSE) to "El servicio no responde.",
            LayerInfoStatus.Failed(LayerInfoProblem.HTTP_ERROR, "503") to "El servicio ha contestado con un error (HTTP 503).",
            LayerInfoStatus.Failed(LayerInfoProblem.SERVICE_EXCEPTION, "Capa no valida") to
                "El servicio ha devuelto un error: Capa no valida",
            LayerInfoStatus.Failed(LayerInfoProblem.SERVICE_EXCEPTION) to "El servicio ha devuelto una excepción.",
            LayerInfoStatus.Failed(LayerInfoProblem.NO_ADDRESS) to "No se puede consultar este servicio.",
        )
        show(menu(MapMenuScreen.INFO, results = results(LayerInfoStatus.Text("Hay datos"), *failures.map { it.first }.toTypedArray())))

        compose.onNodeWithTag(mapMenuLayerTag("l0")).assertTextContains("Hay datos")
        failures.forEachIndexed { i, (_, text) -> compose.onNodeWithTag(mapMenuLayerTag("l${i + 1}")).assertTextContains(text) }
    }

    @Test
    fun `S036 RF-36 volver desde la informacion de las capas llega a quien lo atiende`() {
        show(menu(MapMenuScreen.INFO, results = results(LayerInfoStatus.NoData)))

        compose.onNodeWithTag(MAP_MENU_BACK_TAG).assertHeightIsAtLeast(56.dp).performClick()

        calls shouldBe listOf("back")
    }

    @Test
    fun `S036 RF-40 las coordenadas dan Este, Norte, latitud y longitud y Copiar deja esas mismas lineas`() {
        val coordinates = MapCoordinates.of(PRESS, PROJECT, calculator)
        show(menu(MapMenuScreen.COORDINATES, coordinates = coordinates))
        val lines = coordinates.lines(PROJECT, "m", Locale("es", "ES"))

        lines.forEach { line ->
            compose.onNodeWithTag(mapMenuCoordinateTag(line.kind)).assertTextContains(line.value)
        }
        compose.onNodeWithTag(mapMenuCoordinateTag(CoordinateKind.EASTING)).assertTextContains("Este (E)")
        compose.onNodeWithTag(mapMenuCoordinateTag(CoordinateKind.LATITUDE)).assertTextContains("Latitud (φ)")
        compose.onNodeWithTag(MAP_MENU_COPY_TAG).assertTextContains("Copiar").assertHeightIsAtLeast(56.dp).performClick()

        val names = mapOf(
            CoordinateKind.EASTING to "Este (E)",
            CoordinateKind.NORTHING to "Norte (N)",
            CoordinateKind.LATITUDE to "Latitud (φ)",
            CoordinateKind.LONGITUDE to "Longitud (λ)",
        )
        calls shouldBe listOf("copy:" + MapCoordinates.copyText(lines) { names.getValue(it) })
        compose.onNodeWithTag(MAP_MENU_OUTSIDE_TAG).assertDoesNotExist()
    }

    @Test
    fun `S036 RF-41 fuera del ambito del SC solo salen latitud y longitud con el motivo`() {
        val outside = MapCoordinates.of(MapPress.of(28.1, -15.4, 10f, 10f, AREA), PROJECT, calculator)
        show(menu(MapMenuScreen.COORDINATES, coordinates = outside))

        compose.onNodeWithTag(mapMenuCoordinateTag(CoordinateKind.EASTING)).assertDoesNotExist()
        compose.onNodeWithTag(mapMenuCoordinateTag(CoordinateKind.NORTHING)).assertDoesNotExist()
        compose.onNodeWithTag(mapMenuCoordinateTag(CoordinateKind.LATITUDE)).assertIsDisplayed()
        compose.onNodeWithTag(mapMenuCoordinateTag(CoordinateKind.LONGITUDE)).assertIsDisplayed()
        compose.onNodeWithTag(
            MAP_MENU_OUTSIDE_TAG,
        ).assertTextContains("Fuera del ámbito del sistema de coordenadas del proyecto.", substring = true)
    }

    @Test
    fun `S036 RF-40 volver desde las coordenadas llega a quien lo atiende`() {
        show(menu(MapMenuScreen.COORDINATES, coordinates = MapCoordinates.of(PRESS, PROJECT, calculator)))

        compose.onNodeWithTag(MAP_MENU_BACK_TAG).performClick()

        calls shouldBe listOf("back")
    }
}

/** La pulsación larga en la vista de proyecto: abre el menú, marca el punto y, al cerrarlo, quita la marca (S036/RF-34, RF-35). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ProjectMapMenuTest {

    @get:Rule
    val compose = createComposeRule()

    private val map = FakeMapController()
    private val calls = mutableListOf<String>()
    private val menuState = mutableStateOf(MapMenuUiState())
    private val actions = MapMenuActions(onLongPress = { calls += "press:${it.latitudeDeg}" }, onClose = { calls += "close" })

    private fun show() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectViewScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectViewUiState(PROJECT),
                    onBack = {},
                    map = fakeMap(map),
                    mapMenu = menuState.value,
                    mapMenuActions = actions,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `S036 RF-34 una pulsacion larga en el mapa llega a quien abre el menu`() {
        show()

        map.longPress(PRESS)
        compose.waitForIdle()

        calls shouldBe listOf("press:${PRESS.latitudeDeg}")
    }

    @Test
    fun `S036 RF-34 con el menu abierto el mapa marca el punto y salen las opciones`() {
        menuState.value = menu()
        show()

        map.pressMarker shouldBe GeoPoint(PRESS.latitudeDeg, PRESS.longitudeDeg)
        compose.onNodeWithTag(MAP_MENU_QUERY_TAG).assertIsDisplayed()
        compose.onNodeWithTag(MAP_MENU_COORDINATES_TAG).assertIsDisplayed()
    }

    @Test
    fun `S036 RF-35 al cerrar el menu la marca del punto se quita`() {
        menuState.value = menu()
        show()
        map.pressMarker shouldBe GeoPoint(PRESS.latitudeDeg, PRESS.longitudeDeg)

        menuState.value = MapMenuUiState()
        compose.waitForIdle()

        map.pressMarker shouldBe null
        compose.onNodeWithTag(MAP_MENU_QUERY_TAG).assertDoesNotExist()
    }

    @Test
    fun `S036 RF-35 descartar la hoja pide cerrar el menu`() {
        menuState.value = menu()
        show()

        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).performSemanticsAction(SemanticsActions.Dismiss)
        compose.waitForIdle()

        calls shouldBe listOf("close")
    }
}
