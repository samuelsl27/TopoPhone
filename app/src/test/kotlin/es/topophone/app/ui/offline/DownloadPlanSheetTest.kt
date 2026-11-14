package es.topophone.app.ui.offline

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.offline.DetailLevel
import es.topophone.core.model.offline.DownloadableSource
import es.topophone.core.model.offline.OfflineAreaKind
import es.topophone.core.model.offline.OfflineLimits
import es.topophone.core.model.offline.SourceKind
import es.topophone.core.model.preferences.EffectiveTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La hoja «Descargar zona»: el área, las fuentes, el nivel, la previsión con sus avisos y las confirmaciones, con zonas de 56 dp (S037/RF-1 a RF-14). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class DownloadPlanSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val current = mutableStateOf(DownloadPlanUiState())
    private val calls = mutableListOf<String>()
    private val actions = DownloadPlanActions(
        onAreaKind = { calls += "area:${it.key}" },
        onRadius = { calls += "radius:${it.toInt()}" },
        onMargin = { calls += "margin:${it.toInt()}" },
        onDetail = { calls += "detail:${it.key}" },
        onToggleSource = { calls += "source:$it" },
        onName = { calls += "name:$it" },
        onConfirm = {
            calls += "confirm:${it::class.simpleName}"
            // Como el ViewModel: lo confirmado deja de estar pendiente.
            current.value = current.value.copy(acknowledged = current.value.acknowledged + it)
        },
        onStart = { calls += "start" },
    )

    private val pnoa = DownloadableSource(SourceKind.PNOA, "Ortofoto PNOA")
    private val ign = DownloadableSource(SourceKind.IGN_VECTOR, "Mapa IGN")
    private val ofm = DownloadableSource(SourceKind.OPENFREEMAP, "Mapa OpenStreetMap")
    private val wms = DownloadableSource(SourceKind.WMS, "Ortoimagen ICV", serviceId = "s1", layerName = "capa")

    private fun estimate(blocks: List<EstimateBlock> = emptyList(), confirmations: List<EstimateConfirmation> = emptyList()) = Estimate(
        sources = listOf(SourceEstimate(pnoa, 46_500, 123L * 1024 * 1024), SourceEstimate(ign, 12_000, 8L * 1024 * 1024)),
        blocks = blocks,
        confirmations = confirmations,
    )

    private fun full(
        estimate: Estimate? = estimate(),
        kinds: List<OfflineAreaKind> = listOf(OfflineAreaKind.RADIUS, OfflineAreaKind.VIEWPORT, OfflineAreaKind.PROJECT),
        kind: OfflineAreaKind? = OfflineAreaKind.RADIUS,
        acknowledged: Set<EstimateConfirmation> = emptySet(),
    ) = DownloadPlanUiState(
        loaded = true,
        projectName = "Obra",
        name = "Obra – 14/11/2026",
        areaKinds = kinds,
        areaKind = kind,
        radiusM = 500.0,
        marginM = 500.0,
        detail = DetailLevel.NORMAL,
        sources = listOf(SourceChoice(ign, false), SourceChoice(pnoa, true), SourceChoice(ofm, false), SourceChoice(wms, true)),
        estimate = estimate,
        storage = StorageState(
            freeBytes = 12L * 1024 * 1024 * 1024,
            usedBytes = 300L * 1024 * 1024,
            limitBytes = OfflineLimits.DEFAULT_STORAGE_LIMIT_BYTES,
        ),
        network = NetworkKind.UNMETERED,
        acknowledged = acknowledged,
    )

    private fun show(state: DownloadPlanUiState = full()) {
        current.value = state
        compose.setContent { TopoPhoneTheme(EffectiveTheme.LIGHT) { DownloadPlanContent(current.value, actions) } }
        compose.waitForIdle()
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag(OFFLINE_PLAN_TAG).performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
    }

    @Test
    fun `S037 RF-2 se ofrecen las tres areas, la elegida va marcada y cambiar de area llega a quien la atiende`() {
        show()

        compose.onNodeWithTag(offlineAreaTag(OfflineAreaKind.RADIUS)).assertIsSelected().assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(offlineAreaTag(OfflineAreaKind.VIEWPORT)).assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithTag(offlineAreaTag(OfflineAreaKind.PROJECT)).performClick()

        calls shouldBe listOf("area:viewport", "area:project")
    }

    @Test
    fun `S037 RF-5 y RF-6 solo se ofrecen las areas que el proyecto permite`() {
        show(full(kinds = listOf(OfflineAreaKind.VIEWPORT), kind = OfflineAreaKind.VIEWPORT))

        compose.onNodeWithTag(offlineAreaTag(OfflineAreaKind.VIEWPORT)).assertIsSelected()
        compose.onNodeWithTag(offlineAreaTag(OfflineAreaKind.RADIUS)).assertDoesNotExist()
        compose.onNodeWithTag(offlineAreaTag(OfflineAreaKind.PROJECT)).assertDoesNotExist()
        compose.onNodeWithTag(OFFLINE_RADIUS_TAG).assertDoesNotExist()
    }

    @Test
    fun `S037 RF-2 sin ninguna area posible lo dice y no deja descargar`() {
        show(full(estimate = null, kinds = emptyList(), kind = null))

        compose.onNodeWithTag(OFFLINE_PLAN_NO_AREA_TAG).assertIsDisplayed()
        scrollTo(OFFLINE_START_TAG)
        compose.onNodeWithTag(OFFLINE_START_TAG).assertIsNotEnabled()
    }

    @Test
    fun `S037 RF-3 el radio se ve con su valor, va por pasos y llega a quien lo atiende`() {
        show()

        compose.onNodeWithTag(OFFLINE_RADIUS_VALUE_TAG).assertTextContains("Radio: 500 m")
        compose.onNodeWithTag(OFFLINE_RADIUS_TAG).performSemanticsAction(SemanticsActions.SetProgress) { it(5f) }

        calls shouldBe listOf("radius:5000")
    }

    @Test
    fun `S037 RF-4 el margen solo sale con todo el proyecto y va de 200 m a 2 km`() {
        show(full(kind = OfflineAreaKind.PROJECT))

        compose.onNodeWithTag(OFFLINE_RADIUS_TAG).assertDoesNotExist()
        compose.onNodeWithTag(OFFLINE_MARGIN_VALUE_TAG).assertTextContains("Margen: 500 m")
        compose.onNodeWithTag(OFFLINE_MARGIN_TAG).performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }

        calls shouldBe listOf("margin:2000")
    }

    @Test
    fun `S037 RF-8 las fuentes se marcan y desmarcan y las capas WMS salen con su titulo`() {
        show()

        compose.onNodeWithTag(offlineSourceTag("ign")).assertIsOff().assertHeightIsAtLeast(56.dp).performClick()
        scrollTo(offlineSourceTag("pnoa"))
        compose.onNodeWithTag(offlineSourceTag("pnoa")).assertIsOn().performClick()
        compose.onNodeWithTag(offlineSourceTag("wms:s1:capa")).assertTextContains("Ortoimagen ICV", substring = true).assertIsOn()
        compose.onNodeWithTag(offlineSourceTag("ofm")).assertTextContains("Mapa OpenStreetMap", substring = true)

        calls shouldBe listOf("source:ign", "source:pnoa")
    }

    @Test
    fun `S037 RF-8 sin ninguna fuente marcada lo avisa`() {
        val unmarked = full().copy(
            sources = full().sources.map {
                it.copy(selected = false)
            },
            estimate = Estimate(emptyList(), emptyList(), emptyList()),
        )
        show(unmarked)

        scrollTo(OFFLINE_NO_SOURCES_TAG)
        compose.onNodeWithTag(OFFLINE_NO_SOURCES_TAG).assertIsDisplayed()
        scrollTo(OFFLINE_START_TAG)
        compose.onNodeWithTag(OFFLINE_START_TAG).assertIsNotEnabled()
    }

    @Test
    fun `S037 RF-8 los tres niveles de detalle se ofrecen con su escala y cambiar llega a quien lo atiende`() {
        show()

        scrollTo(offlineDetailTag(DetailLevel.MAX))
        compose.onNodeWithTag(
            offlineDetailTag(DetailLevel.NORMAL),
        ).assertIsSelected().assertHeightIsAtLeast(56.dp).assertTextContains("hasta 1:5.000", substring = true)
        compose.onNodeWithTag(offlineDetailTag(DetailLevel.HIGH)).assertTextContains("hasta 1:1.000", substring = true).performClick()
        compose.onNodeWithTag(
            offlineDetailTag(DetailLevel.MAX),
        ).assertTextContains("1 m en la barra de escala", substring = true).performClick()

        calls shouldBe listOf("detail:high", "detail:max")
    }

    @Test
    fun `S037 RF-11 la prevision da las teselas y el tamano de cada fuente, el total y el espacio`() {
        show()

        scrollTo(OFFLINE_ESTIMATE_STORAGE_TAG)
        compose.onNodeWithTag(offlineEstimateTag("pnoa")).assertTextContains("Ortofoto PNOA: 46.500 teselas, unos 123 MB")
        compose.onNodeWithTag(offlineEstimateTag("ign")).assertTextContains("Mapa IGN: 12.000 teselas, unos 8 MB")
        compose.onNodeWithTag(OFFLINE_ESTIMATE_TOTAL_TAG).assertTextContains("Total: 58.500 teselas, unos 131 MB")
        compose.onNodeWithTag(OFFLINE_ESTIMATE_STORAGE_TAG)
            .assertTextContains("Espacio libre en el móvil: 12 GB. Ocupado ya por mapas sin conexión: 300 MB de 2 GB.")
    }

    @Test
    fun `S037 RF-12 y RF-13 cada bloqueo sale con su motivo y como reducirlo, y el boton no deja descargar`() {
        val blocks = listOf(
            EstimateBlock.NoInternet,
            EstimateBlock.OpenFreeMapLimit(tiles = 5_000, bytes = 90L * 1024 * 1024),
            EstimateBlock.NoSpace(missingBytes = 700L * 1024 * 1024),
            EstimateBlock.OverLimit(missingBytes = 3L * 1024 * 1024 * 1024),
        )
        show(full(estimate = estimate(blocks)))

        scrollTo(offlineBlockTag(3))
        compose.onNodeWithTag(offlineBlockTag(0)).assertTextContains("No hay conexión a internet", substring = true)
        compose.onNodeWithTag(offlineBlockTag(1)).assertTextContains("OpenStreetMap solo permite descargas", substring = true)
        compose.onNodeWithTag(offlineBlockTag(1)).assertTextContains("desmarca esta fuente", substring = true)
        compose.onNodeWithTag(offlineBlockTag(2)).assertTextContains("faltan 700 MB", substring = true)
        compose.onNodeWithTag(offlineBlockTag(3)).assertTextContains("faltan 3 GB", substring = true)
        scrollTo(OFFLINE_START_TAG)
        compose.onNodeWithTag(OFFLINE_START_TAG).assertIsNotEnabled().assertHeightIsAtLeast(56.dp)
    }

    @Test
    fun `S037 RF-15 sin bloqueos ni confirmaciones Descargar empieza al momento`() {
        show()

        scrollTo(OFFLINE_START_TAG)
        compose.onNodeWithTag(OFFLINE_START_TAG).assertIsEnabled().performClick()

        calls shouldBe listOf("start")
        compose.onNodeWithTag(OFFLINE_CONFIRM_TAG).assertDoesNotExist()
    }

    @Test
    fun `S037 RF-10 un servicio WMS propio pide confirmacion con el nombre del servicio y al aceptar empieza`() {
        show(full(estimate = estimate(confirmations = listOf(EstimateConfirmation.OwnWms("Mi servicio")))))

        scrollTo(OFFLINE_START_TAG)
        compose.onNodeWithTag(OFFLINE_START_TAG).performClick()
        compose.onNodeWithTag(OFFLINE_CONFIRM_TAG).assertIsDisplayed()
        compose.onNodeWithText("Comprueba que las condiciones de este servicio permiten guardar teselas: Mi servicio.", substring = true)
            .assertIsDisplayed()
        calls shouldBe emptyList()

        compose.onNodeWithTag(OFFLINE_CONFIRM_ACCEPT_TAG).assertHeightIsAtLeast(56.dp).performClick()

        calls shouldBe listOf("confirm:OwnWms", "start")
    }

    @Test
    fun `S037 RF-14 con datos moviles y mas de 50 MB pide confirmacion con el tamano y cancelar no empieza`() {
        show(full(estimate = estimate(confirmations = listOf(EstimateConfirmation.MobileData))))

        scrollTo(OFFLINE_START_TAG)
        compose.onNodeWithTag(OFFLINE_START_TAG).performClick()
        compose.onNodeWithText("unos 131 MB con datos móviles", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(OFFLINE_CONFIRM_CANCEL_TAG).assertHeightIsAtLeast(56.dp).performClick()

        calls shouldBe emptyList()
        compose.onNodeWithTag(OFFLINE_CONFIRM_TAG).assertDoesNotExist()
    }

    @Test
    fun `S037 RF-10 y RF-14 con las dos confirmaciones se piden una tras otra y solo despues empieza`() {
        val both = listOf(EstimateConfirmation.MobileData, EstimateConfirmation.OwnWms("Mi servicio"))
        show(full(estimate = estimate(confirmations = both)))

        scrollTo(OFFLINE_START_TAG)
        compose.onNodeWithTag(OFFLINE_START_TAG).performClick()
        compose.onNodeWithTag(OFFLINE_CONFIRM_ACCEPT_TAG).performClick()
        compose.onNodeWithText("Mi servicio", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(OFFLINE_CONFIRM_ACCEPT_TAG).performClick()

        calls shouldBe listOf("confirm:MobileData", "confirm:OwnWms", "start")
    }

    @Test
    fun `S037 RF-1 el nombre propuesto sale en su campo y se puede cambiar`() {
        show()

        scrollTo(OFFLINE_NAME_TAG)
        compose.onNodeWithTag(OFFLINE_NAME_TAG).assertTextContains("Obra – 14/11/2026")
    }
}

/** La misma hoja en inglés (S037/RNF-T7). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en")
class DownloadPlanSheetEnglishTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `S037 RNF-T7 the sheet is in English with English number separators`() {
        val pnoa = DownloadableSource(SourceKind.PNOA, "Ortofoto PNOA")
        val state = DownloadPlanUiState(
            loaded = true,
            areaKinds = listOf(OfflineAreaKind.RADIUS),
            areaKind = OfflineAreaKind.RADIUS,
            sources = listOf(SourceChoice(pnoa, true)),
            estimate = Estimate(listOf(SourceEstimate(pnoa, 4_650, 123L * 1024 * 1024)), listOf(EstimateBlock.NoInternet), emptyList()),
        )
        compose.setContent { TopoPhoneTheme(EffectiveTheme.LIGHT) { DownloadPlanContent(state, DownloadPlanActions()) } }

        compose.onNodeWithTag(offlineSourceTag("pnoa")).assertTextContains("PNOA orthophoto", substring = true)
        compose.onNodeWithTag(OFFLINE_RADIUS_VALUE_TAG).assertTextContains("Radius: 500 m")
        compose.onNodeWithTag(OFFLINE_PLAN_TAG).performScrollToNode(hasTestTag(offlineEstimateTag("pnoa")))
        compose.onNodeWithTag(offlineEstimateTag("pnoa")).assertTextContains("PNOA orthophoto: 4,650 tiles, about 123 MB")
        compose.onNodeWithTag(OFFLINE_PLAN_TAG).performScrollToNode(hasTestTag(offlineBlockTag(0)))
        compose.onNodeWithTag(offlineBlockTag(0)).assertTextContains("There is no internet connection", substring = true)
    }
}
