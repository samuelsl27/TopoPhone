package es.topophone.app.ui.stakeout

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.surface.SurfaceHeight
import es.topophone.core.survey.Guidance
import es.topophone.core.survey.HeadingResult
import es.topophone.core.survey.LevelState
import es.topophone.core.survey.StakeZone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La placa del replanteo de la superficie: sin planta, con el desmonte o el terraplén y las cotas (S062/RF-7 a RF-9, RF-13). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class SurfaceStakePlateTest {

    @get:Rule
    val compose = createComposeRule()

    private fun on(surface: SurfaceUi?, level: LevelUi?, levelUnavailable: LevelUnavailable? = null) = GuidanceState.On(
        GuidanceUi(
            guidance = Guidance(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, null),
            zone = StakeZone.FAR,
            heading = HeadingResult.Available(0.0),
            tipEastM = 0.0,
            tipNorthM = 0.0,
            dianaRadiusM = 0.2,
            solution = SolutionStatus.FIX,
            horizontalAccuracyM = 0.012,
            tipUncompensated = false,
            level = level,
            levelUnavailable = levelUnavailable,
            surface = surface,
            surfaceOnly = true,
        ),
    )

    private fun show(guidance: GuidanceState) {
        compose.setContent { TopoPhoneTheme(EffectiveTheme.LIGHT) { SurfaceStakePlate(guidance, LinearUnit.METRE) } }
    }

    @Test
    fun `S062 RF-8 RF-9 la placa da el terraplen y las cotas de la superficie de diseno y de la punta`() {
        show(on(SurfaceUi(SurfaceHeight.Value(612.34), 0.1), LevelUi(-0.052, LevelState.FILL, 0.5, 0.03)))

        compose.onNodeWithText("Terraplén 0,052 m").assertIsDisplayed()
        compose.onNodeWithText("Cota de la superficie: 612,340 m").assertIsDisplayed()
        compose.onNodeWithText("Desplazamiento: +0,100 m").assertIsDisplayed()
        compose.onNodeWithText("Cota de diseño: 612,440 m").assertIsDisplayed()
        compose.onNodeWithText("Cota de la punta: 612,388 m").assertIsDisplayed()
        compose.onNodeWithTag(STAKEOUT_LEVEL_TAG).assertIsDisplayed()
    }

    @Test
    fun `S062 RF-9 en cota lo dice`() {
        show(on(SurfaceUi(SurfaceHeight.Value(612.34), 0.0), LevelUi(0.01, LevelState.ON_GRADE, 0.5, 0.03)))

        compose.onNodeWithText("En cota").assertIsDisplayed()
    }

    @Test
    fun `S062 RF-7 la placa no lleva distancia ni desplazamientos en planta`() {
        show(on(SurfaceUi(SurfaceHeight.Value(612.34), 0.0), LevelUi(0.2, LevelState.CUT, 0.5, 0.03)))

        compose.onNodeWithText("Desmonte 0,200 m").assertIsDisplayed()
        compose.onNodeWithText("Norte", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Este", substring = true).assertDoesNotExist()
    }

    @Test
    fun `S062 RF-13 fuera de la superficie lo dice sin cotas`() {
        show(on(SurfaceUi(SurfaceHeight.Outside, 0.1), null, LevelUnavailable.OUTSIDE_SURFACE))

        compose.onNodeWithText("Fuera de la superficie").assertIsDisplayed()
        compose.onNodeWithText("Cota de diseño", substring = true).assertDoesNotExist()
    }

    @Test
    fun `S062 RF-15 con la guia apagada da el motivo`() {
        show(GuidanceState.Off(GuidanceOff.SOLUTION_NOT_ADMITTED))

        compose.onNodeWithTag(STAKEOUT_OFF_TAG).assertIsDisplayed()
    }
}
