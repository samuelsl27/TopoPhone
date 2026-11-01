package es.topophone.app.ui.section

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.alignment.section.CrossSection
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.alignment.section.SectionPoint
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.survey.AxisPoint
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La pantalla del punto de una sección: resultado, estados, guardar y el alzado «Ver sección» (S050/RF-9 a RF-13, RF-15). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class SectionPointScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val design = CrossSection(
        SectionKind.DESIGN,
        100.0,
        HeightKind.RELATIVE,
        listOf(SectionPoint("BI", -3.5, -0.07), SectionPoint("EJE", 0.0, 0.0), SectionPoint("BD", 3.5, -0.07)),
    )

    private val ground = CrossSection(
        SectionKind.GROUND,
        100.0,
        HeightKind.ABSOLUTE,
        listOf(SectionPoint("T1", -8.0, 99.0), SectionPoint("T2", 8.0, 99.5)),
    )

    private val reading = SectionReading(
        "BD",
        AxisPoint(100.0, 3.5, 440001.75, 4473996.97, 440000.0, 4474000.0, 1.0, 0.0),
        99.93,
        0.02,
    )

    private val base = SectionPointUiState(
        loaded = true,
        alignmentName = "Eje A",
        startChainageM = 0.0,
        endChainageM = 420.0,
        hasProfile = true,
        chainageText = "100",
        codes = listOf("BI", "EJE", "BD"),
        code = "BD",
        section = design,
        profileElevationM = 100.0,
        reading = reading,
        name = "D1",
    )

    private fun show(state: SectionPointUiState, actions: SectionPointActions = SectionPointActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { SectionPointScreen(ThemeState.DEFAULT, {}, state, actions, {}) }
        }
    }

    @Test
    fun `S050 RF-9 el resultado dice el PK el lado con palabra el Este el Norte la cota y la pendiente con baja`() {
        show(base)

        compose.onNodeWithTag(SECTION_POINT_RESULT_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(SECTION_POINT_EASTING_TAG).assertExists()
        compose.onNodeWithTag(SECTION_POINT_NORTHING_TAG).assertExists()
        compose.onNodeWithTag(SECTION_POINT_ELEVATION_TAG).assertExists()
        compose.onNodeWithText("Pendiente transversal: baja 2 %").assertExists()
        compose.onNodeWithText("Código: BD").assertExists()
    }

    @Test
    fun `S050 RF-11 una seccion relativa sin rasante no da cota y lo dice`() {
        show(base.copy(reading = reading.copy(elevationM = null), hasProfile = false))

        compose.onNodeWithTag(SECTION_POINT_NO_ELEVATION_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(SECTION_POINT_ELEVATION_TAG).assertDoesNotExist()
    }

    @Test
    fun `S050 RF-9 se pide por codigo con los codigos de ese PK o por desplazamiento`() {
        val calls = mutableListOf<String>()
        show(
            base,
            SectionPointActions(
                onChainage = { calls += "pk:$it" },
                onMode = { calls += "mode:${it.name}" },
                onCode = { calls += "code:$it" },
            ),
        )

        compose.onNodeWithTag(sectionPointCodeTag("BI")).performClick()
        compose.onNodeWithTag(sectionPointModeTag(SectionPointMode.BY_OFFSET)).performClick()

        calls shouldBe listOf("code:BI", "mode:BY_OFFSET")
    }

    @Test
    fun `S050 RF-10 por desplazamiento aparece el campo y Fuera de la seccion se dice`() {
        show(base.copy(mode = SectionPointMode.BY_OFFSET, reading = null, status = SectionPointStatus.OutsideSection))

        compose.onNodeWithTag(SECTION_POINT_OFFSET_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Fuera de la sección.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(SECTION_POINT_RESULT_TAG).assertDoesNotExist()
    }

    @Test
    fun `S050 RF-12 un PK sin seccion o con codigos que faltan lo dice`() {
        show(base.copy(reading = null, section = null, status = SectionPointStatus.NoSection))
        compose.onNodeWithText("No hay sección de diseño en este PK.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S050 RF-12 los codigos que faltan entre dos secciones se nombran`() {
        show(base.copy(reading = null, section = null, status = SectionPointStatus.Missing(listOf("CD", "CI"), false)))

        compose.onNodeWithText(
            "Las dos secciones que rodean este PK no tienen los mismos códigos: faltan CD, CI.",
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S050 RF-9 un PK fuera del eje dice el PK inicial y el final`() {
        show(base.copy(reading = null, section = null, outside = true))

        compose.onNodeWithTag(SECTION_POINT_OUTSIDE_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S050 RF-15 guardar el punto pide el nombre y avisa`() {
        val calls = mutableListOf<String>()
        show(base, SectionPointActions(onSave = { calls += "save" }, onName = { calls += "name:$it" }))

        compose.onNodeWithTag(SECTION_POINT_SAVE_TAG).performScrollTo().assertIsEnabled().performClick()

        calls shouldBe listOf("save")
    }

    @Test
    fun `S050 RF-15 sin nombre no se puede guardar y un nombre repetido se avisa`() {
        show(base.copy(name = "", duplicateName = true))

        compose.onNodeWithTag(SECTION_POINT_SAVE_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(SECTION_POINT_DUPLICATE_TAG, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `S050 RF-15 tras guardar se dice el nombre y se ofrece otro punto`() {
        var another = 0
        show(base.copy(savedName = "D1"), SectionPointActions(onAnother = { another++ }))

        compose.onNodeWithTag(SECTION_POINT_SAVED_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(SECTION_POINT_ANOTHER_TAG).performScrollTo().performClick()

        another shouldBe 1
    }

    @Test
    fun `S050 RF-13 Ver sección dibuja el alzado con cada punto su desplazamiento con palabra y su cota`() {
        val shown = mutableListOf<Boolean>()
        show(base.copy(showSection = true), SectionPointActions(onShowSection = { shown += it }))

        compose.onNodeWithTag(SECTION_DRAWING_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(sectionDrawingPointTag("BI")).assertExists()
        compose.onNodeWithText("BD: 3,500 m a la derecha, cota 99,930 m").assertExists()
        compose.onNodeWithText("EJE: Sobre el eje, cota 100,000 m").assertExists()
        compose.onNodeWithTag(SECTION_POINT_VIEW_TAG).performScrollTo().performClick()

        shown shouldBe listOf(false)
    }

    @Test
    fun `S050 RF-13 sin pulsar Ver sección no hay alzado y el boton lo ofrece`() {
        show(base)

        compose.onNodeWithTag(SECTION_DRAWING_TAG).assertDoesNotExist()
        compose.onNodeWithText("Ver sección").assertExists()
    }

    @Test
    fun `S050 RF-13 el alzado dibuja tambien el perfil de terreno de ese PK si lo hay`() {
        show(base.copy(showSection = true, ground = ground))

        compose.onNodeWithText("Terreno").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(sectionDrawingPointTag("T1")).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S050 RF-13 una seccion relativa sin rasante se dibuja con las diferencias sin dar cota`() {
        show(base.copy(showSection = true, profileElevationM = null))

        compose.onNodeWithText("BD: 3,500 m a la derecha").assertExists()
    }
}
