package es.topophone.app.ui.projects

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** El formulario del proyecto avisa de que con una localización activa no se cambia el SC, las alturas ni se pasa a terreno (S039/RF-22, RF-27). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class LocalizationLockedNoticeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(locked: Boolean) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                CrsChoices(
                    crs = ProjectCrs.ETRS89_UTM30,
                    heightSystem = HeightSystem.ORTHOMETRIC,
                    datumLocked = true,
                    localizationLocked = locked,
                    onCrs = {},
                    onHeight = {},
                )
            }
        }
    }

    @Test
    fun `S039 RF-27 el aviso de la localizacion activa se ve con su texto`() {
        show(locked = true)

        compose.onNodeWithTag(CRS_LOCALIZATION_LOCKED_TAG).assertIsDisplayed()
        compose.onNodeWithTag(CRS_LOCALIZATION_LOCKED_TAG).assert(hasText("quítala", substring = true))
    }

    @Test
    fun `S039 RF-27 sin bloqueo no hay aviso`() {
        show(locked = false)

        compose.onNodeWithTag(CRS_LOCALIZATION_LOCKED_TAG).assertDoesNotExist()
    }
}
