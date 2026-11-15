package es.topophone.app.ui.receiver

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.equipment.EQUIPMENT_NOT_APPLICABLE_TAG
import es.topophone.app.ui.equipment.EquipmentUiState
import es.topophone.app.ui.guide.GUIDE_CHECK_ONLY_TAG
import es.topophone.app.ui.guide.GUIDE_NOT_APPLICABLE_TAG
import es.topophone.app.ui.guide.GuideUiState
import es.topophone.app.ui.guide.guideListTag
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.guide.ConfigListId
import es.topophone.core.model.point.PositionSource
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSnapshot
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * La Guía y los datos del receptor según la fuente: con un receptor real, sus listas y sus datos; con el móvil, que no aplican; con
 * el receptor virtual o una reproducción, solo las comprobaciones (S043/RF-1, RF-18, RF-22, RF-23). Interfaz en es-ES.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class GuideSourcesTest {

    @get:Rule
    val compose = createComposeRule()

    private fun snapshot(source: PositionSource) = ReceiverSnapshot(ConnectionState.Receiving("fuente"), source = source)

    private fun showMenu(source: PositionSource, actions: ReceiverActions = ReceiverActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ReceiverScreen(ThemeState.DEFAULT, {}, ReceiverUiState(snapshot = snapshot(source)), actions)
            }
        }
    }

    private fun showPage(
        page: ReceiverPage,
        source: PositionSource,
        guide: GuideUiState = GuideUiState(),
        equipment: EquipmentUiState = EquipmentUiState(),
    ) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ReceiverPageScreen(
                    page,
                    ThemeState.DEFAULT,
                    {},
                    ReceiverUiState(snapshot = snapshot(source)),
                    ReceiverActions(),
                    {},
                    guide = guide,
                    equipment = equipment,
                )
            }
        }
    }

    private fun entry(tag: String) = compose.onNodeWithTag(tag).performScrollTo()

    @Test
    fun `S043 RF-1 con un receptor real el menu ofrece la Guia y los Datos del receptor con su resumen`() {
        showMenu(PositionSource.RECEIVER)

        entry(MENU_GUIDE_TAG).assert(hasText(text(R.string.receiver_menu_guide_summary)))
        entry(MENU_EQUIPMENT_TAG).assert(hasText(text(R.string.receiver_menu_equipment_summary)))
    }

    @Test
    fun `S043 RF-22 con el movil las dos entradas dicen que no aplican a esta fuente`() {
        showMenu(PositionSource.PHONE)

        entry(MENU_GUIDE_TAG).assert(hasText(text(R.string.receiver_menu_source_not_applicable)))
        entry(MENU_EQUIPMENT_TAG).assert(hasText(text(R.string.receiver_menu_source_not_applicable)))
    }

    @Test
    fun `S043 RF-23 con el virtual la Guia conserva Comprobar y los Datos del receptor no aplican`() {
        showMenu(PositionSource.VIRTUAL)

        entry(MENU_GUIDE_TAG).assert(hasText(text(R.string.receiver_menu_guide_check_only)))
        entry(MENU_EQUIPMENT_TAG).assert(hasText(text(R.string.receiver_menu_source_not_applicable)))
    }

    @Test
    fun `S043 RF-23 con una reproduccion la Guia ofrece solo comprobaciones`() {
        showMenu(PositionSource.REPLAY)

        entry(MENU_GUIDE_TAG).assert(hasText(text(R.string.receiver_menu_guide_check_only)))
        entry(MENU_EQUIPMENT_TAG).assert(hasText(text(R.string.receiver_menu_source_not_applicable)))
    }

    @Test
    fun `S043 RF-1 y RF-18 las entradas abren la Guia y los Datos del receptor`() {
        val opened = mutableListOf<String>()
        showMenu(PositionSource.RECEIVER, ReceiverActions(onOpenGuide = { opened += "guia" }, onOpenEquipment = { opened += "datos" }))

        entry(MENU_GUIDE_TAG).performClick()
        entry(MENU_EQUIPMENT_TAG).performClick()

        opened shouldBe listOf("guia", "datos")
    }

    @Test
    fun `S043 RF-1 la pagina de la Guia con un receptor real muestra las cuatro listas`() {
        showPage(ReceiverPage.GUIDE, PositionSource.RECEIVER, guide = GuideUiState(applies = true, receiverKey = "AA:BB"))

        compose.onNodeWithTag(RECEIVER_GUIDE_SCREEN_TAG).assertExists()
        ConfigListId.entries.forEach { compose.onNodeWithTag(guideListTag(it)).performScrollTo().assertExists() }
    }

    @Test
    fun `S043 RF-22 la pagina de la Guia con el movil dice que no aplica y no tiene listas`() {
        showPage(ReceiverPage.GUIDE, PositionSource.PHONE, guide = GuideUiState(applies = false, receiverKey = "phone"))

        compose.onNodeWithTag(GUIDE_NOT_APPLICABLE_TAG).assertExists()
        compose.onNodeWithTag(guideListTag(ConfigListId.NTRIP_ROVER)).assertDoesNotExist()
    }

    @Test
    fun `S043 RF-23 la pagina de la Guia con el virtual no tiene listas pero ofrece comprobar`() {
        val guide = GuideUiState(applies = false, receiverKey = "virtual", canCheck = true)
        showPage(ReceiverPage.GUIDE, PositionSource.VIRTUAL, guide = guide)

        compose.onNodeWithTag(GUIDE_CHECK_ONLY_TAG).assertExists()
        compose.onNodeWithTag(guideListTag(ConfigListId.NTRIP_ROVER)).assertDoesNotExist()
    }

    @Test
    fun `S043 RF-22 la pagina de Datos del receptor no aplica con el movil`() {
        showPage(ReceiverPage.EQUIPMENT, PositionSource.PHONE, equipment = EquipmentUiState(applies = false))

        compose.onNodeWithTag(EQUIPMENT_NOT_APPLICABLE_TAG).assertExists()
    }

    @Test
    fun `S043 RF-23 la pagina de Datos del receptor no aplica con el virtual`() {
        showPage(ReceiverPage.EQUIPMENT, PositionSource.VIRTUAL, equipment = EquipmentUiState(applies = false))

        compose.onNodeWithTag(EQUIPMENT_NOT_APPLICABLE_TAG).assertExists()
    }
}
