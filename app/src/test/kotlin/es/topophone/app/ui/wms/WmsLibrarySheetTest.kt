package es.topophone.app.ui.wms

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.projects.CONFIRM_TAG
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.formats.wms.WmsPresets
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.wms.WmsAddressProblem
import es.topophone.core.model.wms.WmsLayerInfo
import es.topophone.core.model.wms.WmsRequestMode
import es.topophone.core.model.wms.WmsService
import es.topophone.data.wms.MissingProjectLayer
import es.topophone.data.wms.WmsAddProblem
import es.topophone.data.wms.WmsServiceUse
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La hoja de la biblioteca de servicios WMS: alta, presets, capas de un servicio, actualizar, renombrar y borrar (S036/RF-1 a RF-17). Interfaz en español. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class WmsLibrarySheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val currentState = mutableStateOf(WmsLibraryUiState())
    private val currentActions = mutableStateOf(WmsLibraryActions())
    private var composed = false

    private val service = WmsService(
        id = "s1",
        name = "Ortofoto de la obra",
        address = "https://ejemplo.es/wms",
        title = "Servicio de ejemplo",
        version = "1.3.0",
        attribution = "© Servicio de ejemplo",
        presetKey = null,
        requestMode = WmsRequestMode.TILES,
        mapFormats = listOf("image/png"),
        infoFormats = listOf("text/plain"),
        createdAtMs = 0L,
        updatedAtMs = 0L,
    )
    private val layers = listOf(
        WmsLayerInfo(0, 0, null, "Grupo de capas", "", true, false, "EPSG:3857"),
        WmsLayerInfo(1, 1, "rgb", "Color", "Imagen en color", true, true, "EPSG:3857"),
        WmsLayerInfo(2, 1, "utm", "Solo UTM", "", false, false, null),
    )

    private fun groups(inLibrary: Map<String, String> = emptyMap()) = WmsPresets.all.groupBy { it.group }.map { (name, presets) ->
        WmsPresetGroup(name, presets.map { WmsPresetItem(it, inLibrary[it.key]) })
    }

    private fun library(
        services: List<WmsService> = emptyList(),
        address: String = "",
        add: WmsAddState = WmsAddState.Idle,
        inLibrary: Map<String, String> = emptyMap(),
    ) = WmsLibraryUiState(services = services, presetGroups = groups(inLibrary), address = address, add = add)

    private fun opened(
        notice: WmsOpenNotice? = null,
        refresh: WmsRefreshState = WmsRefreshState.Idle,
        mode: WmsRequestMode = WmsRequestMode.TILES,
        pendingDelete: PendingWmsDelete? = null,
    ) = WmsLibraryUiState(
        services = listOf(service),
        opened = OpenedWmsService(service.copy(requestMode = mode), layers.map(::WmsLayerRow), notice),
        refresh = refresh,
        pendingDelete = pendingDelete,
    )

    /** Pone [state] y [actions] en la hoja; una segunda llamada en el mismo test los cambia sin volver a componer. */
    private fun show(state: WmsLibraryUiState, actions: WmsLibraryActions = WmsLibraryActions()) {
        currentState.value = state
        currentActions.value = actions
        if (!composed) {
            composed = true
            compose.setContent { TopoPhoneTheme(EffectiveTheme.LIGHT) { WmsLibraryContent(currentState.value, currentActions.value) } }
        }
        compose.waitForIdle()
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag(WMS_LIST_TAG).performScrollToNode(hasTestTag(tag))
    }

    @Test
    fun `S036 RF-1 Anadir esta desactivado sin direccion y lo que se escribe llega a quien lo atiende`() {
        val calls = mutableListOf<String>()
        show(library(), WmsLibraryActions(onAddressChange = { calls += "text:$it" }, onAdd = { calls += "add" }))

        compose.onNodeWithTag(WMS_ADD_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(WMS_ADDRESS_TAG).performTextReplacement("ejemplo.es/wms")

        calls shouldBe listOf("text:ejemplo.es/wms")
    }

    @Test
    fun `S036 RF-1 con una direccion escrita Anadir se activa y se pulsa`() {
        val calls = mutableListOf<String>()
        show(library(address = "ejemplo.es/wms"), WmsLibraryActions(onAdd = { calls += "add" }))

        compose.onNodeWithTag(WMS_ADD_TAG).assertIsEnabled().assertHeightIsAtLeast(56.dp).performClick()

        calls shouldBe listOf("add")
    }

    @Test
    fun `S036 RF-1 mientras se pide el servicio se dice y no se puede anadir otro`() {
        show(library(address = "ejemplo.es/wms", add = WmsAddState.Loading()))

        compose.onNodeWithText("Pidiendo las capas del servicio…").assertIsDisplayed()
        compose.onNodeWithTag(WMS_ADD_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(WMS_ADDRESS_TAG).assertIsNotEnabled()
    }

    @Test
    fun `S036 RF-14 mientras se anade un preset se dice su nombre`() {
        val preset = WmsPresets.byKey("catastro-wms")!!
        show(library(add = WmsAddState.Loading(preset.key)))

        compose.onNodeWithText("Añadiendo «${preset.name}»…").assertIsDisplayed()
    }

    @Test
    fun `S036 RF-8 cada motivo de fallo tiene su texto`() {
        val cases = listOf(
            WmsFailure.Address(WmsAddressProblem.EMPTY) to "Escribe la dirección del servicio.",
            WmsFailure.Address(WmsAddressProblem.PUBLIC_HTTP) to
                "Esta dirección usa HTTP y no es de una red privada. Usa una dirección HTTPS.",
            WmsFailure.Service(WmsAddProblem.NO_RESPONSE) to "El servicio no responde. Comprueba la conexión y la dirección.",
            WmsFailure.Service(WmsAddProblem.TIMEOUT) to "El servicio ha tardado más de 20 s en responder.",
            WmsFailure.Service(WmsAddProblem.HTTP_ERROR, "503") to "El servicio ha contestado con un error (HTTP 503).",
            WmsFailure.Service(WmsAddProblem.SERVICE_EXCEPTION, "Parcela no encontrada") to
                "El servicio ha devuelto un error: Parcela no encontrada",
            WmsFailure.Service(WmsAddProblem.NO_WEB_MERCATOR) to
                "El servicio no ofrece ninguna capa en EPSG:3857, así que no es compatible con el mapa.",
        )
        cases.forEach { (failure, text) ->
            show(library(add = WmsAddState.Error(failure)))

            compose.onNodeWithTag(WMS_ADD_ERROR_TAG).assertTextContains(text)
        }
    }

    @Test
    fun `S036 RF-4 la biblioteca vacia lo dice`() {
        show(library())

        compose.onNodeWithText("Todavía no hay servicios en la biblioteca.").assertIsDisplayed()
    }

    @Test
    fun `S036 RF-4 un servicio de la biblioteca sale con su version y su modo y se abre al tocarlo`() {
        val calls = mutableListOf<String>()
        show(library(services = listOf(service)), WmsLibraryActions(onOpen = { calls += it }))

        compose.onNodeWithTag(wmsServiceTag("s1")).assertTextContains("Ortofoto de la obra").assertTextContains("WMS 1.3.0 · Por teselas")
        compose.onNodeWithTag(wmsServiceTag("s1")).assertHeightIsAtLeast(56.dp).performClick()

        calls shouldBe listOf("s1")
    }

    @Test
    fun `S036 RF-13 los presets salen por grupos con su licencia`() {
        show(library())
        val preset = WmsPresets.all.first { it.licenseUrl != null }

        scrollTo(wmsPresetLicenseTag(preset.key))

        compose.onNodeWithTag(wmsPresetLicenseTag(preset.key)).assertTextContains("Licencia: ${preset.license}")
        compose.onNodeWithText(preset.group).assertIsDisplayed()
    }

    @Test
    fun `S036 RF-14 un preset que no esta en la biblioteca se anade con su clave`() {
        val calls = mutableListOf<String>()
        show(library(), WmsLibraryActions(onAddPreset = { calls += it }))
        val key = WmsPresets.all.first().key

        scrollTo(wmsPresetAddTag(key))
        compose.onNodeWithTag(wmsPresetAddTag(key)).assertHeightIsAtLeast(56.dp).performClick()

        calls shouldBe listOf(key)
    }

    @Test
    fun `S036 RF-15 un preset que ya esta en la biblioteca lo dice y se abre en lugar de anadirse`() {
        val calls = mutableListOf<String>()
        val key = WmsPresets.all.first().key
        show(library(services = listOf(service), inLibrary = mapOf(key to "s1")), WmsLibraryActions(onOpen = { calls += it }))

        scrollTo(wmsPresetOpenTag(key))
        compose.onNodeWithTag(wmsPresetOpenTag(key)).assertTextContains("En la biblioteca").performClick()
        compose.onNodeWithTag(wmsPresetAddTag(key)).assertDoesNotExist()

        calls shouldBe listOf("s1")
    }

    @Test
    fun `S036 RF-3 un servicio abierto muestra su titulo, su version, su atribucion y su arbol de capas`() {
        show(opened(notice = WmsOpenNotice.ADDED))

        compose.onNodeWithText("Ortofoto de la obra").assertIsDisplayed()
        compose.onNodeWithText("Servicio de ejemplo").assertIsDisplayed()
        compose.onNodeWithText("WMS 1.3.0 · Por teselas").assertIsDisplayed()
        compose.onNodeWithText("Atribución: © Servicio de ejemplo").assertIsDisplayed()
        compose.onNodeWithTag(WMS_NOTICE_TAG).assertTextContains("Servicio añadido a la biblioteca.")
        scrollTo(wmsLayerTag(1))
        compose.onNodeWithTag(wmsLayerTag(1)).assertTextContains("Color").assertTextContains("Imagen en color")
            .assertTextContains("Transparente · Consultable").assertTextContains("Nombre: rgb")
    }

    @Test
    fun `S036 RF-15 un servicio que ya estaba lo dice`() {
        show(opened(notice = WmsOpenNotice.ALREADY_THERE))

        compose.onNodeWithTag(WMS_NOTICE_TAG).assertTextContains("Este servicio ya estaba en la biblioteca.")
    }

    @Test
    fun `S036 RF-12 una capa que solo agrupa o no tiene 3857 dice por que no se puede anadir`() {
        show(opened())

        scrollTo(wmsLayerTag(0))
        compose.onNodeWithTag(wmsLayerTag(0)).assertTextContains("Solo agrupa otras capas: no se puede añadir a un proyecto.")
        scrollTo(wmsLayerTag(2))
        compose.onNodeWithTag(wmsLayerTag(2)).assertTextContains("No ofrece EPSG:3857: no se puede añadir a un proyecto.")
    }

    @Test
    fun `S036 RF-6 actualizar llega a quien lo atiende y mientras se actualiza no se puede repetir`() {
        val calls = mutableListOf<String>()
        show(opened(), WmsLibraryActions(onRefresh = { calls += "refresh" }))
        compose.onNodeWithTag(WMS_REFRESH_TAG).assertHeightIsAtLeast(56.dp).performClick()
        calls shouldBe listOf("refresh")

        show(opened(refresh = WmsRefreshState.Loading))

        compose.onNodeWithTag(WMS_REFRESH_TAG).assertTextContains("Actualizando…").assertIsNotEnabled()
    }

    @Test
    fun `S036 RF-6 el resultado de actualizar avisa de las capas de proyectos que han desaparecido`() {
        val missing = listOf(MissingProjectLayer("p1", "Obra norte", "irg"))
        show(opened(refresh = WmsRefreshState.Done(missing)))

        scrollTo(WMS_REFRESH_RESULT_TAG)
        compose.onNodeWithText("Capas actualizadas.").assertIsDisplayed()
        compose.onNodeWithText("Siguen en su proyecto como «no disponible»", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Obra norte: irg").assertIsDisplayed()
    }

    @Test
    fun `S036 RF-6 si no se pudo actualizar dice por que y que el servicio queda como estaba`() {
        val calls = mutableListOf<String>()
        show(
            opened(refresh = WmsRefreshState.Failed(WmsFailure.Service(WmsAddProblem.NO_RESPONSE))),
            WmsLibraryActions(onDismissRefresh = { calls += "dismiss" }),
        )

        scrollTo(WMS_REFRESH_RESULT_TAG)
        compose.onNodeWithText("el servicio queda como estaba", substring = true).assertIsDisplayed()
        compose.onNodeWithText("El servicio no responde.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Entendido").performClick()
        calls shouldBe listOf("dismiss")
    }

    @Test
    fun `S036 RF-7 renombrar abre un dialogo con el nombre y guarda el nuevo`() {
        val calls = mutableListOf<String>()
        show(opened(), WmsLibraryActions(onRename = { calls += it }))

        compose.onNodeWithTag(WMS_RENAME_TAG).performClick()
        compose.onNodeWithTag(WMS_RENAME_FIELD_TAG).assertTextContains("Ortofoto de la obra")
        compose.onNodeWithTag(WMS_RENAME_FIELD_TAG).performTextReplacement("   ")
        compose.onNodeWithTag(WMS_RENAME_SAVE_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(WMS_RENAME_FIELD_TAG).performTextReplacement("PNOA de la zona")
        compose.onNodeWithTag(WMS_RENAME_SAVE_TAG).assertIsEnabled().performClick()

        calls shouldBe listOf("PNOA de la zona")
        compose.onNodeWithTag(WMS_RENAME_FIELD_TAG).assertDoesNotExist()
    }

    @Test
    fun `S036 RF-7 borrar pide confirmar y avisa de los proyectos que usan sus capas`() {
        val calls = mutableListOf<String>()
        val pending = PendingWmsDelete(service, listOf(WmsServiceUse("p1", "Obra norte", 2), WmsServiceUse("p2", "Obra sur", 1)))
        show(opened(), WmsLibraryActions(onRequestDelete = { calls += "request:$it" }))
        compose.onNodeWithTag(WMS_DELETE_TAG).performClick()
        calls shouldBe listOf("request:s1")

        show(
            opened(pendingDelete = pending),
            WmsLibraryActions(onConfirmDelete = { calls += "confirm" }, onCancelDelete = { calls += "cancel" }),
        )
        compose.onNodeWithText("¿Borrar «Ortofoto de la obra» de la biblioteca?", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Obra norte: 2 capas", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Obra sur: 1 capa", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(CONFIRM_TAG).performClick()

        calls shouldBe listOf("request:s1", "confirm")
    }

    @Test
    fun `S036 RF-17 el modo de peticion se ve y se cambia`() {
        val calls = mutableListOf<WmsRequestMode>()
        show(opened(), WmsLibraryActions(onSetRequestMode = { calls += it }))

        compose.onNodeWithTag(WMS_MODE_TILES_TAG).assertIsSelected()
        compose.onNodeWithTag(WMS_MODE_IMAGE_TAG).performClick()
        calls shouldBe listOf(WmsRequestMode.IMAGE)

        show(opened(mode = WmsRequestMode.IMAGE))

        compose.onNodeWithTag(WMS_MODE_IMAGE_TAG).assertIsSelected()
    }

    @Test
    fun `S036 RF-3 volver a la lista cierra el servicio abierto`() {
        val calls = mutableListOf<String>()
        show(opened(), WmsLibraryActions(onClose = { calls += "close" }))

        compose.onNodeWithTag(WMS_BACK_TAG).assertHeightIsAtLeast(56.dp).performClick()

        calls shouldBe listOf("close")
    }
}

/** Lo esencial de la hoja con la interfaz en inglés (S001/RF-19). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en")
class WmsLibrarySheetEnglishTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `S036 RF-1 the add form and the failures are in English`() {
        val state = WmsLibraryUiState(add = WmsAddState.Error(WmsFailure.Address(WmsAddressProblem.PUBLIC_HTTP)), address = "http://x.es")
        compose.setContent { TopoPhoneTheme(EffectiveTheme.LIGHT) { WmsLibraryContent(state, WmsLibraryActions()) } }

        compose.onNodeWithText("WMS services").assertIsDisplayed()
        compose.onNodeWithTag(
            WMS_ADD_ERROR_TAG,
        ).assertTextContains("This address uses HTTP and is not on a private network. Use an HTTPS address.")
        compose.onNodeWithTag(WMS_ADD_TAG).assertTextContains("Add")
    }
}
