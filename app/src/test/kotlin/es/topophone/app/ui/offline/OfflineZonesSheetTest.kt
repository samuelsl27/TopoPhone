package es.topophone.app.ui.offline

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.offline.DetailLevel
import es.topophone.core.model.offline.DownloadableSource
import es.topophone.core.model.offline.OfflineLimits
import es.topophone.core.model.offline.SourceKind
import es.topophone.core.model.offline.ZoneState
import es.topophone.core.model.preferences.EffectiveTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La hoja «Mapas sin conexión»: las zonas con su estado, la ocupación y el límite, y sus acciones con confirmación (S037/RF-24 a RF-27, RF-31). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class OfflineZonesSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val mb = 1024L * 1024
    private val pnoa = DownloadableSource(SourceKind.PNOA, "Ortofoto PNOA")
    private val ign = DownloadableSource(SourceKind.IGN_VECTOR, "Mapa IGN")
    private val current = mutableStateOf(OfflineZonesUiState())
    private val calls = mutableListOf<String>()
    private val actions = OfflineZonesActions(
        onLimit = { calls += "limit:${it / mb}" },
        onDownloadZone = { calls += "download" },
        onSeeProgress = { calls += "progress" },
        onUpdate = { calls += "update:$it" },
        onResume = { calls += "resume:$it" },
        onRename = { id, name -> calls += "rename:$id:$name" },
        onRequestDelete = { calls += "request:$it" },
        onConfirmDelete = { calls += "confirm" },
        onDismissDelete = { calls += "dismiss" },
    )

    private fun zone(
        id: String,
        name: String,
        state: ZoneState,
        at: Long? = 1_794_657_600_000L,
        fraction: Float = 1f,
        size: Long = 42 * mb,
    ) = ZoneItem(
        zoneId = id,
        projectId = "p1",
        name = name,
        sources = listOf(pnoa, ign),
        detail = DetailLevel.HIGH,
        sizeBytes = size,
        downloadedAtMs = at,
        state = state,
        fraction = fraction,
    )

    private fun show(state: OfflineZonesUiState) {
        current.value = state
        compose.setContent { TopoPhoneTheme(EffectiveTheme.LIGHT) { OfflineZonesContent(current.value, actions) } }
        compose.waitForIdle()
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag(OFFLINE_ZONES_TAG).performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
    }

    private val complete = zone("a", "Obra norte", ZoneState.COMPLETE)
    private val incomplete = zone("b", "A medias", ZoneState.INCOMPLETE, at = null, fraction = 0.42f, size = 7 * mb)
    private val updating = zone("c", "Renovando", ZoneState.UPDATING)

    @Test
    fun `S037 RF-24 sin zonas lo dice, da la ocupacion y deja descargar una`() {
        show(OfflineZonesUiState(loaded = true, totalBytes = 0))

        compose.onNodeWithTag(OFFLINE_ZONES_EMPTY_TAG).assertTextContains("no tiene zonas sin conexión", substring = true)
        compose.onNodeWithTag(OFFLINE_ZONES_USAGE_TAG).assertTextContains("Ocupado por los mapas sin conexión: 0 KB de 2 GB")
        compose.onNodeWithTag(OFFLINE_ZONES_DOWNLOAD_TAG).assertIsEnabled().assertHeightIsAtLeast(56.dp).performClick()

        calls shouldBe listOf("download")
    }

    @Test
    fun `S037 RF-24 cada zona dice su nombre, sus fuentes, su nivel, su tamano real, su fecha y su estado`() {
        show(OfflineZonesUiState(loaded = true, zones = listOf(complete), totalBytes = 42 * mb))

        compose.onNodeWithTag(offlineZoneTag("a")).assertTextContains("Obra norte", substring = true)
        compose.onNodeWithTag(offlineZoneTag("a")).assertTextContains("Fuentes: Ortofoto PNOA, Mapa IGN", substring = true)
        compose.onNodeWithTag(offlineZoneTag("a")).assertTextContains("Nivel de detalle: Alto (hasta 1:1.000)", substring = true)
        compose.onNodeWithTag(offlineZoneSizeTag("a"), useUnmergedTree = true).assertTextContains("Tamaño: 42 MB")
        compose.onNodeWithTag(offlineZoneDateTag("a"), useUnmergedTree = true).assertTextContains("Descargada el", substring = true)
        compose.onNodeWithTag(offlineZoneDateTag("a"), useUnmergedTree = true).assertTextContains("2026", substring = true)
        compose.onNodeWithTag(offlineZoneStateTag("a"), useUnmergedTree = true).assertTextContains("Completa")
    }

    @Test
    fun `S037 RF-24 una zona incompleta dice su porcentaje y que no ha terminado, y una que se actualiza lo dice`() {
        show(OfflineZonesUiState(loaded = true, zones = listOf(incomplete, updating)))

        compose.onNodeWithTag(offlineZoneStateTag("b"), useUnmergedTree = true).assertTextContains("Incompleta: 42 %")
        compose.onNodeWithTag(offlineZoneDateTag("b"), useUnmergedTree = true).assertTextContains("Sin terminar de descargar")
        compose.onNodeWithTag(offlineZoneStateTag("c"), useUnmergedTree = true).assertTextContains("Actualizando")
    }

    @Test
    fun `S037 RF-26 una zona completa se actualiza y una incompleta se reanuda, y una que se actualiza no ofrece ninguna`() {
        show(OfflineZonesUiState(loaded = true, zones = listOf(complete, incomplete, updating)))

        scrollTo(offlineZoneUpdateTag("a"))
        compose.onNodeWithTag(offlineZoneUpdateTag("a")).assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithTag(offlineZoneResumeTag("a")).assertDoesNotExist()
        scrollTo(offlineZoneResumeTag("b"))
        compose.onNodeWithTag(offlineZoneResumeTag("b")).assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithTag(offlineZoneUpdateTag("b")).assertDoesNotExist()
        scrollTo(offlineZoneRenameTag("c"))
        compose.onNodeWithTag(offlineZoneUpdateTag("c")).assertDoesNotExist()
        compose.onNodeWithTag(offlineZoneResumeTag("c")).assertDoesNotExist()

        calls shouldBe listOf("update:a", "resume:b")
    }

    @Test
    fun `S037 RF-25 renombrar abre un dialogo con el nombre de ahora y al guardar llega el nombre nuevo`() {
        show(OfflineZonesUiState(loaded = true, zones = listOf(complete)))

        scrollTo(offlineZoneRenameTag("a"))
        compose.onNodeWithTag(offlineZoneRenameTag("a")).assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithTag(OFFLINE_RENAME_FIELD_TAG).assertTextContains("Obra norte")
        compose.onNodeWithTag(OFFLINE_RENAME_FIELD_TAG).performTextClearance()
        compose.onNodeWithTag(OFFLINE_RENAME_SAVE_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(OFFLINE_RENAME_FIELD_TAG).performTextInput("Obra sur")
        compose.onNodeWithTag(OFFLINE_RENAME_SAVE_TAG).assertHeightIsAtLeast(56.dp).performClick()

        calls shouldBe listOf("rename:a:Obra sur")
        compose.onNodeWithTag(OFFLINE_RENAME_FIELD_TAG).assertDoesNotExist()
    }

    @Test
    fun `S037 RF-25 cancelar el dialogo de renombrar no cambia nada`() {
        show(OfflineZonesUiState(loaded = true, zones = listOf(complete)))

        scrollTo(offlineZoneRenameTag("a"))
        compose.onNodeWithTag(offlineZoneRenameTag("a")).performClick()
        compose.onNodeWithTag(OFFLINE_RENAME_CANCEL_TAG).assertHeightIsAtLeast(56.dp).performClick()

        calls shouldBe emptyList()
        compose.onNodeWithTag(OFFLINE_RENAME_FIELD_TAG).assertDoesNotExist()
    }

    @Test
    fun `S037 RF-27 borrar pide confirmacion con el nombre y el espacio que se libera`() {
        show(OfflineZonesUiState(loaded = true, zones = listOf(complete)))
        scrollTo(offlineZoneDeleteTag("a"))
        compose.onNodeWithTag(offlineZoneDeleteTag("a")).assertHeightIsAtLeast(56.dp).performClick()
        calls shouldBe listOf("request:a")

        current.value = current.value.copy(pendingDelete = complete)
        compose.waitForIdle()
        compose.onNodeWithTag(OFFLINE_DELETE_DIALOG_TAG).assertIsDisplayed()
        compose.onNodeWithTag(OFFLINE_DELETE_CANCEL_TAG).assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithTag(OFFLINE_DELETE_CONFIRM_TAG).assertHeightIsAtLeast(56.dp).performClick()

        calls shouldBe listOf("request:a", "dismiss", "confirm")
    }

    @Test
    fun `S037 RF-27 el dialogo de borrar dice cuanto se libera`() {
        show(OfflineZonesUiState(loaded = true, zones = listOf(complete), pendingDelete = complete))

        compose.onNodeWithTag(OFFLINE_DELETE_DIALOG_TAG).assertIsDisplayed()
        compose.onNodeWithText("Se borra «Obra norte» y se liberan 42 MB.", substring = true).assertIsDisplayed()
    }

    @Test
    fun `S037 RF-31 el limite se ve, el deslizador va de 200 MB a 10 GB y cambiarlo llega a quien lo atiende`() {
        show(OfflineZonesUiState(loaded = true, limitBytes = OfflineLimits.DEFAULT_STORAGE_LIMIT_BYTES))

        compose.onNodeWithTag(OFFLINE_ZONES_LIMIT_VALUE_TAG).assertTextContains("Límite de almacenamiento: 2 GB")
        compose.onNodeWithTag(OFFLINE_ZONES_LIMIT_TAG).performSemanticsAction(SemanticsActions.SetProgress) { it(5f) }
        compose.onNodeWithTag(OFFLINE_ZONES_LIMIT_TAG).performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }

        calls shouldBe listOf("limit:10240", "limit:200")
    }

    @Test
    fun `S037 RF-15 con una descarga en curso la hoja la dice, lleva a su progreso y no deja empezar otra ni actualizar`() {
        val running = DownloadState(zoneId = "x", name = "En curso", phase = DownloadPhase.RUNNING)
        show(OfflineZonesUiState(loaded = true, zones = listOf(complete, incomplete), download = running))

        compose.onNodeWithTag(OFFLINE_ZONES_RUNNING_TAG).assertTextContains("Hay una descarga en curso: En curso", substring = true)
        compose.onNodeWithTag(OFFLINE_ZONES_SEE_PROGRESS_TAG).assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithTag(OFFLINE_ZONES_DOWNLOAD_TAG).assertIsNotEnabled()
        scrollTo(offlineZoneUpdateTag("a"))
        compose.onNodeWithTag(offlineZoneUpdateTag("a")).assertIsNotEnabled()
        scrollTo(offlineZoneResumeTag("b"))
        compose.onNodeWithTag(offlineZoneResumeTag("b")).assertIsNotEnabled()

        calls shouldBe listOf("progress")
    }
}

/** La tarjeta de la descarga en curso solo sale mientras se descarga o está en pausa (S037/RF-15, RF-16); visto en el emulador tras terminar una descarga. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class OfflineZonesRunningCardTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(phase: DownloadPhase) {
        val download = DownloadState(zoneId = "x", name = "Obra", phase = phase)
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                OfflineZonesContent(OfflineZonesUiState(loaded = true, download = download), OfflineZonesActions())
            }
        }
    }

    @Test
    fun `S037 RF-16 una descarga en pausa sigue en la tarjeta de la descarga en curso`() {
        show(DownloadPhase.PAUSED)

        compose.onNodeWithTag(OFFLINE_ZONES_RUNNING_TAG).assertIsDisplayed()
    }

    @Test
    fun `S037 RF-15 una descarga terminada ya no sale en la tarjeta y deja descargar otra zona`() {
        show(DownloadPhase.FINISHED)

        compose.onNodeWithTag(OFFLINE_ZONES_RUNNING_TAG).assertDoesNotExist()
        compose.onNodeWithTag(OFFLINE_ZONES_DOWNLOAD_TAG).assertIsEnabled()
    }

    @Test
    fun `S037 RF-15 sin ninguna descarga tampoco sale la tarjeta`() {
        show(DownloadPhase.IDLE)

        compose.onNodeWithTag(OFFLINE_ZONES_RUNNING_TAG).assertDoesNotExist()
    }
}

/** La misma hoja en inglés (S037/RNF-T7). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en")
class OfflineZonesSheetEnglishTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `S037 RNF-T7 the offline maps sheet is in English`() {
        val zone = ZoneItem(
            "a",
            "p1",
            "Site north",
            listOf(DownloadableSource(SourceKind.PNOA, "Ortofoto PNOA")),
            DetailLevel.NORMAL,
            12L * 1024 * 1024,
            null,
            ZoneState.INCOMPLETE,
            0.5f,
        )
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                OfflineZonesContent(OfflineZonesUiState(loaded = true, zones = listOf(zone)), OfflineZonesActions())
            }
        }

        compose.onNodeWithTag(OFFLINE_ZONES_DOWNLOAD_TAG).assertTextContains("Download zone")
        compose.onNodeWithTag(OFFLINE_ZONES_TAG).performScrollToNode(hasTestTag(offlineZoneTag("a")))
        compose.onNodeWithTag(offlineZoneStateTag("a"), useUnmergedTree = true).assertTextContains("Incomplete: 50 %")
        compose.onNodeWithTag(offlineZoneDateTag("a"), useUnmergedTree = true).assertTextContains("Download not finished")
        compose.onNodeWithTag(offlineZoneTag("a")).assertTextContains("Sources: PNOA orthophoto", substring = true)
    }
}
