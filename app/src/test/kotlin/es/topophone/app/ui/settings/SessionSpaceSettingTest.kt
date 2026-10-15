package es.topophone.app.ui.settings

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.StatefulTestApp
import es.topophone.app.ui.TopLevelDestination
import es.topophone.app.ui.navItem
import es.topophone.app.ui.text
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Espacio para sesiones en Ajustes (S003/RF-26). Interfaz en español (es-ES). */
@RunWith(AndroidJUnit4::class)
class SessionSpaceSettingTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `S003 RF-26 se elige el espacio para sesiones entre 100 MB y 2 GB y se ve al momento`() {
        var chosen: Int? = null
        compose.setContent { StatefulTestApp(onSelectSessionSpace = { chosen = it }) }
        compose.navItem(TopLevelDestination.SETTINGS).performClick()

        compose.onNodeWithTag(SESSION_SPACE_SETTING_TAG).performScrollTo()
            .assert(hasText(text(R.string.settings_session_space)))
            .assert(hasText("500 MB"))
            .performClick()
        listOf(100, 250, 500, 1000, 2000).forEach {
            compose.onNodeWithTag(sessionSpaceOptionTag(it)).assert(hasText(sessionSpaceLabel(it)))
        }
        compose.onNodeWithTag(sessionSpaceOptionTag(1000)).performClick()

        chosen shouldBe 1000
        compose.onNodeWithTag(SESSION_SPACE_SETTING_TAG).performScrollTo().assert(hasText("1 GB"))
    }

    private fun sessionSpaceLabel(mb: Int) = if (mb < 1000) "$mb MB" else "${mb / 1000} GB"
}
