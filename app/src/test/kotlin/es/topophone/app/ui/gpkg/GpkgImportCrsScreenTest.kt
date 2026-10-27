package es.topophone.app.ui.gpkg

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.formats.gpkg.GpkgCrs
import es.topophone.core.formats.gpkg.GpkgCrsCatalog
import es.topophone.core.model.cad.CadExtent
import es.topophone.core.model.cad.GpkgColumn
import es.topophone.core.model.cad.GpkgLayerInfo
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** El sistema de cada capa, su extensión, su distancia y el aviso de 100 km en la pantalla de importar un GeoPackage (S048/RF-8 a RF-12). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class GpkgImportCrsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val columns = listOf(GpkgColumn("nombre", "TEXT"))
    private fun layer(table: String, srs: Int = 25830) = GpkgLayerInfo(table, "geom", "POINT", 10, srs, columns)
    private val utm30 = GpkgCrsCatalog.resolveEpsg(25830)!!
    private val geographic = GpkgCrsCatalog.resolveEpsg(4258)!!
    private val wgs84 = GpkgCrsCatalog.resolveEpsg(4326)!!

    private fun config(vararg rows: GpkgLayerRow, far: Boolean = false, confirmed: Boolean = false, scanning: Boolean = false) =
        GpkgImportUiState(
            stage = GpkgImportStage.CONFIG,
            fileName = "capas.gpkg",
            layers = rows.toList(),
            farWarning = far,
            farConfirmed = confirmed,
            scanning = scanning,
        )

    private fun show(state: GpkgImportUiState, actions: GpkgImportActions = GpkgImportActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                GpkgImportScreen(ThemeState.DEFAULT, {}, state, actions, onBack = {})
            }
        }
    }

    @Test
    fun `S048 RF-8 una capa con sistema de la app lo muestra con su unidad`() {
        show(config(GpkgLayerRow(layer("vertices"), crs = utm30)))

        compose.onNodeWithTag(gpkgCrsTag("vertices")).assertIsDisplayed()
        compose.onNode(hasText("Sistema:", substring = true)).assertIsDisplayed()
        compose.onNodeWithTag(gpkgUnitTag("vertices")).assertIsDisplayed()
        compose.onNodeWithText("Unidad: metros").assertIsDisplayed()
    }

    @Test
    fun `S048 RF-10 una capa en geograficas dice que su unidad son grados`() {
        show(config(GpkgLayerRow(layer("ejes", 4258), crs = geographic)))

        compose.onNodeWithText("Unidad: grados").assertIsDisplayed()
    }

    @Test
    fun `S048 RF-8 una capa en WGS 84 avisa de que se trata como ETRS89`() {
        show(config(GpkgLayerRow(layer("puntos", 4326), crs = wgs84)))

        compose.onNode(hasText("WGS 84", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("Se trata como ETRS89: las coordenadas pueden diferir unos 0,9 m.").assertIsDisplayed()
    }

    @Test
    fun `S048 RF-8 si el usuario elige otro sistema el aviso de WGS 84 desaparece`() {
        show(config(GpkgLayerRow(layer("puntos", 4326), crs = wgs84, chosen = ProjectCrs.ETRS89_UTM30)))

        compose.onAllNodes(hasText("0,9 m", substring = true)).assertCountEquals(0)
    }

    @Test
    fun `S048 RF-9 sin sistema declarado pide elegir y muestra los sistemas de la app`() {
        val calls = mutableListOf<Pair<String, ProjectCrs?>>()
        show(
            config(GpkgLayerRow(layer("sin", 0), crs = GpkgCrs.Undeclared)),
            GpkgImportActions(onCrs = { table, crs -> calls += table to crs }),
        )

        compose.onNodeWithText("El fichero no declara el sistema de coordenadas de esta capa: elige uno.").assertIsDisplayed()
        compose.onNodeWithTag(gpkgCrsChoiceTag("sin", ProjectCrs.ETRS89_UTM30)).performScrollTo().performClick()
        compose.onNodeWithTag(gpkgCrsChoiceTag("sin", ProjectCrs.ED50_UTM31)).performScrollTo().assertIsDisplayed()

        calls shouldBe listOf("sin" to ProjectCrs.ETRS89_UTM30)
    }

    @Test
    fun `S048 RF-9 un sistema que la app no ofrece se dice con su codigo`() {
        show(config(GpkgLayerRow(layer("web", 3857), crs = GpkgCrs.Unavailable("EPSG", 3857))))

        compose.onNodeWithText("El sistema EPSG:3857 no está disponible en la app: elige uno.").assertIsDisplayed()
    }

    @Test
    fun `S048 RF-9 sin sistema elegido la capa marcada impide importar y con uno lo permite`() {
        val undeclared = GpkgLayerRow(layer("sin", 0), crs = GpkgCrs.Undeclared)
        show(config(undeclared))

        compose.onNodeWithTag(GPKG_IMPORT_BUTTON_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `S048 RF-9 tras elegir un sistema la capa ya se puede importar`() {
        show(config(GpkgLayerRow(layer("sin", 0), crs = GpkgCrs.Undeclared, chosen = ProjectCrs.ETRS89_UTM30)))

        compose.onNodeWithTag(GPKG_IMPORT_BUTTON_TAG).performScrollTo().assertIsEnabled()
        compose.onNode(hasText("Sistema:", substring = true)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S048 RF-8 Cambiar el sistema muestra los sistemas y tocar el elegido lo quita`() {
        val calls = mutableListOf<ProjectCrs?>()
        show(
            config(GpkgLayerRow(layer("vertices"), crs = utm30, chosen = ProjectCrs.ETRS89_UTM29)),
            GpkgImportActions(onCrs = { _, crs -> calls += crs }),
        )

        compose.onNodeWithTag(gpkgCrsChoiceTag("vertices", ProjectCrs.ETRS89_UTM31)).assertDoesNotExist()
        compose.onNodeWithTag(gpkgCrsChangeTag("vertices")).performScrollTo().performClick()
        compose.onNodeWithTag(gpkgCrsChoiceTag("vertices", ProjectCrs.ETRS89_UTM29)).performScrollTo().assertIsSelected()
        compose.onNodeWithTag(gpkgCrsChoiceTag("vertices", ProjectCrs.ETRS89_UTM29)).performClick()

        calls shouldBe listOf(null)
    }

    @Test
    fun `S048 RF-12 muestra la extension de la capa y su distancia al proyecto`() {
        show(
            config(
                GpkgLayerRow(
                    layer("vertices"),
                    crs = utm30,
                    extent = CadExtent(440_100.0, 440_300.0, 4_474_100.0, 4_474_180.0),
                    distanceKm = 0.14,
                ),
            ),
        )

        compose.onNodeWithTag(gpkgExtentTag("vertices")).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Este: de 440.100,0 a 440.300,0")).assertIsDisplayed()
        compose.onNode(hasText("Norte: de 4.474.100,0 a 4.474.180,0")).assertIsDisplayed()
        compose.onNode(hasText("Distancia al proyecto: 0,1 km")).assertIsDisplayed()
    }

    @Test
    fun `S048 RF-12 una capa que no cabe en el proyecto lo dice`() {
        show(config(GpkgLayerRow(layer("canarias"), crs = utm30, outsideArea = true)))

        compose.onNodeWithTag(gpkgOutsideTag("canarias")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(gpkgExtentTag("canarias")).assertDoesNotExist()
    }

    @Test
    fun `S048 RF-12 mientras calcula no muestra extensiones y lo dice`() {
        show(config(GpkgLayerRow(layer("vertices"), crs = utm30, extent = CadExtent(1.0, 2.0, 3.0, 4.0)), scanning = true))

        compose.onNodeWithTag(GPKG_SCANNING_TAG).assertIsDisplayed()
        compose.onNodeWithTag(gpkgExtentTag("vertices")).assertDoesNotExist()
        compose.onNodeWithTag(GPKG_IMPORT_BUTTON_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `S048 RF-12 a mas de 100 km avisa y pide confirmar antes de importar`() {
        var confirmed = 0
        show(
            config(GpkgLayerRow(layer("vertices"), crs = utm30, distanceKm = 387.2), far = true),
            GpkgImportActions(onConfirmFar = { confirmed++ }),
        )

        compose.onNodeWithTag(GPKG_FAR_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(GPKG_IMPORT_BUTTON_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(GPKG_FAR_CONFIRM_TAG).performScrollTo().performClick()

        confirmed shouldBe 1
    }

    @Test
    fun `S048 RF-12 confirmado el aviso se puede importar`() {
        show(config(GpkgLayerRow(layer("vertices"), crs = utm30, distanceKm = 387.2), far = true, confirmed = true))

        compose.onNodeWithTag(GPKG_FAR_CONFIRM_TAG).performScrollTo().assertIsOn()
        compose.onNodeWithTag(GPKG_IMPORT_BUTTON_TAG).performScrollTo().assertIsEnabled()
    }

    @Test
    fun `S048 RF-12 sin aviso no hay casilla de confirmacion`() {
        show(config(GpkgLayerRow(layer("vertices"), crs = utm30, distanceKm = 0.1)))

        compose.onNodeWithTag(GPKG_FAR_TAG).assertDoesNotExist()
        compose.onNodeWithTag(GPKG_FAR_CONFIRM_TAG).assertDoesNotExist()
    }
}
