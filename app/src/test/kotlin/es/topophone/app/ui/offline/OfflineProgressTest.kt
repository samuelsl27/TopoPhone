package es.topophone.app.ui.offline

import android.view.View
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.offline.DownloadableSource
import es.topophone.core.model.offline.SourceKind
import es.topophone.core.model.offline.SourceState
import es.topophone.core.model.preferences.EffectiveTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La pantalla de la descarga: el progreso, el estado de cada fuente con texto, pausar, reanudar, cancelar con confirmación y la pantalla encendida (S037/RF-15 a RF-18). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class OfflineProgressTest {

    @get:Rule
    val compose = createComposeRule()

    private val pnoa = DownloadableSource(SourceKind.PNOA, "Ortofoto PNOA")
    private val ign = DownloadableSource(SourceKind.IGN_VECTOR, "Mapa IGN")
    private val current = mutableStateOf(DownloadState())
    private val calls = mutableListOf<String>()
    private var view: View? = null

    private val actions = OfflineProgressActions(
        onPause = { calls += "pause" },
        onResume = { calls += "resume" },
        onCancel = { calls += "cancel" },
    )

    private fun running(phase: DownloadPhase = DownloadPhase.RUNNING) = DownloadState(
        zoneId = "z1",
        name = "Obra – 14/11/2026",
        phase = phase,
        sources = listOf(
            SourceProgress(
                pnoa,
                SourceState.COMPLETE,
                expectedTiles = 40_000,
                completedTiles = 40_000,
                bytes = 900L * 1024 * 1024,
                fraction = 1f,
            ),
            SourceProgress(
                ign,
                SourceState.DOWNLOADING,
                expectedTiles = 10_000,
                completedTiles = 2_500,
                bytes = 30L * 1024 * 1024,
                fraction = 0.25f,
            ),
        ),
        currentSource = if (phase == DownloadPhase.RUNNING) ign else null,
    )

    private fun show(state: DownloadState) {
        current.value = state
        compose.setContent {
            view = LocalView.current
            TopoPhoneTheme(EffectiveTheme.LIGHT) { OfflineProgressContent(current.value, actions) }
        }
        compose.waitForIdle()
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag(OFFLINE_PROGRESS_TAG).performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
    }

    @Test
    fun `S037 RF-15 muestra la fase, el porcentaje, las teselas, los MB y la fuente en curso`() {
        show(running())

        compose.onNodeWithTag(OFFLINE_PROGRESS_PHASE_TAG).assertTextContains("Descargando")
        compose.onNodeWithTag(OFFLINE_PROGRESS_PERCENT_TAG).assertTextContains("62 %")
        compose.onNodeWithTag(OFFLINE_PROGRESS_TILES_TAG).assertTextContains("42.500 de 50.000 teselas")
        compose.onNodeWithTag(OFFLINE_PROGRESS_BYTES_TAG).assertTextContains("930 MB descargados")
        compose.onNodeWithTag(OFFLINE_PROGRESS_CURRENT_TAG).assertTextContains("Fuente en curso: Mapa IGN")
    }

    @Test
    fun `S037 RF-15 cada fuente dice su estado con texto`() {
        show(running())

        scrollTo(offlineProgressSourceTag("ign"))
        compose.onNodeWithTag(
            offlineProgressSourceTag("pnoa"),
        ).assertTextContains("Completa", substring = true).assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(offlineProgressSourceTag("ign")).assertTextContains("Descargando", substring = true)
    }

    @Test
    fun `S037 RF-19 una fuente incompleta dice cuantas teselas faltan y la descarga terminada ofrece Reintentar`() {
        val finished = running(DownloadPhase.FINISHED).let { state ->
            state.copy(sources = listOf(state.sources[0], SourceProgress(ign, SourceState.INCOMPLETE, 10_000, 9_000, failedTiles = 1_000)))
        }
        show(finished)

        compose.onNodeWithTag(OFFLINE_PROGRESS_PHASE_TAG).assertTextContains("terminada con fuentes incompletas", substring = true)
        scrollTo(OFFLINE_RETRY_TAG)
        compose.onNodeWithTag(offlineProgressSourceTag("ign")).assertTextContains("faltan", substring = true)
        compose.onNodeWithTag(OFFLINE_RETRY_TAG).assertHeightIsAtLeast(56.dp).performClick()
        calls shouldBe listOf("resume")
        compose.onNodeWithTag(OFFLINE_PAUSE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(OFFLINE_CANCEL_TAG).assertDoesNotExist()
    }

    @Test
    fun `S037 RF-16 mientras descarga se ofrece Pausar y Cancelar y no Reanudar`() {
        show(running())

        scrollTo(OFFLINE_CANCEL_TAG)
        compose.onNodeWithTag(OFFLINE_PAUSE_TAG).assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithTag(OFFLINE_RESUME_TAG).assertDoesNotExist()
        compose.onNodeWithTag(OFFLINE_CANCEL_TAG).assertHeightIsAtLeast(56.dp)

        calls shouldBe listOf("pause")
    }

    @Test
    fun `S037 RF-16 en pausa se ofrece Reanudar y la zona se ve incompleta`() {
        show(running(DownloadPhase.PAUSED))

        compose.onNodeWithTag(OFFLINE_PROGRESS_PHASE_TAG).assertTextContains("En pausa")
        scrollTo(OFFLINE_CANCEL_TAG)
        compose.onNodeWithTag(OFFLINE_RESUME_TAG).assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithTag(OFFLINE_PAUSE_TAG).assertDoesNotExist()

        calls shouldBe listOf("resume")
    }

    @Test
    fun `S037 RF-16 cancelar pide confirmacion y solo al aceptar llega a quien lo atiende`() {
        show(running())

        scrollTo(OFFLINE_CANCEL_TAG)
        compose.onNodeWithTag(OFFLINE_CANCEL_TAG).performClick()
        compose.onNodeWithTag(OFFLINE_CANCEL_DIALOG_TAG).assertIsDisplayed()
        compose.onNodeWithTag(OFFLINE_CANCEL_KEEP_TAG).assertHeightIsAtLeast(56.dp).performClick()
        calls shouldBe emptyList()
        compose.onNodeWithTag(OFFLINE_CANCEL_DIALOG_TAG).assertDoesNotExist()

        compose.onNodeWithTag(OFFLINE_CANCEL_TAG).performClick()
        compose.onNodeWithTag(OFFLINE_CANCEL_CONFIRM_TAG).assertHeightIsAtLeast(56.dp).performClick()

        calls shouldBe listOf("cancel")
    }

    @Test
    fun `S037 RF-17 la pantalla se mantiene encendida solo mientras descarga`() {
        show(running())
        view!!.keepScreenOn shouldBe true

        current.value = running(DownloadPhase.PAUSED)
        compose.waitForIdle()
        view!!.keepScreenOn shouldBe false

        current.value = running()
        compose.waitForIdle()
        view!!.keepScreenOn shouldBe true
    }

    @Test
    fun `S037 RF-17 al cerrar la pantalla de la descarga la pantalla ya no se mantiene encendida`() {
        val shown = mutableStateOf(true)
        current.value = running()
        compose.setContent {
            view = LocalView.current
            TopoPhoneTheme(EffectiveTheme.LIGHT) { if (shown.value) OfflineProgressContent(current.value, actions) }
        }
        compose.waitForIdle()
        view!!.keepScreenOn shouldBe true

        shown.value = false
        compose.waitForIdle()

        view!!.keepScreenOn shouldBe false
    }

    @Test
    fun `S037 RF-15 sin descarga lo dice y no ofrece nada`() {
        show(DownloadState())

        compose.onNodeWithTag(OFFLINE_PROGRESS_IDLE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(OFFLINE_PAUSE_TAG).assertDoesNotExist()
        view!!.keepScreenOn shouldBe false
    }
}

/** La misma pantalla en inglés (S037/RNF-T7). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en")
class OfflineProgressEnglishTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `S037 RNF-T7 the progress screen is in English with English separators`() {
        val pnoa = DownloadableSource(SourceKind.PNOA, "Ortofoto PNOA")
        val state = DownloadState(
            zoneId = "z1",
            name = "Site",
            phase = DownloadPhase.PAUSED,
            sources = listOf(SourceProgress(pnoa, SourceState.PAUSED, expectedTiles = 50_000, completedTiles = 12_500, fraction = 0.25f)),
        )
        compose.setContent { TopoPhoneTheme(EffectiveTheme.LIGHT) { OfflineProgressContent(state, OfflineProgressActions()) } }

        compose.onNodeWithTag(OFFLINE_PROGRESS_PHASE_TAG).assertTextContains("Paused")
        compose.onNodeWithTag(OFFLINE_PROGRESS_TILES_TAG).assertTextContains("12,500 of 50,000 tiles")
        compose.onNodeWithTag(OFFLINE_PROGRESS_TAG).performScrollToNode(hasTestTag(OFFLINE_RESUME_TAG))
        compose.onNodeWithTag(OFFLINE_RESUME_TAG).assertTextContains("Resume")
    }
}
