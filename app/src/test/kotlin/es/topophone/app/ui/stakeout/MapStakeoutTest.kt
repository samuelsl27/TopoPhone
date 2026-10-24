package es.topophone.app.ui.stakeout

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.collector.CollectorUiState
import es.topophone.app.ui.map.FakeMapController
import es.topophone.app.ui.map.MapPoint
import es.topophone.app.ui.map.MapPoints
import es.topophone.app.ui.map.fakeMap
import es.topophone.app.ui.projects.ProjectViewScreen
import es.topophone.app.ui.projects.ProjectViewUiState
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.gnss.nmea.SolutionStatus
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
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lo que ve el mapa durante el replanteo —la punta, el punto de diseño y la línea entre ellos mientras queda a más de 40 cm— y
 * el símbolo propio de los puntos replanteados (S018/RF-14, RF-34).
 */
@RunWith(AndroidJUnit4::class)
class MapStakeoutTest {

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
    private val designGround = GeodeticPosition(40.4168, -3.7038, 699.4)
    private val tipGround = GeodeticPosition(40.41685, -3.7038, 699.4)
    private val target = StakeTarget("d1", "12", "", PointCoordinates(designGround, 440_290.0, 4_474_257.0, 648.0))

    private fun ui(distance: Double, tip: GeodeticPosition? = tipGround, zone: StakeZone = StakeZone.FAR) = GuidanceUi(
        guidance = Guidance(distance, 0.0, distance, 0.0, null, null, null),
        zone = zone,
        heading = HeadingResult.Available(0.0),
        tipEastM = 0.0,
        tipNorthM = -distance,
        dianaRadiusM = 0.5,
        solution = SolutionStatus.FIX,
        horizontalAccuracyM = 0.01,
        tipUncompensated = false,
        tip = tip,
    )

    private fun state(guidance: GuidanceState, target: StakeTarget? = this.target) = StakeoutUiState(project, target, guidance = guidance)

    @Test
    fun `S018 RF-14 a mas de 40 cm el mapa lleva la punta, el punto de diseno y la linea`() {
        val marker = StakeoutMarkers.of(state(GuidanceState.On(ui(distance = 5.0)))).shouldNotBeNull()

        marker.design shouldBe GeoPoint(40.4168, -3.7038)
        marker.tip shouldBe GeoPoint(40.41685, -3.7038)
        marker.line shouldBe true
    }

    @Test
    fun `S018 RF-14 a 40 cm o menos solo hay punta y punto de diseno, sin linea`() {
        val marker = StakeoutMarkers.of(state(GuidanceState.On(ui(distance = 0.40, zone = StakeZone.AID)))).shouldNotBeNull()

        marker.line shouldBe false
        marker.tip.shouldNotBeNull()
    }

    @Test
    fun `S018 RF-14 con la guia apagada el mapa solo lleva el punto de diseno`() {
        val marker = StakeoutMarkers.of(state(GuidanceState.Off(GuidanceOff.NO_FIX))).shouldNotBeNull()

        marker.design shouldBe GeoPoint(40.4168, -3.7038)
        marker.tip.shouldBeNull()
        marker.line shouldBe false
    }

    @Test
    fun `S018 RF-14 sin punto de diseno o sin replanteo no hay marcador`() {
        StakeoutMarkers.of(state(GuidanceState.Off(GuidanceOff.NO_POSITION), target = null)).shouldBeNull()
        StakeoutMarkers.of(null).shouldBeNull()
    }

    private fun summary(id: String, origin: PointOrigin) = PointSummary(
        id = id,
        name = id,
        description = "",
        origin = origin,
        latitudeDeg = 40.4168,
        longitudeDeg = -3.7038,
        hasHeight = true,
        outOfTolerance = false,
        edited = false,
        hidden = false,
        createdAtMs = 1_000,
    )

    @Test
    fun `S018 RF-34 los puntos replanteados llevan su propio simbolo y los medidos y manuales no`() {
        val mapPoints = MapPoints.from(
            listOf(summary("a", PointOrigin.MEASURED), summary("b", PointOrigin.STAKED), summary("c", PointOrigin.MANUAL)),
        )

        mapPoints.map(MapPoint::staked) shouldBe listOf(false, true, false)
    }

    @Test
    fun `S018 RF-14 la vista de proyecto lleva el marcador al mapa y lo quita al cerrar el replanteo`() {
        val map = FakeMapController()
        var open by mutableStateOf<StakeoutUiState?>(state(GuidanceState.On(ui(distance = 5.0))))
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectViewScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectViewUiState(project),
                    onBack = {},
                    map = fakeMap(map),
                    collector = CollectorUiState(loaded = true),
                    stakeout = open,
                )
            }
        }
        compose.waitForIdle()

        map.stakeout.shouldNotBeNull().line shouldBe true

        open = null
        compose.waitForIdle()

        map.stakeout.shouldBeNull()
    }
}
