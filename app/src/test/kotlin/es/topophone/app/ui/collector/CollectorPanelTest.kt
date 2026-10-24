package es.topophone.app.ui.collector

import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.FakeMapController
import es.topophone.app.ui.map.fakeMap
import es.topophone.app.ui.projects.POSITION_PANEL_TAG
import es.topophone.app.ui.projects.PROJECT_MAP_TAG
import es.topophone.app.ui.projects.ProjectViewScreen
import es.topophone.app.ui.projects.ProjectViewUiState
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Panel del recolector sobre el mapa (S010/RF-1, RF-3 a RF-7, RF-9 a RF-12, RF-19). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class CollectorPanelTest {

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

    private val ready = CollectorUiState(
        loaded = true,
        name = "P12",
        description = "arqueta",
        poleHeightText = "1,800",
        live = LiveQuality(SolutionStatus.FIX, 0.010, 0.015),
    )

    private var view: View? = null
    private val calls = mutableListOf<String>()
    private val actions = CollectorActions(
        onName = { calls += "name:$it" },
        onDescription = { calls += "description:$it" },
        onPoleHeight = { calls += "pole:$it" },
        onMode = { calls += "mode:$it" },
        onSeconds = { calls += "seconds:$it" },
        onMeasure = { calls += "measure" },
        onCancel = { calls += "cancel" },
    )

    private fun show(state: CollectorUiState, open: Boolean = true, map: FakeMapController = FakeMapController()) {
        compose.setContent {
            view = LocalView.current
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectViewScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectViewUiState(project),
                    onBack = {},
                    map = fakeMap(map),
                    collector = state,
                    collectorActions = actions,
                )
            }
        }
        if (open) compose.onNodeWithTag(COLLECT_BUTTON_TAG).performClick()
    }

    @Test
    fun `S010 RF-1 Recolectar abre el recolector en lugar del panel de posicion, con el mapa a la vista`() {
        show(ready, open = false)
        compose.onNodeWithTag(POSITION_PANEL_TAG).assertIsDisplayed()

        compose.onNodeWithTag(COLLECT_BUTTON_TAG).performClick()

        compose.onNodeWithTag(COLLECTOR_PANEL_TAG).assertIsDisplayed()
        compose.onNodeWithTag(POSITION_PANEL_TAG).assertDoesNotExist()
        compose.onNodeWithTag(PROJECT_MAP_TAG).assertIsDisplayed()

        compose.onNodeWithTag(COLLECTOR_CLOSE_TAG).performClick()
        compose.onNodeWithTag(POSITION_PANEL_TAG).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "+w360dp-h640dp")
    fun `S010 RF-1 el mapa deja libre lo que tapa el recolector, para que el rover quede a la vista`() {
        val map = FakeMapController()
        show(ready, open = false, map = map)
        compose.waitForIdle()
        val mapBounds = compose.onNodeWithTag(PROJECT_MAP_TAG).fetchSemanticsNode().boundsInRoot
        val positionPanel = compose.onNodeWithTag(POSITION_PANEL_TAG).fetchSemanticsNode().boundsInRoot
        map.bottomInsetPx.toFloat() shouldBeGreaterThanOrEqual mapBounds.bottom - positionPanel.top

        compose.onNodeWithTag(COLLECT_BUTTON_TAG).performClick()
        compose.waitForIdle()

        val panel = compose.onNodeWithTag(COLLECTOR_PANEL_TAG).fetchSemanticsNode().boundsInRoot
        map.bottomInsetPx.toFloat() shouldBeGreaterThanOrEqual mapBounds.bottom - panel.top
        map.bottomInsetPx.toFloat() shouldBeLessThan mapBounds.height
    }

    @Test
    @Config(qualifiers = "+w360dp-h640dp")
    fun `S010 RF-6 y RF-8 en un movil pequeno la solucion y Medir se ven sin desplazar el panel`() {
        show(ready)

        compose.onNodeWithTag(COLLECTOR_LIVE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COLLECTOR_MEASURE_TAG).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "+w360dp-h640dp")
    fun `S010 RF-9 y RF-11 en un movil pequeno el progreso y Cancelar se ven sin desplazar el panel`() {
        show(ready.copy(settings = CollectorSettings(MeasurementMode.AVERAGED), measuring = true, progress = MeasureProgress(2.0, 5.0)))

        compose.onNodeWithText("Tiempo 0:02 de 0:05").assertIsDisplayed() // S017/RF-1
        compose.onNodeWithTag(COLLECTOR_CANCEL_TAG).assertIsDisplayed()
    }

    @Test
    fun `S010 RF-4 y RF-5 nombre, descripcion y jalon se ven y se cambian`() {
        show(ready)

        compose.onNodeWithTag(COLLECTOR_NAME_TAG).performScrollTo().assert(hasText("P12")).performTextReplacement("P13")
        compose.onNodeWithTag(COLLECTOR_DESCRIPTION_TAG).performScrollTo().assert(hasText("arqueta")).performTextReplacement("pozo")
        compose.onNodeWithTag(COLLECTOR_POLE_TAG).performScrollTo().assert(hasText("1,800")).performTextReplacement("2,05")

        // El estado de esta prueba no cambia: basta con que lleguen los valores nuevos.
        calls shouldContainAll listOf("name:P13", "description:pozo", "pole:2,05")
    }

    @Test
    fun `S010 RF-6 el recolector da la solucion y las precisiones H y V`() {
        show(ready)

        compose.onNodeWithTag(COLLECTOR_LIVE_TAG)
            .assert(hasText("Fija", substring = true))
            .assert(hasText("H 0,010 m", substring = true))
            .assert(hasText("V 0,015 m", substring = true))
    }

    @Test
    fun `S010 RF-7 la pantalla no se apaga mientras el recolector esta abierto`() {
        show(ready)
        compose.waitForIdle()
        view?.keepScreenOn shouldBe true

        compose.onNodeWithTag(COLLECTOR_CLOSE_TAG).performClick()
        compose.waitForIdle()
        view?.keepScreenOn shouldBe false
    }

    @Test
    fun `S010 RF-8 y RF-9 se elige el modo y se mide`() {
        show(ready)

        compose.onNodeWithTag(modeOptionTag(MeasurementMode.AVERAGED)).performScrollTo().performClick()
        compose.onNodeWithTag(COLLECTOR_MEASURE_TAG).assertIsEnabled().performClick()

        calls shouldBe listOf("mode:AVERAGED", "measure")
    }

    @Test
    fun `S010 RF-9 y RF-11 durante el promediado se ve el progreso y se puede cancelar`() {
        show(ready.copy(settings = CollectorSettings(MeasurementMode.AVERAGED), measuring = true, progress = MeasureProgress(2.0, 5.0)))

        compose.onNodeWithText("Tiempo 0:02 de 0:05").assertIsDisplayed() // S017/RF-1
        compose.onNodeWithTag(COLLECTOR_MEASURE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(COLLECTOR_CANCEL_TAG).performClick()

        calls shouldBe listOf("cancel")
    }

    @Test
    fun `S010 RF-9 los segundos se pueden vaciar y, fuera de 1 a 60, se dice el rango y no se mide`() {
        show(
            ready.copy(
                settings = CollectorSettings(MeasurementMode.AVERAGED),
                averagingSecondsText = "",
                averagingSecondsValid = false,
                blocker = MeasureBlocker.INVALID_SECONDS,
            ),
        )

        compose.onNodeWithText("De 0:01 a 60:00.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(COLLECTOR_MEASURE_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(COLLECTOR_SECONDS_TAG).performScrollTo().assert(hasText("")).performTextReplacement("20")

        calls shouldContainAll listOf("seconds:20")
    }

    @Test
    fun `S010 RF-10 si se pierde el FIX se avisa`() {
        show(ready.copy(measuring = true, progress = MeasureProgress(1.0, 5.0), solutionLost = true))

        compose.onNodeWithText("Solución perdida: no cuentan las épocas sin FIX.").assertIsDisplayed()
    }

    @Test
    fun `S010 RF-12 sin posicion no se puede medir y se dice por que`() {
        show(ready.copy(live = null, blocker = MeasureBlocker.NO_POSITION))

        compose.onNodeWithTag(COLLECTOR_MEASURE_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(COLLECTOR_BLOCKER_TAG).assert(hasText("Sin posición del receptor."))
    }

    @Test
    fun `S010 RF-3 con el nombre repetido se dice y no se puede medir`() {
        show(ready.copy(blocker = MeasureBlocker.NAME_TAKEN))

        compose.onNodeWithText("Ya hay un punto con ese nombre.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(COLLECTOR_MEASURE_TAG).assertIsNotEnabled()
    }

    @Test
    fun `S010 RF-12 una medida cancelada por falta de datos lo dice`() {
        show(ready.copy(error = CollectorError.NO_DATA))

        compose.onNodeWithText("Sin datos del receptor: la medida se ha cancelado.").assertIsDisplayed()
    }

    @Test
    fun `S010 RF-19 tras guardar se dice que punto se ha guardado`() {
        show(ready.copy(name = "P13", savedName = "P12"))

        compose.onNodeWithText("Punto P12 guardado.").assertIsDisplayed()
    }
}
