package es.topophone.app.ui.points

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.points.PointFixtures.measured
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.point.PointSummary
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La marca de los puntos con fotos y cuántas, en la lista de objetos (S017b/RF-26). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ObjectListPhotosTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(vararg points: PointSummary) = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) {
            val state = ObjectListUiState(loaded = true, points = points.toList(), total = points.size)
            ObjectListScreen(ThemeState.DEFAULT, {}, state, ObjectListActions(), {})
        }
    }

    @Test
    fun `S017b RF-26 un punto con fotos lleva la marca con texto y cuantas tiene`() {
        show(measured("12").summary().copy(photoCount = 3), measured("13").summary().copy(photoCount = 1))

        compose.onNodeWithTag(objectRowTag("id-12")).assert(hasText("3 fotos", substring = true))
        compose.onNodeWithTag(objectRowTag("id-13")).assert(hasText("1 foto", substring = true))
    }

    @Test
    fun `S017b RF-26 un punto sin fotos no lleva la marca`() {
        show(measured("14").summary())

        compose.onNodeWithTag(objectRowTag("id-14")).assert(!hasText("foto", substring = true))
    }

    @Test
    fun `S017b RF-26 la marca de fotos convive con las demas marcas`() {
        show(measured("15").summary().copy(photoCount = 2, edited = true, stakeCount = 1))

        compose.onNodeWithTag(objectRowTag("id-15")).assert(hasText("2 fotos", substring = true))
        compose.onNodeWithTag(objectRowTag("id-15")).assert(hasText("Replanteado 1 vez", substring = true))
    }
}
