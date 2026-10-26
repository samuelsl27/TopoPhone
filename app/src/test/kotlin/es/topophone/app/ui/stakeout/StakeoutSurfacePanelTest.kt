package es.topophone.app.ui.stakeout

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.ElevationSource
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.StakeMode
import es.topophone.core.model.point.StakeoutSettings
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.surface.SurfaceHeight
import es.topophone.core.survey.Guidance
import es.topophone.core.survey.HeadingResult
import es.topophone.core.survey.LevelState
import es.topophone.core.survey.StakeZone
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * El panel del replanteo contra una superficie: el conmutador «Cota del punto» / «Superficie», la cota de la superficie y el
 * desplazamiento, «Fuera de la superficie», «Calculando…» y el aviso de la superficie retirada (S042/RF-23 a RF-27, RNF-T8).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class StakeoutSurfacePanelTest {

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
    private val target = StakeTarget(
        "d1",
        "12",
        "esquina norte",
        PointCoordinates(GeodeticPosition(40.4168, -3.0, 699.4), 440_290.458, 4_474_257.382, 648.19),
    )

    private fun on(surface: SurfaceUi? = null, level: LevelUi? = null, levelUnavailable: LevelUnavailable? = null): GuidanceState.On {
        val guidance = Guidance(5.0, 3.0, 4.0, Math.atan2(3.0, 4.0), 4.0, 3.0, 0.123)
        return GuidanceState.On(
            GuidanceUi(
                guidance = guidance,
                zone = StakeZone.FAR,
                heading = HeadingResult.Available(0.0),
                tipEastM = -3.0,
                tipNorthM = -4.0,
                dianaRadiusM = 0.2,
                solution = SolutionStatus.FIX,
                horizontalAccuracyM = 0.012,
                tipUncompensated = false,
                level = level,
                levelUnavailable = levelUnavailable,
                surface = surface,
            ),
        )
    }

    private fun state(
        surfaceActive: Boolean = true,
        source: ElevationSource = ElevationSource.POINT,
        canChoose: Boolean = true,
        removed: Boolean = false,
        measuring: Boolean = false,
        guidance: GuidanceState = on(),
    ) = StakeoutUiState(
        project = project,
        target = target,
        settings = StakeoutSettings(mode = StakeMode.BY_DISTANCE),
        guidance = guidance,
        measuring = measuring,
        surfaceActive = surfaceActive,
        elevationSource = source,
        canChooseSource = canChoose,
        surfaceRemoved = removed,
    )

    private val chosen = mutableListOf<ElevationSource>()

    private fun show(state: StakeoutUiState) = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) {
            StakeoutPanel(state, StakeoutActions(onElevationSource = { chosen += it }))
        }
    }

    @Test
    fun `S042 RF-25 sin superficie activa no hay conmutador`() {
        show(state(surfaceActive = false, canChoose = false))

        compose.onNodeWithTag(STAKEOUT_SOURCE_POINT_TAG).assertDoesNotExist()
        compose.onNodeWithTag(STAKEOUT_SOURCE_SURFACE_TAG).assertDoesNotExist()
    }

    @Test
    fun `S042 RF-25 con superficie y cota propia hay conmutador con la cota del punto elegida`() {
        show(state())

        compose.onNodeWithTag(STAKEOUT_SOURCE_POINT_TAG).assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag(STAKEOUT_SOURCE_SURFACE_TAG).assertIsDisplayed()
    }

    @Test
    fun `S042 RF-25 pulsar Superficie y Cota del punto avisa del origen elegido`() {
        show(state())

        compose.onNodeWithTag(STAKEOUT_SOURCE_SURFACE_TAG).performClick()
        compose.onNodeWithTag(STAKEOUT_SOURCE_POINT_TAG).performClick()

        chosen shouldBe listOf(ElevationSource.SURFACE, ElevationSource.POINT)
    }

    @Test
    fun `S042 RF-25 con una medida en marcha el conmutador no se puede cambiar`() {
        show(state(measuring = true))

        compose.onNodeWithTag(STAKEOUT_SOURCE_SURFACE_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(STAKEOUT_SOURCE_POINT_TAG).assertIsNotEnabled()
    }

    @Test
    fun `S042 RF-24 un punto sin cota contra la superficie no ofrece conmutador pero ensena la cota y el desplazamiento`() {
        show(
            state(
                source = ElevationSource.SURFACE,
                canChoose = false,
                guidance = on(SurfaceUi(SurfaceHeight.Value(601.25), 0.3), LevelUi(0.12, LevelState.FILL, 1.0, 0.03)),
            ),
        )

        compose.onNodeWithTag(STAKEOUT_SOURCE_SURFACE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(STAKEOUT_SURFACE_HEIGHT_TAG).assertIsDisplayed().assertTextContains("601", substring = true)
        compose.onNodeWithTag(STAKEOUT_SURFACE_OFFSET_TAG).assertIsDisplayed().assertTextContains("0", substring = true)
        compose.onNodeWithTag(STAKEOUT_LEVEL_TEXT_TAG).assertExists()
    }

    @Test
    fun `S042 RF-23 fuera de la superficie el nivel dice Fuera de la superficie en texto`() {
        show(
            state(
                source = ElevationSource.SURFACE,
                canChoose = false,
                guidance = on(SurfaceUi(SurfaceHeight.Outside, 0.3), levelUnavailable = LevelUnavailable.OUTSIDE_SURFACE),
            ),
        )

        compose.onNodeWithTag(STAKEOUT_LEVEL_UNAVAILABLE_TAG).assertIsDisplayed().assertTextContains("Fuera de la superficie")
        compose.onNodeWithTag(STAKEOUT_SURFACE_HEIGHT_TAG).assertDoesNotExist()
    }

    @Test
    fun `S042 RF-22 mientras la superficie carga el nivel dice que se calcula`() {
        show(
            state(
                source = ElevationSource.SURFACE,
                canChoose = false,
                guidance = on(SurfaceUi(SurfaceHeight.Loading, 0.0), levelUnavailable = LevelUnavailable.SURFACE_LOADING),
            ),
        )

        compose.onNodeWithTag(STAKEOUT_LEVEL_UNAVAILABLE_TAG).assertIsDisplayed().assertTextContains("Calculando", substring = true)
    }

    @Test
    fun `S042 RF-27 si la superficie se retira se avisa con el motivo`() {
        show(state(surfaceActive = false, canChoose = false, removed = true))

        compose.onNodeWithTag(STAKEOUT_SURFACE_REMOVED_TAG).assertIsDisplayed().assertTextContains("ya no está activa", substring = true)
    }

    @Test
    fun `S042 RF-27 sin retirar nada no hay aviso`() {
        show(state())

        compose.onNodeWithTag(STAKEOUT_SURFACE_REMOVED_TAG).assertDoesNotExist()
    }
}
