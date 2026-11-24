package es.topophone.app.ui.stakeout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.collector.CollectorUiState
import es.topophone.app.ui.collector.MeasureBlocker
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.SolutionStatus
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
import es.topophone.core.survey.Guidance
import es.topophone.core.survey.HeadingResult
import es.topophone.core.survey.LevelState
import es.topophone.core.survey.StakeZone
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Cuánto tapa la hoja plegada del replanteo de un punto, con la placa más cargada (S060/RF-2). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "es-rES-w411dp-h891dp")
class StakeoutSheetHeightTest {

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

    private fun state(): StakeoutUiState {
        val guidance = Guidance(5.0, 3.0, 4.0, Math.atan2(3.0, 4.0), 4.0, 3.0, 0.123)
        return StakeoutUiState(
            project = project,
            target = target,
            hasPrevious = true,
            hasNext = true,
            settings = StakeoutSettings(mode = StakeMode.BY_DIRECTION),
            guidance = GuidanceState.On(
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
                    level = LevelUi(0.123, LevelState.CUT, 5.0, 0.03),
                    levelUnavailable = null,
                ),
            ),
        )
    }

    private fun fraction(): Float {
        val sheet = compose.onNodeWithTag(STAKEOUT_PANEL_TAG).getUnclippedBoundsInRoot().height.value
        val screen = compose.onRoot().getUnclippedBoundsInRoot().height.value
        return sheet / screen
    }

    @Test
    fun `S060 RF-2 a 411 por 891 dp la hoja plegada del replanteo con cota deja libre al menos el 60 por ciento`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                Box(Modifier.fillMaxSize()) {
                    StakeoutPanel(
                        state(),
                        StakeoutActions(),
                        Modifier.align(Alignment.BottomCenter),
                        collector = CollectorUiState(loaded = true, name = "12", blocker = MeasureBlocker.NO_GEAR),
                    )
                }
            }
        }

        fraction() shouldBeLessThanOrEqualTo 0.40f
    }
}
