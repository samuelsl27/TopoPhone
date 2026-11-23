package es.topophone.app.ui.stakeout

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.StakeMode
import es.topophone.core.model.point.StakeoutSettings
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.Guidance
import es.topophone.core.survey.HeadingResult
import es.topophone.core.survey.LevelState
import es.topophone.core.survey.StakeZone
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La regla de llegada y la placa de guiado del replanteo (S060/RF-24, RF-28). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES-w411dp-h900dp")
class GuidancePlateTest {

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

    private fun ui(
        zone: StakeZone = StakeZone.FAR,
        distance: Double = 1.3,
        ahead: Double? = 1.0,
        right: Double? = -0.5,
        level: LevelUi? = null,
    ) = GuidanceUi(
        guidance = Guidance(distance, -0.5, 1.0, 0.0, ahead, right, level?.dzM),
        zone = zone,
        heading = HeadingResult.Available(0.0),
        tipEastM = 0.0,
        tipNorthM = -distance,
        dianaRadiusM = 0.2,
        solution = SolutionStatus.FIX,
        horizontalAccuracyM = 0.012,
        tipUncompensated = false,
        level = level,
    )

    private fun level(state: LevelState, dz: Double) = LevelUi(dzM = dz, state = state, radiusM = 0.5, toleranceM = 0.03)

    // ---- La regla de llegada (RF-28) ----

    @Test
    fun `S060 RF-28 sin cota de diseno basta estar dentro de la tolerancia horizontal`() {
        ArrivalRules.within(ui(zone = StakeZone.INSIDE)) shouldBe true
        ArrivalRules.within(ui(zone = StakeZone.AID)) shouldBe false
        ArrivalRules.within(ui(zone = StakeZone.FAR)) shouldBe false
    }

    @Test
    fun `S060 RF-28 con cota de diseno tambien tiene que estar en cota`() {
        ArrivalRules.within(ui(zone = StakeZone.INSIDE, level = level(LevelState.ON_GRADE, 0.01))) shouldBe true
        ArrivalRules.within(ui(zone = StakeZone.INSIDE, level = level(LevelState.CUT, 0.20))) shouldBe false
        ArrivalRules.within(ui(zone = StakeZone.INSIDE, level = level(LevelState.FILL, -0.20))) shouldBe false
        ArrivalRules.within(ui(zone = StakeZone.AID, level = level(LevelState.ON_GRADE, 0.0))) shouldBe false
    }

    // ---- La placa ----

    private fun show(ui: GuidanceUi, mode: StakeMode) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                GuidancePlate(project, StakeoutSettings(mode = mode), GuidanceState.On(ui))
            }
        }
    }

    @Test
    fun `S060 RF-24 por direccion la placa da la distancia y adelante y derecha o izquierda`() {
        show(ui(ahead = 1.0, right = -0.5), StakeMode.BY_DIRECTION)

        compose.onNodeWithTag(STAKEOUT_DISTANCE_TAG).assertIsDisplayed().assertTextContains("1,300 m", substring = true)
        compose.onNodeWithTag(STAKEOUT_AHEAD_TAG).assertTextContains("Adelante", substring = true)
        compose.onNodeWithTag(STAKEOUT_RIGHT_TAG).assertTextContains("Izquierda", substring = true)
        compose.onAllNodesWithTag(STAKEOUT_NORTH_TAG).fetchSemanticsNodes().size shouldBe 0
    }

    @Test
    fun `S060 RF-24 detras y a la derecha dicen Atras y Derecha`() {
        show(ui(ahead = -2.0, right = 0.4), StakeMode.BY_DIRECTION)

        compose.onNodeWithTag(STAKEOUT_AHEAD_TAG).assertTextContains("Atrás", substring = true)
        compose.onNodeWithTag(STAKEOUT_RIGHT_TAG).assertTextContains("Derecha", substring = true)
    }

    @Test
    fun `S060 RF-24 por distancia la placa da Norte y Este con signo`() {
        show(ui(), StakeMode.BY_DISTANCE)

        compose.onNodeWithTag(STAKEOUT_NORTH_TAG).assertTextContains("Norte +1,000 m", substring = true)
        compose.onNodeWithTag(STAKEOUT_EAST_TAG).assertTextContains("Este -0,500 m", substring = true)
        compose.onAllNodesWithTag(STAKEOUT_AHEAD_TAG).fetchSemanticsNodes().size shouldBe 0
    }

    @Test
    fun `S060 RF-24 sin rumbo la placa sigue dando la distancia y Norte y Este`() {
        show(ui(ahead = null, right = null), StakeMode.BY_DIRECTION)

        compose.onNodeWithTag(STAKEOUT_DISTANCE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(STAKEOUT_NORTH_TAG).assertIsDisplayed()
        compose.onNodeWithTag(STAKEOUT_EAST_TAG).assertIsDisplayed()
    }

    @Test
    fun `S060 RF-24 con cota de diseno la placa dice Desmonte o Terraplen con la diferencia`() {
        show(ui(level = level(LevelState.CUT, 0.20)), StakeMode.BY_DIRECTION)
        compose.onNodeWithTag(STAKEOUT_LEVEL_TEXT_TAG).assertTextContains("Desmonte 0,200 m", substring = true)
    }

    @Test
    fun `S060 RF-24 con la punta por debajo de la cota la placa dice Terraplen`() {
        show(ui(level = level(LevelState.FILL, -0.12)), StakeMode.BY_DIRECTION)
        compose.onNodeWithTag(STAKEOUT_LEVEL_TEXT_TAG).assertTextContains("Terraplén 0,120 m", substring = true)
    }

    @Test
    fun `S060 RF-28 dentro de las tolerancias la placa dice En el punto con texto`() {
        show(ui(zone = StakeZone.INSIDE, distance = 0.01, level = level(LevelState.ON_GRADE, 0.0)), StakeMode.BY_DIRECTION)

        compose.onNodeWithTag(STAKEOUT_ARRIVED_TAG).assertIsDisplayed().assertTextContains("En el punto")
    }

    @Test
    fun `S060 RF-28 fuera de la tolerancia vertical la placa no dice En el punto`() {
        show(ui(zone = StakeZone.INSIDE, distance = 0.01, level = level(LevelState.CUT, 0.30)), StakeMode.BY_DIRECTION)

        compose.onAllNodesWithTag(STAKEOUT_ARRIVED_TAG).fetchSemanticsNodes().size shouldBe 0
    }

    @Test
    fun `S060 RF-24 con la guia apagada la placa dice por que`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                GuidancePlate(project, StakeoutSettings(), GuidanceState.Off(GuidanceOff.NO_POSITION))
            }
        }

        compose.onNodeWithTag(STAKEOUT_OFF_TAG).assertIsDisplayed()
    }
}
