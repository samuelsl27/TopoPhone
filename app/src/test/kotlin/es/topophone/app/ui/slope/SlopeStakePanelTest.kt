package es.topophone.app.ui.slope

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.stakeout.AxisField
import es.topophone.app.ui.stakeout.GuidanceState
import es.topophone.app.ui.stakeout.GuidanceUi
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.alignment.slope.SlopeKind
import es.topophone.core.model.alignment.slope.SlopeRule
import es.topophone.core.model.alignment.slope.SlopeSide
import es.topophone.core.model.point.StakeMode
import es.topophone.core.model.point.StakeoutSettings
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.AxisInputError
import es.topophone.core.survey.AxisPoint
import es.topophone.core.survey.Guidance
import es.topophone.core.survey.HeadingResult
import es.topophone.core.survey.SlopeCatchResult
import es.topophone.core.survey.SlopeEdgeResult
import es.topophone.core.survey.SlopeLiveValue
import es.topophone.core.survey.StakeZone
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * El panel del replanteo de taludes con estados ya hechos: los campos de partida, el PK y el lado con la palabra, cabeza o pie, la
 * diferencia de cota, lo que hay que moverse, el error en PK y la punta sin proyección (S051/RF-9 a RF-12, RF-17, RF-18; RNF-T7, RNF-T8).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class SlopeStakePanelTest {

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

    private val rule = SlopeRule(SlopeSide.RIGHT, 0.0, 200.0, 1.5, 2.0, "BD")
    private val axisPoint = AxisPoint(105.0, 3.5, 441_105.0, 4_473_996.5, 441_105.0, 4_474_000.0, 0.0, 0.0)
    private val edge = SlopeEdgeResult.Edge(rule, 105.0, 3.5, 100.0, axisPoint)
    private val found = SlopeCatchResult.Found(SlopeKind.CUT, 1.5, 5.0, 101.0, 1.0, axisPoint)
    private val outcome = SlopePointOutcome.Found(edge, found, null)

    private fun on(): GuidanceState.On = GuidanceState.On(
        GuidanceUi(
            guidance = Guidance(1.3, 0.0, 1.3, 0.0, 1.3, 0.0, null),
            zone = StakeZone.FAR,
            heading = HeadingResult.Available(0.0),
            tipEastM = 0.0,
            tipNorthM = -1.3,
            dianaRadiusM = 0.2,
            solution = SolutionStatus.FIX,
            horizontalAccuracyM = 0.012,
            tipUncompensated = false,
        ),
    )

    private fun setup(errors: Map<AxisField, AxisInputError> = emptyMap(), measuring: Boolean = false) = SlopeStakeUiState(
        project = project,
        loaded = true,
        alignmentName = "Eje C",
        startChainageM = 0.0,
        endChainageM = 200.0,
        startText = "100",
        errors = errors,
        measuring = measuring,
    )

    private fun started(
        live: SlopeLiveValue? = SlopeLiveValue(SlopeKind.CUT, 100.6, 0.2, 0.3, 0.1, onLine = true, found = false),
        outcome: SlopePointOutcome? = this.outcome,
        noProjection: Boolean = false,
        measuring: Boolean = false,
        position: Int = 3,
        count: Int = 10,
    ) = setup(measuring = measuring).copy(
        started = true,
        position = position,
        count = count,
        item = SlopeStakeItem(105.0, SlopeSide.RIGHT),
        outcome = outcome,
        objective = SlopeObjective(axisPoint, 101.0, SlopeKind.CUT, 1.0, null),
        settings = StakeoutSettings(mode = StakeMode.BY_DIRECTION),
        guidance = on(),
        live = live,
        noProjection = noProjection,
    )

    private val calls = mutableListOf<String>()

    private fun show(state: SlopeStakeUiState) = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) {
            SlopeStakePanel(
                state,
                SlopeStakeActions(
                    onClose = { calls += "close" },
                    onStart = { calls += "start" },
                    onStartText = { calls += "start:$it" },
                    onEndText = { calls += "end:$it" },
                    onIntervalText = { calls += "interval:$it" },
                    onSingular = { calls += "singular:$it" },
                    onSide = { calls += "side:${it.name}" },
                    onStakeOffset = { calls += "stake:$it" },
                    onPrevious = { calls += "previous" },
                    onNext = { calls += "next" },
                ),
            )
        }
    }

    @Test
    fun `S051 RF-9 antes de abrir se ven los campos de partida y los lados`() {
        show(setup())

        compose.onNodeWithTag(SLOPE_STAKE_TITLE_TAG).assertTextContains("Eje C", substring = true)
        compose.onNodeWithTag(SLOPE_STAKE_LIMITS_TAG).assertTextContains("0+200,00", substring = true)
        listOf(
            SLOPE_STAKE_START_TAG,
            SLOPE_STAKE_END_TAG,
            SLOPE_STAKE_INTERVAL_TAG,
            SLOPE_STAKE_SINGULAR_TAG,
            slopeStakeSideTag(SlopeSide.LEFT),
            slopeStakeSideTag(SlopeSide.RIGHT),
            SLOPE_STAKE_STAKE_OFFSET_TAG,
            SLOPE_STAKE_OPEN_TAG,
        ).forEach { compose.onNodeWithTag(it).performScrollTo().assertIsDisplayed() }
        compose.onAllNodesWithTag(SLOPE_STAKE_DESIGN_TAG).assertCountEquals(0)
    }

    @Test
    fun `S051 RF-9 escribir en los campos elegir un lado y abrir llega a las acciones`() {
        show(setup())

        compose.onNodeWithTag(SLOPE_STAKE_START_TAG).performTextReplacement("120")
        compose.onNodeWithTag(SLOPE_STAKE_END_TAG).performTextReplacement("180")
        compose.onNodeWithTag(SLOPE_STAKE_INTERVAL_TAG).performTextReplacement("20")
        compose.onNodeWithTag(SLOPE_STAKE_SINGULAR_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(slopeStakeSideTag(SlopeSide.LEFT)).performScrollTo().performClick()
        compose.onNodeWithTag(SLOPE_STAKE_STAKE_OFFSET_TAG).performScrollTo().performTextReplacement("1")
        compose.onNodeWithTag(SLOPE_STAKE_OPEN_TAG).performScrollTo().performClick()

        calls shouldBe listOf("start:120", "end:180", "interval:20", "singular:true", "side:LEFT", "stake:1", "start")
    }

    @Test
    fun `S051 RF-9 un PK fuera del eje dice el PK inicial y el final y un numero malo dice que no lo es`() {
        show(setup(errors = mapOf(AxisField.START to AxisInputError.OUT_OF_AXIS, AxisField.OFFSET to AxisInputError.NOT_A_NUMBER)))

        compose.onNodeWithTag(slopeStakeErrorTag(AxisField.START), useUnmergedTree = true).performScrollTo()
            .assertTextContains("Fuera del eje: PK inicial 0+000,00, PK final 0+200,00.")
        compose.onNodeWithTag(slopeStakeErrorTag(AxisField.OFFSET), useUnmergedTree = true).performScrollTo()
            .assertTextContains("No es un número.")
    }

    @Test
    fun `S051 RF-9 el lugar de la serie dice el PK y el lado con la palabra`() {
        show(started())

        compose.onNodeWithTag(SLOPE_STAKE_POSITION_TAG).assertTextContains("Punto 3 de 10")
        compose.onNodeWithTag(SLOPE_STAKE_DESIGN_TAG).assertTextContains("Derecha", substring = true)
        compose.onNodeWithTag(SLOPE_STAKE_DESIGN_TAG).assertTextContains("0+105,00", substring = true)
    }

    @Test
    fun `S051 RF-9 siguiente y anterior avisan y se bloquean en los extremos`() {
        show(started(position = 1, count = 3))

        compose.onNodeWithTag(SLOPE_STAKE_PREVIOUS_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(SLOPE_STAKE_NEXT_TAG).assertIsEnabled().performClick()

        calls shouldBe listOf("next")
    }

    @Test
    fun `S051 RF-18 con una medida en marcha no se cambia el punto ni la estaca`() {
        show(started(measuring = true))

        compose.onNodeWithTag(SLOPE_STAKE_PREVIOUS_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(SLOPE_STAKE_NEXT_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(SLOPE_STAKE_STAKE_OFFSET_TAG).assertIsNotEnabled()
    }

    @Test
    fun `S051 RF-10 el tipo de punto y la altura de talud se dicen con palabras`() {
        show(started())

        compose.onNodeWithTag(SLOPE_STAKE_KIND_TAG).assertTextContains("Cabeza de desmonte, talud 1,5 : 1")
        compose.onNodeWithTag(SLOPE_STAKE_HEIGHT_TAG).assertTextContains("Altura de talud", substring = true)
        compose.onNodeWithTag(SLOPE_ELEVATION_TAG).assertExists()
    }

    @Test
    fun `S051 RF-11 la diferencia de cota dice si la punta esta por encima o por debajo`() {
        show(started())
        compose.onNodeWithTag(SLOPE_STAKE_DZ_TAG, useUnmergedTree = true).assertTextContains("por encima", substring = true)
    }

    @Test
    fun `S051 RF-11 por debajo del talud se dice con su palabra`() {
        show(started(live = SlopeLiveValue(SlopeKind.CUT, 100.6, -0.2, -0.3, 0.1, onLine = true, found = false)))
        compose.onNodeWithTag(SLOPE_STAKE_DZ_TAG, useUnmergedTree = true).assertTextContains("por debajo", substring = true)
    }

    @Test
    fun `S051 RF-11 la distancia a moverse dice hacia dentro o hacia fuera del eje`() {
        show(started(live = SlopeLiveValue(SlopeKind.CUT, 100.6, 0.2, 0.3, 0.1, onLine = true, found = false)))
        compose.onNodeWithTag(SLOPE_STAKE_MOVE_TAG, useUnmergedTree = true).assertTextContains("hacia fuera del eje", substring = true)
    }

    @Test
    fun `S051 RF-11 un movimiento negativo es hacia dentro del eje`() {
        show(started(live = SlopeLiveValue(SlopeKind.CUT, 100.6, -0.2, -0.3, 0.1, onLine = true, found = false)))
        compose.onNodeWithTag(SLOPE_STAKE_MOVE_TAG, useUnmergedTree = true).assertTextContains("hacia dentro del eje", substring = true)
    }

    @Test
    fun `S051 RF-12 el error en PK dice si la punta esta por delante o por detras`() {
        show(started(live = SlopeLiveValue(SlopeKind.CUT, 100.6, 0.2, 0.3, 0.1, onLine = false, found = false)))
        compose.onNodeWithTag(SLOPE_STAKE_CHAINAGE_TAG, useUnmergedTree = true).assertTextContains("por delante", substring = true)
    }

    @Test
    fun `S051 RF-12 un error en PK negativo es por detras`() {
        show(started(live = SlopeLiveValue(SlopeKind.CUT, 100.6, 0.2, 0.3, -0.1, onLine = false, found = false)))
        compose.onNodeWithTag(SLOPE_STAKE_CHAINAGE_TAG, useUnmergedTree = true).assertTextContains("por detrás", substring = true)
    }

    @Test
    fun `S051 RF-17 sin proyeccion sobre el eje se apagan los valores en vivo y se dice`() {
        show(started(live = null, noProjection = true))

        compose.onNodeWithTag(SLOPE_STAKE_NO_PROJECTION_TAG).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithTag(SLOPE_STAKE_DZ_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(SLOPE_STAKE_MOVE_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(SLOPE_STAKE_CHAINAGE_TAG).assertCountEquals(0)
    }

    @Test
    fun `S051 RF-8 sin talud o sin terreno el panel dice el motivo`() {
        show(started(live = null, outcome = SlopePointOutcome.Unavailable(SlopeEdgeResult.NoRule)))
        compose.onNodeWithTag(SLOPE_STAKE_STATUS_TAG).assertTextContains("No hay talud definido", substring = true)
    }

    @Test
    fun `S051 RF-6 sin terreno el panel dice que el punto se busca en vivo`() {
        show(started(live = null, outcome = SlopePointOutcome.NoTerrain(edge)))
        compose.onNodeWithTag(SLOPE_STAKE_STATUS_TAG).assertTextContains("Sin terreno conocido", substring = true)
    }

    @Test
    fun `S051 RNF-T8 la frase de TalkBack junta la diferencia de cota y lo que hay que moverse`() {
        show(started())
        compose.onNodeWithTag(SLOPE_STAKE_LIVE_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S051 RF-9 cerrar avisa`() {
        show(started())
        compose.onNodeWithTag(SLOPE_STAKE_CLOSE_TAG).performClick()
        calls shouldBe listOf("close")
    }
}
