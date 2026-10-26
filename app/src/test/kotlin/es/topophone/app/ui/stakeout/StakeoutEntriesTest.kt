package es.topophone.app.ui.stakeout

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.collector.COLLECTOR_PANEL_TAG
import es.topophone.app.ui.collector.COLLECT_BUTTON_TAG
import es.topophone.app.ui.collector.CollectorUiState
import es.topophone.app.ui.map.FakeMapController
import es.topophone.app.ui.map.fakeMap
import es.topophone.app.ui.points.ObjectListActions
import es.topophone.app.ui.points.ObjectListScreen
import es.topophone.app.ui.points.ObjectListUiState
import es.topophone.app.ui.points.POINT_STAKEOUT_TAG
import es.topophone.app.ui.points.PointDetailsActions
import es.topophone.app.ui.points.PointDetailsScreen
import es.topophone.app.ui.points.PointDetailsUiState
import es.topophone.app.ui.points.PointFixtures
import es.topophone.app.ui.points.SELECTION_STAKEOUT_TAG
import es.topophone.app.ui.projects.ProjectViewScreen
import es.topophone.app.ui.projects.ProjectViewUiState
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.PointSummary
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.Guidance
import es.topophone.core.survey.HeadingResult
import es.topophone.core.survey.StakeZone
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Las tres entradas al replanteo —la ficha de un punto (también la que se abre desde el mapa), la selección de la lista de
 * objetos— y la vista de proyecto con el panel puesto en lugar del recolector (S018/RF-1, RNF-T2).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class StakeoutEntriesTest {

    @get:Rule
    val compose = createComposeRule()

    private val project = Project(
        id = "p1",
        name = "Calle Mayor 12",
        author = "",
        description = "",
        crs = ProjectCrs.ETRS89_UTM30,
        linearUnit = LinearUnit.METRE,
        angularUnit = AngularUnit.DECIMAL_DEGREES,
        correctionsDatum = CorrectionsDatum.ETRS89,
        folderId = null,
        createdAtMs = 1_000,
        modifiedAtMs = 1_000,
        openedAtMs = null,
        deletedAtMs = null,
    )

    private fun summary(id: String, name: String) = PointSummary(
        id = id,
        name = name,
        description = "",
        origin = PointOrigin.MEASURED,
        latitudeDeg = 40.4168,
        longitudeDeg = -3.7038,
        hasHeight = true,
        outOfTolerance = false,
        edited = false,
        hidden = false,
        createdAtMs = 1_000,
    )

    private val calls = mutableListOf<String>()

    private fun showList(selected: Set<String>, query: String = "") = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) {
            ObjectListScreen(
                ThemeState.DEFAULT,
                {},
                ObjectListUiState(
                    loaded = true,
                    query = query,
                    points = listOf(summary("a", "1"), summary("b", "2"), summary("c", "10")),
                    total = 3,
                    selected = selected,
                ),
                ObjectListActions(onStakeout = { id -> calls += "stakeout:$id" }),
                {},
            )
        }
    }

    @Test
    fun `S018 RF-1 con un punto elegido en la lista la barra ofrece Replantear con ese punto`() {
        showList(selected = setOf("b"))

        compose.onNodeWithTag(SELECTION_STAKEOUT_TAG).assertIsDisplayed().performClick()

        calls shouldBe listOf("stakeout:b")
    }

    @Test
    fun `S018 RF-1 con varios puntos elegidos no se ofrece Replantear`() {
        showList(selected = setOf("a", "b"))

        compose.onNodeWithTag(SELECTION_STAKEOUT_TAG).assertDoesNotExist()
    }

    @Test
    fun `S018 RF-1 sin seleccion no hay barra ni Replantear`() {
        showList(selected = emptySet())

        compose.onNodeWithTag(SELECTION_STAKEOUT_TAG).assertDoesNotExist()
    }

    @Test
    fun `S018 RF-1 la ficha de un punto ofrece Replantear`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                PointDetailsScreen(
                    ThemeState.DEFAULT,
                    {},
                    PointDetailsUiState(project, PointFixtures.measured("12")),
                    PointDetailsActions(onStakeout = { calls += "stakeout" }),
                    {},
                )
            }
        }

        compose.onNodeWithTag(POINT_STAKEOUT_TAG).performScrollTo().assertIsDisplayed().performClick()

        calls shouldBe listOf("stakeout")
    }

    private val design = StakeTarget(
        "d1",
        "12",
        "esquina",
        PointCoordinates(GeodeticPosition(40.4168, -3.0, 699.4), 440_290.458, 4_474_257.382, 648.19),
    )

    private val stakeoutState = StakeoutUiState(
        project = project,
        target = design,
        guidance = GuidanceState.On(
            GuidanceUi(
                guidance = Guidance(5.0, 3.0, 4.0, 0.6, 4.0, 3.0, null),
                zone = StakeZone.FAR,
                heading = HeadingResult.Available(0.0),
                tipEastM = -3.0,
                tipNorthM = -4.0,
                dianaRadiusM = 0.2,
                solution = SolutionStatus.FIX,
                horizontalAccuracyM = 0.01,
                tipUncompensated = false,
            ),
        ),
    )

    private fun showProject(stakeout: StakeoutUiState?) = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) {
            ProjectViewScreen(
                ThemeState.DEFAULT,
                {},
                ProjectViewUiState(project),
                onBack = {},
                map = fakeMap(FakeMapController()),
                collector = CollectorUiState(loaded = true),
                stakeout = stakeout,
                stakeoutActions = StakeoutActions(onClose = { calls += "close" }),
            )
        }
    }

    @Test
    fun `S018 RF-1 con el replanteo abierto el panel del replanteo sustituye al recolector y a su boton`() {
        showProject(stakeoutState)

        compose.onNodeWithTag(STAKEOUT_PANEL_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COLLECTOR_PANEL_TAG).assertDoesNotExist()
        compose.onNodeWithTag(COLLECT_BUTTON_TAG).assertDoesNotExist()
    }

    @Test
    fun `S018 RF-1 cerrar el panel del replanteo avisa`() {
        showProject(stakeoutState)

        compose.onNodeWithTag(STAKEOUT_CLOSE_TAG).performClick()

        calls shouldBe listOf("close")
    }

    @Test
    fun `S018 RF-1 sin replanteo la vista de proyecto sigue con su boton Recolectar`() {
        showProject(null)

        compose.onNodeWithTag(COLLECT_BUTTON_TAG).assertIsDisplayed()
        compose.onNodeWithTag(STAKEOUT_PANEL_TAG).assertDoesNotExist()
    }

    private fun showLineProject(line: LineStakeoutUiState?) = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) {
            ProjectViewScreen(
                ThemeState.DEFAULT,
                {},
                ProjectViewUiState(project),
                onBack = {},
                map = fakeMap(FakeMapController()),
                collector = CollectorUiState(loaded = true),
                lineStakeout = line,
                lineStakeoutActions = LineStakeoutActions(onClose = { calls += "line-close" }),
            )
        }
    }

    private val lineState = LineStakeoutUiState(
        project = project,
        element = LineElementInfo("l1", "Eje", "calle.dxf", CadEntityKind.POLYLINE, 90.0, false, null, null),
    )

    @Test
    fun `S041 RF-2 con el replanteo de una linea abierto su panel sustituye al recolector y a su boton`() {
        showLineProject(lineState)

        compose.onNodeWithTag(LINE_PANEL_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COLLECTOR_PANEL_TAG).assertDoesNotExist()
        compose.onNodeWithTag(COLLECT_BUTTON_TAG).assertDoesNotExist()
        compose.onNodeWithTag(STAKEOUT_PANEL_TAG).assertDoesNotExist()
    }

    @Test
    fun `S041 RF-2 sin replanteo de linea el panel no esta y cerrarlo avisa`() {
        showLineProject(null)
        compose.onNodeWithTag(LINE_PANEL_TAG).assertDoesNotExist()
    }

    @Test
    fun `S041 RF-2 cerrar el panel de la linea avisa`() {
        showLineProject(lineState)

        compose.onNodeWithTag(LINE_CLOSE_TAG).performClick()

        calls shouldBe listOf("line-close")
    }
}
