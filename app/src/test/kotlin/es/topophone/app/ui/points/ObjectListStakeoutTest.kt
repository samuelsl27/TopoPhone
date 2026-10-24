package es.topophone.app.ui.points

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.points.PointFixtures.measured
import es.topophone.app.ui.points.PointFixtures.staked
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.point.PointSummary
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La marca de los puntos de diseño que ya se han replanteado, con su recuento (S018/RF-33). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ObjectListStakeoutTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(vararg points: PointSummary) = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) {
            val state = ObjectListUiState(loaded = true, points = points.toList(), total = points.size)
            ObjectListScreen(ThemeState.DEFAULT, {}, state, ObjectListActions(), {})
        }
    }

    @Test
    fun `S018 RF-33 un punto de diseno replanteado lleva la marca con texto y el numero de replanteos`() {
        show(measured("12").summary().copy(stakeCount = 2), measured("13").summary().copy(stakeCount = 1))

        compose.onNodeWithTag(objectRowTag("id-12")).assert(hasText("Replanteado 2 veces", substring = true))
        compose.onNodeWithTag(objectRowTag("id-13")).assert(hasText("Replanteado 1 vez", substring = true))
    }

    @Test
    fun `S018 RF-33 un punto sin replanteos no lleva la marca`() {
        show(measured("14").summary())

        compose.onNodeWithTag(objectRowTag("id-14")).assert(!hasText("vez", substring = true))
    }

    @Test
    fun `S018 RF-33 el punto replanteado en si no lleva el recuento`() {
        show(staked("12_stkd").summary())

        compose.onNodeWithTag(objectRowTag("id-12_stkd")).assert(!hasText("vez", substring = true))
    }
}
