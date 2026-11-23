package es.topophone.app.ui.stakeout

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.collector.CollectorUiState
import es.topophone.app.ui.map.FakeMapController
import es.topophone.app.ui.map.fakeMap
import es.topophone.app.ui.projects.ProjectViewScreen
import es.topophone.app.ui.projects.ProjectViewUiState
import es.topophone.app.ui.projects.STAKEOUT_RECENTER_TAG
import es.topophone.core.designsystem.theme.TopoPhoneTheme
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
import io.kotest.matchers.shouldBe
import java.time.Duration
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/** El mapa que sigue al jalón en el replanteo, con «Recentrar» y el norte arriba al cerrar (S060/RF-25, RF-26). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class StakeoutFollowScreenTest {

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

    private val design = StakeTarget(
        "d1",
        "12",
        "esquina",
        PointCoordinates(GeodeticPosition(40.4168, -3.7030, 699.4), 440_290.458, 4_474_257.382, 648.19),
    )

    private fun stakeout(tipLat: Double, heading: HeadingResult = HeadingResult.Available(Math.toRadians(90.0))) = StakeoutUiState(
        project = project,
        target = design,
        guidance = GuidanceState.On(
            GuidanceUi(
                guidance = Guidance(40.0, 40.0, 0.0, 1.57, 0.0, 40.0, null),
                zone = StakeZone.FAR,
                heading = heading,
                tipEastM = -40.0,
                tipNorthM = 0.0,
                dianaRadiusM = 0.2,
                solution = SolutionStatus.FIX,
                horizontalAccuracyM = 0.01,
                tipUncompensated = false,
                tip = GeodeticPosition(tipLat, -3.7038, 699.0),
            ),
        ),
    )

    private val map = FakeMapController()
    private var state by mutableStateOf<StakeoutUiState?>(null)

    private fun show(initial: StakeoutUiState?) {
        state = initial
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectViewScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectViewUiState(project),
                    onBack = {},
                    map = fakeMap(map),
                    collector = CollectorUiState(loaded = true),
                    stakeout = state,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun advance(ms: Long = 1_000) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(ms))
    }

    @Test
    fun `S060 RF-25 con el replanteo abierto el mapa se centra en el jalon y se gira hacia la marcha`() {
        show(stakeout(40.4168))

        val camera = map.follows.last()
        camera.latitudeDeg shouldBe 40.4168
        camera.longitudeDeg shouldBe -3.7038
        camera.bearingDeg shouldBe 90.0
    }

    @Test
    fun `S060 RF-25 sin rumbo el mapa tiene el norte arriba`() {
        show(stakeout(40.4168, HeadingResult.Unavailable(es.topophone.core.survey.HeadingUnavailable.NOT_MOVING)))

        map.follows.last().bearingDeg shouldBe 0.0
    }

    @Test
    fun `S060 RF-25 sin replanteo el mapa no se mueve ni sale Recentrar`() {
        show(null)

        map.follows.size shouldBe 0
        compose.onAllNodesWithTag(STAKEOUT_RECENTER_TAG).fetchSemanticsNodes().size shouldBe 0
    }

    @Test
    fun `S060 RF-26 mover el mapa a mano deja de seguir y sale Recentrar con 56 dp`() {
        show(stakeout(40.4168))
        val before = map.follows.size

        map.gesture()
        compose.waitForIdle()
        advance()
        state = stakeout(40.4170)
        compose.waitForIdle()

        compose.onNodeWithTag(STAKEOUT_RECENTER_TAG).assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        map.follows.size shouldBe before
    }

    @Test
    fun `S060 RF-26 Recentrar vuelve a seguir al jalon y quita el boton`() {
        show(stakeout(40.4168))
        map.gesture()
        compose.waitForIdle()
        val before = map.follows.size

        compose.onNodeWithTag(STAKEOUT_RECENTER_TAG).performClick()
        compose.waitForIdle()

        (map.follows.size > before) shouldBe true
        compose.onAllNodesWithTag(STAKEOUT_RECENTER_TAG).fetchSemanticsNodes().size shouldBe 0
    }

    @Test
    fun `S060 RF-25 al cerrar el replanteo el norte vuelve arriba`() {
        show(stakeout(40.4168))
        val resets = map.bearingResets

        state = null
        compose.waitForIdle()

        (map.bearingResets > resets) shouldBe true
    }
}
