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
import es.topophone.core.model.cad.CadOrigin
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.cad.DesignLayer
import es.topophone.core.model.cad.DesignLayerKind
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.LinearUnit
import es.topophone.data.cad.CadDrawingWithLayers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** El panel «Dibujos» dice con texto de qué fichero viene cada dibujo, también de un Shapefile (S030/RF-26, RNF-T8). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class DrawingsSheetShpTest {

    @get:Rule
    val compose = createComposeRule()

    private fun entry(origin: CadOrigin, id: String, fileName: String) = CadDrawingWithLayers(
        CadDrawing(id, "p", fileName, 1_000L, CadSourceCrs.Project, LinearUnit.METRE, true, false, origin = origin),
        listOf(DesignLayer("l-$id", id, "parcelas", DesignLayerKind.LINES, CadColor.rgb(0xFF0000), entityCount = 4)),
    )

    @Test
    fun `S030 RF-26 el panel Dibujos dice si un dibujo viene de un Shapefile de un GeoPackage o de un DXF`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                DrawingsSheet(
                    listOf(
                        entry(CadOrigin.SHAPEFILE, "s1", "parcelas.zip"),
                        entry(CadOrigin.GEOPACKAGE, "g1", "capas.gpkg"),
                        entry(CadOrigin.DXF, "d1", "plano.dxf"),
                    ),
                    DrawingsActions(),
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithTag(drawingOriginTag("s1")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Origen: Shapefile").assertIsDisplayed()
        compose.onNodeWithText("parcelas.zip").assertIsDisplayed()
        compose.onNodeWithTag(drawingOriginTag("g1")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Origen: GeoPackage").assertIsDisplayed()
        compose.onNodeWithTag(drawingOriginTag("d1")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Origen: DXF").assertIsDisplayed()
    }

    @Test
    fun `S030 RF-26 la procedencia se dice en texto y no solo con un icono o un color`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                DrawingsSheet(listOf(entry(CadOrigin.SHAPEFILE, "s1", "parcelas.zip")), DrawingsActions(), onDismiss = {})
            }
        }

        compose.onNodeWithTag(drawingOriginTag("s1")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Origen: Shapefile").assertIsDisplayed()
    }
}
