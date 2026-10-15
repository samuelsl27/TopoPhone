package es.topophone.app.ui.sessions

import android.content.Context
import android.text.format.Formatter
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.StatefulTestApp
import es.topophone.app.ui.TopLevelDestination
import es.topophone.app.ui.navItem
import es.topophone.app.ui.receiver.LIST_SESSIONS_TAG
import es.topophone.app.ui.text
import es.topophone.core.designsystem.component.NAVIGATE_BACK_TAG
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Lista de sesiones (S003/RF-29 a RF-32, S003/RF-42). Interfaz en español (es-ES). */
@RunWith(AndroidJUnit4::class)
class SessionsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val zone = TimeZone.getDefault()

    private val recorded = SessionRow(
        id = "20261015-083000",
        startedAt = Instant.parse("2026-10-15T08:30:00Z"),
        durationS = 3_720,
        sizeBytes = 12_300_000,
        receiver = "reach-RS3",
        imported = false,
        inProgress = false,
    )
    private val imported = recorded.copy(
        id = "20261014-070000",
        startedAt = Instant.parse("2026-10-14T07:00:00Z"),
        durationS = 307,
        imported = true,
    )
    private val current = recorded.copy(
        id = "20261015-101500",
        startedAt = Instant.parse("2026-10-15T10:15:00Z"),
        durationS = 60,
        inProgress = true,
    )

    @Before
    fun madrid() = TimeZone.setDefault(TimeZone.getTimeZone("Europe/Madrid"))

    @After
    fun restore() = TimeZone.setDefault(zone)

    private fun show(rows: List<SessionRow>, actions: SessionsActions = SessionsActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                SessionsScreen(ThemeState.DEFAULT, {}, SessionsUiState(rows), actions, onNavigateBack = {})
            }
        }
    }

    private fun row(id: String) = compose.onNodeWithTag(sessionRowTag(id)).performScrollTo()

    @Test
    fun `S003 RF-29 cada sesion muestra inicio en hora local, duracion, tamano y receptor`() {
        show(listOf(recorded))

        row(recorded.id)
            .assert(hasText("10:30:00", substring = true))
            .assert(hasText(text(R.string.sessions_duration_hours, 1, 2)))
            .assert(hasText(Formatter.formatShortFileSize(context, 12_300_000), substring = true))
            .assert(hasText("reach-RS3", substring = true))
    }

    @Test
    fun `S003 RF-29 una sesion importada se marca como Importada y su duracion en minutos`() {
        show(listOf(imported))

        row(
            imported.id,
        ).assert(
            hasText(text(R.string.sessions_imported), substring = true),
        ).assert(hasText(text(R.string.sessions_duration_minutes, 5, 7)))
        text(R.string.sessions_imported) shouldBe "Importada"
    }

    @Test
    fun `S003 RF-29 sin sesiones se indica`() {
        show(emptyList())

        compose.onNodeWithText(text(R.string.receiver_no_session)).assertIsDisplayed()
    }

    @Test
    fun `S003 RF-30 se puede compartir cualquier sesion de la lista`() {
        var shared: String? = null
        show(listOf(current, recorded, imported), SessionsActions(onShare = { shared = it }))

        compose.onNodeWithTag(shareSessionTag(imported.id)).performScrollTo().performClick()

        shared shouldBe imported.id
    }

    @Test
    fun `S003 RF-31 borrar una sesion pide confirmacion y solo borra al confirmar`() {
        val deleted = mutableListOf<String>()
        show(listOf(recorded), SessionsActions(onDelete = { deleted += it }))

        compose.onNodeWithTag(deleteSessionTag(recorded.id)).performScrollTo().performClick()
        compose.onNodeWithTag(CANCEL_DELETE_TAG).performClick()
        deleted shouldBe emptyList()

        compose.onNodeWithTag(deleteSessionTag(recorded.id)).performScrollTo().performClick()
        compose.onNodeWithTag(CONFIRM_DELETE_TAG).performClick()
        deleted shouldBe listOf(recorded.id)
    }

    @Test
    fun `S003 RF-31 la sesion en curso se marca y no se puede borrar`() {
        show(listOf(current))

        row(current.id).assert(hasText(text(R.string.sessions_in_progress), substring = true))
        compose.onNodeWithTag(deleteSessionTag(current.id)).assertDoesNotExist()
    }

    @Test
    fun `S003 RF-29 la lista se abre desde la lista de receptores y se vuelve a Receptor`() {
        compose.setContent { StatefulTestApp() }
        compose.navItem(TopLevelDestination.RECEIVER).performClick()

        compose.onNodeWithTag(LIST_SESSIONS_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(SESSIONS_LIST_TAG).assertIsDisplayed()

        compose.onNodeWithTag(NAVIGATE_BACK_TAG).performClick()
        compose.onNodeWithTag(LIST_SESSIONS_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S003 RF-32 se puede reproducir una sesion de la lista`() {
        var played: String? = null
        show(listOf(recorded), SessionsActions(onPlay = { played = it }))

        compose.onNodeWithTag(playSessionTag(recorded.id)).performScrollTo().performClick()

        played shouldBe recorded.id
    }

    @Test
    fun `S003 RF-42 con un receptor conectado reproducir pide confirmacion antes de desconectarlo`() {
        val played = mutableListOf<String>()
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                SessionsScreen(
                    ThemeState.DEFAULT,
                    {
                    },
                    SessionsUiState(listOf(recorded), receiverConnected = true),
                    SessionsActions(onPlay = {
                        played +=
                            it
                    }),
                    {},
                )
            }
        }

        compose.onNodeWithTag(playSessionTag(recorded.id)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.sessions_replay_confirm_text)).assertIsDisplayed()
        compose.onNodeWithTag(CANCEL_REPLAY_TAG).performClick()
        played shouldBe emptyList()

        compose.onNodeWithTag(playSessionTag(recorded.id)).performScrollTo().performClick()
        compose.onNodeWithTag(CONFIRM_REPLAY_TAG).performClick()
        played shouldBe listOf(recorded.id)
    }
}
