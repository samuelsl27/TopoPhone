package es.topophone.app.ui.offline

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.offline.DownloadableSource
import es.topophone.core.model.offline.OfflineAreaKind
import es.topophone.core.model.offline.SourceKind
import es.topophone.core.model.offline.SourceState
import es.topophone.core.model.preferences.EffectiveTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private fun stateDescription(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text)

private fun contentDescription(text: String) = SemanticsMatcher("ContentDescription = $text") {
    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true
}

private val isHeading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
private val isLiveRegion = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)

private val pnoa = DownloadableSource(SourceKind.PNOA, "Ortofoto PNOA")

/**
 * TalkBack en la hoja «Descargar zona»: los deslizadores dicen su nombre y su valor, no solo un porcentaje, y los títulos de sección son encabezados
 * para saltar de uno a otro (S037/RNF-T8).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class DownloadPlanAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(kind: OfflineAreaKind) {
        val state = DownloadPlanUiState(
            loaded = true,
            projectName = "Obra",
            areaKinds = listOf(OfflineAreaKind.RADIUS, OfflineAreaKind.PROJECT),
            areaKind = kind,
            radiusM = 500.0,
            marginM = 500.0,
            sources = listOf(SourceChoice(pnoa, true)),
        )
        compose.setContent { TopoPhoneTheme(EffectiveTheme.LIGHT) { DownloadPlanContent(state, DownloadPlanActions()) } }
        compose.waitForIdle()
    }

    @Test
    fun `S037 RNF-T8 el deslizador del radio dice su nombre y su valor`() {
        show(OfflineAreaKind.RADIUS)

        compose.onNodeWithTag(OFFLINE_RADIUS_TAG).assert(stateDescription("Radio: 500 m"))
    }

    @Test
    fun `S037 RNF-T8 el deslizador del margen dice su nombre y su valor`() {
        show(OfflineAreaKind.PROJECT)

        compose.onNodeWithTag(OFFLINE_MARGIN_TAG).assert(stateDescription("Margen: 500 m"))
    }

    @Test
    fun `S037 RNF-T8 el titulo y las secciones de la hoja son encabezados`() {
        show(OfflineAreaKind.RADIUS)

        compose.onNodeWithText("Descargar zona").assert(isHeading)
        compose.onNodeWithText("Área").assert(isHeading)
        compose.onNodeWithTag(OFFLINE_PLAN_TAG).performScrollToNode(hasTestTag(offlineSourceTag(pnoa.key)))
        compose.onNodeWithText("Fuentes").assert(isHeading)
    }
}

/** TalkBack en «Mapas sin conexión»: el límite dice su valor, la barra de ocupación tiene nombre y el título es un encabezado (S037/RNF-T8). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class OfflineZonesAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { OfflineZonesContent(OfflineZonesUiState(loaded = true), OfflineZonesActions()) }
        }
        compose.waitForIdle()
    }

    @Test
    fun `S037 RNF-T8 el deslizador del limite dice su nombre y su valor`() {
        show()

        compose.onNodeWithTag(OFFLINE_ZONES_LIMIT_TAG).assert(stateDescription("Límite de almacenamiento: 2 GB"))
    }

    @Test
    fun `S037 RNF-T8 la barra de ocupacion tiene nombre y el titulo es un encabezado`() {
        show()

        compose.onNodeWithTag(OFFLINE_ZONES_USAGE_BAR_TAG).assert(contentDescription("Espacio ocupado por los mapas sin conexión"))
        compose.onNodeWithText("Mapas sin conexión").assert(isHeading)
    }
}

/** TalkBack en la pantalla de la descarga: la barra tiene nombre, la fase se anuncia al cambiar y el título es un encabezado (S037/RNF-T8). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class OfflineProgressAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private val running = DownloadState(
        zoneId = "z1",
        name = "Obra",
        phase = DownloadPhase.RUNNING,
        sources = listOf(SourceProgress(pnoa, SourceState.DOWNLOADING, expectedTiles = 100, completedTiles = 25, fraction = 0.25f)),
        currentSource = pnoa,
    )

    private fun show() {
        compose.setContent { TopoPhoneTheme(EffectiveTheme.LIGHT) { OfflineProgressContent(running, OfflineProgressActions()) } }
        compose.waitForIdle()
    }

    @Test
    fun `S037 RNF-T8 la barra de progreso tiene nombre`() {
        show()

        compose.onNodeWithTag(OFFLINE_PROGRESS_BAR_TAG).assert(contentDescription("Progreso de la descarga"))
    }

    @Test
    fun `S037 RNF-T8 la fase se anuncia al cambiar y el titulo es un encabezado`() {
        show()

        compose.onNodeWithTag(OFFLINE_PROGRESS_PHASE_TAG).assert(isLiveRegion).assertTextContains("Descargando")
        compose.onNodeWithText("Descarga de la zona").assert(isHeading)
    }
}

/** Los mismos nombres de TalkBack en inglés (S037/RNF-T7, RNF-T8). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en")
class OfflineAccessibilityEnglishTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `S037 RNF-T8 the sliders and the bars have their name in English`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { OfflineZonesContent(OfflineZonesUiState(loaded = true), OfflineZonesActions()) }
        }

        compose.onNodeWithTag(OFFLINE_ZONES_LIMIT_TAG).assert(stateDescription("Storage limit: 2 GB"))
        compose.onNodeWithTag(OFFLINE_ZONES_USAGE_BAR_TAG).assert(contentDescription("Space used by offline maps"))
    }
}
