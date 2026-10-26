package es.topophone.app.ui.cad

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.FakeMapController
import es.topophone.app.ui.map.MapTap
import es.topophone.app.ui.map.fakeMap
import es.topophone.app.ui.projects.ProjectViewScreen
import es.topophone.app.ui.projects.ProjectViewUiState
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadEntityRef
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La ficha de un elemento del dibujo: tipo, capa, dibujo, coordenadas o longitud, y cota o «Sin cota» (S040/RF-23, RF-29). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class DesignElementSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private fun selection(entity: CadEntity, hasElevation: Boolean = true) =
        DesignSelection(CadEntityRef("l1", 1L, 0), entity, "Ejes", "calle.dxf", hasElevation)

    private fun show(selection: DesignSelection, unit: LinearUnit = LinearUnit.METRE, symbol: String = "m", geographic: Boolean = false) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { DesignElementSheet(selection, unit, symbol, geographic, onDismiss = {}) }
        }
    }

    private fun point(z: Double?, kind: CadEntityKind = CadEntityKind.POINT) =
        CadEntity(kind, doubleArrayOf(440_000.5), doubleArrayOf(4_474_000.25), z?.let { doubleArrayOf(it) }, null)

    private fun line(z: DoubleArray?, kind: CadEntityKind = CadEntityKind.LINE) =
        CadEntity(kind, doubleArrayOf(440_000.0, 440_030.0, 440_030.0), doubleArrayOf(4_474_000.0, 4_474_000.0, 4_474_040.0), z, null)

    @Test
    fun `S040 RF-29 un punto da su tipo, su capa, su dibujo y sus coordenadas`() {
        show(selection(point(646.5)))

        compose.onNodeWithText("Elemento del dibujo").assertIsDisplayed()
        compose.onNodeWithText("Punto").assertIsDisplayed()
        compose.onNodeWithText("Capa: Ejes").assertIsDisplayed()
        compose.onNodeWithText("Dibujo: calle.dxf").assertIsDisplayed()
        compose.onNodeWithText("Este 440.000,500, Norte 4.474.000,250").assertIsDisplayed()
    }

    @Test
    fun `S040 RF-29 una linea da su longitud y el numero de vertices`() {
        show(selection(line(null), hasElevation = false))

        compose.onNodeWithText("Línea").assertIsDisplayed()
        compose.onNodeWithText("Longitud: 70,000 m").assertIsDisplayed()
        compose.onNodeWithText("Vértices: 3").assertIsDisplayed()
    }

    @Test
    fun `S040 RF-29 el tipo es el original aunque se guarde como polilinea`() {
        show(selection(line(null, CadEntityKind.ARC), hasElevation = false))
        compose.onNodeWithText("Arco").assertIsDisplayed()
    }

    @Test
    fun `S040 RF-29 el punto de insercion de un bloque se llama asi`() {
        show(selection(point(null, CadEntityKind.BLOCK_INSERT), hasElevation = false))

        compose.onNodeWithText("Punto de inserción de un bloque").assertIsDisplayed()
    }

    @Test
    fun `S040 RF-23 un elemento con cota da su altura en la unidad del proyecto`() {
        show(selection(point(646.5)))

        compose.onNodeWithTag(DESIGN_ELEVATION_TAG).assertIsDisplayed()
        compose.onNodeWithText("Cota: 646,500 m").assertIsDisplayed()
    }

    @Test
    fun `S040 RF-23 una linea con alturas distintas da el rango`() {
        show(selection(line(doubleArrayOf(100.0, 101.5, 102.0))))

        compose.onNodeWithText("Cota: de 100,000 a 102,000 m").assertIsDisplayed()
    }

    @Test
    fun `S040 RF-23 un elemento sin cota dice Sin cota`() {
        show(selection(point(null), hasElevation = false))

        compose.onNodeWithTag(DESIGN_NO_ELEVATION_TAG).assertIsDisplayed()
        compose.onNodeWithText("Sin cota").assertIsDisplayed()
    }

    @Test
    fun `S040 RF-23 un elemento con Z en un dibujo sin cotas tambien dice Sin cota`() {
        show(selection(point(0.0), hasElevation = false))

        compose.onNodeWithText("Sin cota").assertIsDisplayed()
    }

    @Test
    fun `S040 RF-7 en pies las longitudes y las cotas se dan en pies`() {
        show(selection(point(30.48)), unit = LinearUnit.INTERNATIONAL_FOOT, symbol = "ft")

        compose.onNodeWithText("Cota: 100,000 ft").assertIsDisplayed()
    }

    @Test
    fun `S040 RF-29 en un proyecto en geograficas las coordenadas son longitud y latitud`() {
        val geo = CadEntity(CadEntityKind.POINT, doubleArrayOf(-3.703712), doubleArrayOf(40.416801), null, null)

        show(selection(geo, hasElevation = false), geographic = true)

        compose.onNodeWithText("Longitud -3,704, latitud 40,417").assertIsDisplayed()
    }

    private val project = Project(
        id = "p1",
        name = "Calle Mayor 12",
        author = "",
        description = "",
        crs = ProjectCrs.ETRS89_UTM30,
        linearUnit = LinearUnit.METRE,
        angularUnit = AngularUnit.DMS,
        correctionsDatum = CorrectionsDatum.ETRS89,
        folderId = null,
        createdAtMs = 1_000,
        modifiedAtMs = 1_000,
        openedAtMs = null,
        deletedAtMs = null,
    )

    @Test
    fun `S040 RF-29 la vista de proyecto abre la ficha con el elemento tocado y avisa de los toques del mapa`() {
        val map = FakeMapController()
        val taps = mutableListOf<MapTap>()
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectViewScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectViewUiState(project),
                    onBack = {},
                    map = fakeMap(map),
                    designSelection = selection(point(646.5)),
                    onMapTap = { taps += it },
                )
            }
        }
        compose.waitForIdle()
        map.tap(MapTap(40.4, -3.7, 12.0))
        compose.waitUntil(5_000) { taps.isNotEmpty() }

        compose.onNodeWithTag(DESIGN_SHEET_TAG).assertIsDisplayed()
        taps.single() shouldBe MapTap(40.4, -3.7, 12.0)
    }
}
