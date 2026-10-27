package es.topophone.app.ui.cad

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.cad.CadColor
import es.topophone.core.model.cad.CadDrawing
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadEntityRef
import es.topophone.core.model.cad.CadOrigin
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.cad.DesignLayer
import es.topophone.core.model.cad.DesignLayerKind
import es.topophone.core.model.cad.GpkgValue
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.LinearUnit
import es.topophone.data.cad.CadDrawingWithLayers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La ficha de un objeto de GeoPackage con su nombre, su contorno y sus atributos, y su procedencia en «Dibujos» (S048/RF-20, RF-22, RF-23). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class GpkgFeatureSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private fun entity(name: String?, ring: Boolean = false, points: Int = 1): CadEntity {
        val east = DoubleArray(points) { 440_000.0 + it * 10 }
        val north = DoubleArray(points) { 4_474_000.0 }
        return CadEntity(
            if (points == 1) CadEntityKind.POINT else CadEntityKind.POLYLINE,
            east,
            north,
            null,
            null,
            name = name,
            polygonRing = ring,
        )
    }

    private fun selection(entity: CadEntity, attributes: Map<String, GpkgValue> = emptyMap()) =
        DesignSelection(CadEntityRef("l1", 1L, 0), entity, "parcelas", "capas.gpkg", false, attributes)

    private fun show(selection: DesignSelection) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                DesignElementSheet(selection, LinearUnit.METRE, "m", geographic = false, onDismiss = {})
            }
        }
    }

    @Test
    fun `S048 RF-22 la ficha da el nombre del objeto`() {
        show(selection(entity("Farola")))

        compose.onNodeWithTag(DESIGN_NAME_TAG).assertIsDisplayed()
        compose.onNodeWithText("Nombre: Farola").assertIsDisplayed()
    }

    @Test
    fun `S048 RF-22 sin nombre no hay linea de nombre`() {
        show(selection(entity(null)))

        compose.onNodeWithTag(DESIGN_NAME_TAG).assertDoesNotExist()
    }

    @Test
    fun `S048 RF-17 y RNF-T8 el contorno de poligono se dice con texto`() {
        show(selection(entity("Parcela 2", ring = true, points = 4)))

        compose.onNodeWithTag(DESIGN_RING_TAG).assertIsDisplayed()
        compose.onNodeWithText("Contorno de polígono").assertIsDisplayed()
    }

    @Test
    fun `S048 RF-17 una linea que no es contorno no lo dice`() {
        show(selection(entity("Eje", ring = false, points = 3)))

        compose.onNodeWithTag(DESIGN_RING_TAG).assertDoesNotExist()
    }

    @Test
    fun `S048 RF-20 la ficha lista todos los atributos con su columna`() {
        show(
            selection(
                entity("Farola"),
                linkedMapOf(
                    "nombre" to GpkgValue.Text("Farola"),
                    "cota" to GpkgValue.Real(646.377),
                    "codigo" to GpkgValue.Integer(4_117),
                    "fecha" to GpkgValue.Date("2026-10-01"),
                    "activo" to GpkgValue.Bool(true),
                    "baja" to GpkgValue.Bool(false),
                    "nota" to GpkgValue.Null,
                    "icono" to GpkgValue.Blob(byteArrayOf(1, 2, 3)),
                    "foto" to GpkgValue.Omitted(70_000),
                ),
            ),
        )

        compose.onNodeWithTag(DESIGN_ATTRIBUTES_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Atributos").assertIsDisplayed()
        compose.onNodeWithText("nombre: Farola").assertIsDisplayed()
        compose.onNodeWithText("cota: 646,377").assertIsDisplayed()
        compose.onNodeWithText("codigo: 4117").assertIsDisplayed()
        compose.onNodeWithText("fecha: 2026-10-01").assertIsDisplayed()
        compose.onNodeWithText("activo: Sí").assertIsDisplayed()
        compose.onNodeWithText("baja: No").assertIsDisplayed()
        compose.onNodeWithText("nota: —").assertIsDisplayed()
        compose.onNodeWithText("icono: (binario, 3 bytes)").assertIsDisplayed()
        compose.onNodeWithText("foto: (omitido, 68 kB)").assertIsDisplayed()
    }

    @Test
    fun `S048 RF-20 un elemento de un DXF no tiene seccion de atributos`() {
        show(selection(entity(null)))

        compose.onNodeWithTag(DESIGN_ATTRIBUTES_TAG).assertDoesNotExist()
    }

    private fun entry(origin: CadOrigin, id: String) = CadDrawingWithLayers(
        CadDrawing(
            id, "p",
            if (origin ==
                CadOrigin.GEOPACKAGE
            ) {
                "capas.gpkg"
            } else {
                "plano.dxf"
            },
            1_000L, CadSourceCrs.Project, LinearUnit.METRE, true, false, origin = origin,
        ),
        listOf(DesignLayer("l-$id", id, "Ejes", DesignLayerKind.LINES, CadColor.rgb(0xFF0000), entityCount = 4)),
    )

    @Test
    fun `S048 RF-26 el panel Dibujos dice si un dibujo viene de un GeoPackage o de un DXF`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                DrawingsSheet(listOf(entry(CadOrigin.GEOPACKAGE, "g1"), entry(CadOrigin.DXF, "d1")), DrawingsActions(), onDismiss = {})
            }
        }

        compose.onNodeWithTag(drawingOriginTag("g1")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Origen: GeoPackage").assertIsDisplayed()
        compose.onNodeWithTag(drawingOriginTag("d1")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Origen: DXF").performScrollTo().assertIsDisplayed()
    }
}
