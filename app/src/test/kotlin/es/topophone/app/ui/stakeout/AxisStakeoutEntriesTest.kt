package es.topophone.app.ui.stakeout

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.collector.COLLECT_BUTTON_TAG
import es.topophone.app.ui.collector.CollectorUiState
import es.topophone.app.ui.map.FakeMapController
import es.topophone.app.ui.map.fakeMap
import es.topophone.app.ui.projects.ProjectViewScreen
import es.topophone.app.ui.projects.ProjectViewUiState
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.PointCoordinates
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
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lo que ve el mapa y la vista del proyecto en el replanteo por PK: el punto de diseño, la punta y la línea hasta él; y que el panel
 * ocupa el hueco del recolector (S054/RF-1, RF-11).
 */
@RunWith(AndroidJUnit4::class)
class AxisStakeoutEntriesTest {

    @get:Rule
    val compose = createComposeRule()

    private val project = Project(
        id = "p1",
        name = "Obra",
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

    private val tipGround = GeodeticPosition(40.4168, -3.7038, 699.0)
    private val designGround = GeodeticPosition(40.4169, -3.7038, 699.0)

    private fun on(distance: Double) = GuidanceState.On(
        GuidanceUi(
            guidance = Guidance(distance, 0.0, distance, 0.0, null, null, null),
            zone = StakeZone.FAR,
            heading = HeadingResult.Available(0.0),
            tipEastM = 0.0,
            tipNorthM = -distance,
            dianaRadiusM = 0.2,
            solution = SolutionStatus.FIX,
            horizontalAccuracyM = 0.01,
            tipUncompensated = false,
            tip = tipGround,
        ),
    )

    private fun state(guidance: GuidanceState = on(5.0), started: Boolean = true, closed: AxisClosedReason? = null) = AxisStakeoutUiState(
        project = project,
        loaded = true,
        alignmentId = "a1",
        alignmentName = "Eje A",
        started = started,
        guidance = guidance,
        closed = closed,
        stakeTarget = StakeTarget(
            "axis:Eje A:150000",
            "Eje A",
            "",
            PointCoordinates(designGround, 440_290.0, 4_474_257.0, null),
        ),
    )

    @Test
    fun `S054 RF-1 el mapa dibuja el punto de diseno y con la guia encendida la punta`() {
        val marker = AxisStakeoutMarkers.of(state()).shouldNotBeNull()

        marker.design shouldBe GeoPoint(designGround.latitudeDeg, designGround.longitudeDeg)
        marker.tip shouldBe GeoPoint(tipGround.latitudeDeg, tipGround.longitudeDeg)
        marker.chosen shouldBe emptyList()
    }

    @Test
    fun `S054 RF-1 la linea hasta el objetivo solo se dibuja a mas de 40 cm porque dentro lo ensena la diana`() {
        AxisStakeoutMarkers.of(state(on(5.0))).shouldNotBeNull().line shouldBe true
        AxisStakeoutMarkers.of(state(on(0.4))).shouldNotBeNull().line shouldBe false
    }

    @Test
    fun `S054 RF-1 con la guia apagada se ve el punto de diseno y no hay punta ni linea`() {
        val marker = AxisStakeoutMarkers.of(state(GuidanceState.Off(GuidanceOff.NO_DATA))).shouldNotBeNull()

        marker.design.shouldNotBeNull()
        marker.tip.shouldBeNull()
        marker.line shouldBe false
    }

    @Test
    fun `S054 RF-1 sin replanteo abierto o cerrado por el borrado del eje el mapa no dibuja nada`() {
        AxisStakeoutMarkers.of(null).shouldBeNull()
        AxisStakeoutMarkers.of(state(started = false)).shouldBeNull()
        AxisStakeoutMarkers.of(state(closed = AxisClosedReason.ALIGNMENT_DELETED)).shouldBeNull()
        AxisStakeoutMarkers.of(state().copy(stakeTarget = null)).shouldBeNull()
    }

    private fun show(axis: AxisStakeoutUiState?, map: FakeMapController? = null) = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) {
            ProjectViewScreen(
                ThemeState.DEFAULT,
                {},
                ProjectViewUiState(project),
                onBack = {},
                map = map?.let { fakeMap(it) } ?: { _, _ -> },
                collector = CollectorUiState(loaded = true),
                axisStakeout = axis,
            )
        }
    }

    @Test
    fun `S054 RF-1 la vista del proyecto muestra el panel del eje en el hueco del recolector y esconde Recolectar`() {
        show(state())

        compose.onNodeWithTag(AXIS_PANEL_TAG).assertIsDisplayed()
        compose.onAllNodesWithTag(COLLECT_BUTTON_TAG).assertCountEquals(0)
    }

    @Test
    fun `S054 RF-1 sin replanteo de eje la vista del proyecto ofrece Recolectar y no muestra el panel`() {
        show(null)

        compose.onAllNodesWithTag(AXIS_PANEL_TAG).assertCountEquals(0)
        compose.onNodeWithTag(COLLECT_BUTTON_TAG).assertIsDisplayed()
    }

    @Test
    fun `S054 RF-1 el mapa recibe el punto de diseno del replanteo por PK`() {
        val map = FakeMapController()
        show(state(), map)
        compose.waitForIdle()

        map.stakeout.shouldNotBeNull().design shouldBe GeoPoint(designGround.latitudeDeg, designGround.longitudeDeg)
    }
}
