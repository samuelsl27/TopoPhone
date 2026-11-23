package es.topophone.app.ui.crs

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.localization.LocalizationParams
import es.topophone.core.model.localization.LocalizedCrs
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Los SC guardados con una localización salen en Mis SC con la marca «Localizado» y se pueden borrar (S039/RF-24). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class MyCrsLocalizedTest {

    @get:Rule
    val compose = createComposeRule()

    private val saved = LocalizedCrs("c1", "Plano de obra", ProjectCrs.ETRS89_UTM30, HeightSystem.ORTHOMETRIC, LocalizationParams(), 1)

    @Test
    fun `S039 RF-24 Mis SC lista el SC localizado con su marca y permite borrarlo`() {
        val calls = mutableListOf<String>()
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                MyCrsScreen(
                    ThemeState.DEFAULT,
                    {},
                    MyCrsUiState(loaded = true, localized = listOf(saved)),
                    MyCrsActions(onDeleteLocalized = { calls += it }),
                )
            }
        }

        compose.onNodeWithTag(myCrsLocalizedTag("c1")).assertIsDisplayed().assert(hasAnyDescendant(hasText("Localizado", substring = true)))
        compose.onNodeWithTag(myCrsLocalizedTag("c1")).assert(hasAnyDescendant(hasText("Plano de obra", substring = true)))
        compose.onNodeWithTag(myCrsLocalizedDeleteTag("c1")).performClick()

        calls shouldBe listOf("c1")
        compose.onNodeWithTag(MY_CRS_EMPTY_TAG).assertDoesNotExist()
    }

    @Test
    fun `S039 RF-24 sin SC propios ni localizados Mis SC sigue diciendo que esta vacio`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                MyCrsScreen(ThemeState.DEFAULT, {}, MyCrsUiState(loaded = true), MyCrsActions())
            }
        }

        compose.onNodeWithTag(MY_CRS_EMPTY_TAG).assertIsDisplayed()
    }
}
