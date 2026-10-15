package es.topophone.app.ui.sessions

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Importar un fichero NMEA desde la pantalla Sesiones (S003/RF-33, S003/RF-34). Interfaz en español (es-ES). */
@RunWith(AndroidJUnit4::class)
class SessionImportUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(state: SessionsUiState, actions: SessionsActions = SessionsActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { SessionsScreen(ThemeState.DEFAULT, {}, state, actions, onNavigateBack = {}) }
        }
    }

    @Test
    fun `S003 RF-33 la pantalla Sesiones ofrece importar un fichero NMEA`() {
        var import = false
        show(SessionsUiState(), SessionsActions(onImport = { import = true }))

        compose.onNodeWithTag(IMPORT_SESSION_TAG).performClick()

        import shouldBe true
    }

    @Test
    fun `S003 RF-34 si el fichero no tiene NMEA con hora se indica el motivo`() {
        show(SessionsUiState(message = ImportMessage.NO_NMEA))

        compose.onNodeWithText(text(R.string.sessions_import_no_nmea)).assertIsDisplayed()
        text(R.string.sessions_import_no_nmea) shouldBe "El fichero no tiene frases NMEA con hora."
    }

    @Test
    fun `S003 RF-34 si el fichero supera los 200 MB se indica el motivo`() {
        show(SessionsUiState(message = ImportMessage.TOO_LARGE))

        compose.onNodeWithText(text(R.string.sessions_import_too_large)).assertIsDisplayed()
        text(R.string.sessions_import_too_large) shouldBe "El fichero supera los 200 MB."
    }
}
