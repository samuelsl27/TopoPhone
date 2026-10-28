package es.topophone.app.ui.export

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.data.gpkg.GpkgExportCrs
import es.topophone.data.gpkg.GpkgExportSummary
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La pantalla «Exportar GeoPackage»: los puntos, el sistema, el nombre, guardar y compartir y los mensajes (S053/RF-6 a RF-12; RNF-T7, RNF-T8). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class GpkgExportScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun state(
        count: Int = 3,
        crs: GpkgExportCrs = GpkgExportCrs.PROJECT,
        canChoose: Boolean = true,
        srsId: Int = 25830,
        summary: GpkgExportSummary? = null,
        result: ExportResult? = null,
    ) = GpkgExportUiState(
        loaded = true,
        projectName = "Calle Mayor 12",
        pointCount = count,
        crs = crs,
        canChooseCrs = canChoose,
        srsId = srsId,
        projectSrsId = if (canChoose) 25830 else srsId,
        geographicSrsId = if (canChoose) 4258 else srsId,
        summary = summary,
        result = result,
    )

    private fun show(state: GpkgExportUiState) = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) {
            GpkgExportScreen(
                ThemeState.DEFAULT,
                {},
                state,
                GpkgExportActions(
                    onCrs = { calls += "crs:${it.name}" },
                    onSave = { calls += "save:$it" },
                    onShare = { calls += "share:$it" },
                ),
                onBack = {},
            )
        }
    }

    @Test
    fun `S053 RF-1 dice cuantos puntos se exportan en singular y en plural`() {
        show(state(count = 1))
        compose.onNodeWithTag(GPKG_COUNT_TAG).assertTextContains("Se exportará 1 punto")
    }

    @Test
    fun `S053 RF-1 con varios puntos lo dice en plural`() {
        show(state(count = 3))
        compose.onNodeWithTag(GPKG_COUNT_TAG).assertTextContains("Se exportarán 3 puntos")
    }

    @Test
    fun `S053 RF-11 sin puntos lo dice y no deja guardar ni compartir`() {
        show(state(count = 0))

        compose.onNodeWithTag(GPKG_COUNT_TAG).assertTextContains("No hay puntos que exportar.")
        compose.onNodeWithTag(GPKG_SAVE_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(GPKG_SHARE_TAG).assertIsNotEnabled()
    }

    @Test
    fun `S053 RF-6 y RF-7 en un proyecto UTM se elige entre el sistema del proyecto y las geograficas y se ve el codigo`() {
        show(state())

        compose.onNodeWithTag(GPKG_CRS_PROJECT_TAG).assertTextContains("EPSG:25830", substring = true)
        compose.onNodeWithTag(GPKG_CRS_PROJECT_TAG).assertIsSelected()
        compose.onNodeWithTag(GPKG_CRS_GEOGRAPHIC_TAG).performClick()

        calls shouldContainExactly listOf("crs:GEOGRAPHIC")
    }

    @Test
    fun `S053 RF-7 con las geograficas elegidas aparece elegida la opcion y su codigo`() {
        show(state(crs = GpkgExportCrs.GEOGRAPHIC, srsId = 4258))

        compose.onNodeWithTag(GPKG_CRS_GEOGRAPHIC_TAG).assertIsSelected()
        compose.onNodeWithTag(GPKG_CRS_GEOGRAPHIC_TAG).assertTextContains("EPSG:4258", substring = true)
    }

    @Test
    fun `S053 RF-6 en un proyecto en geograficas no se ofrece elegir y se dice el sistema`() {
        show(state(crs = GpkgExportCrs.GEOGRAPHIC, canChoose = false, srsId = 4258))

        compose.onAllNodesWithTag(GPKG_CRS_PROJECT_TAG).assertCountEquals(0)
        compose.onNodeWithTag(GPKG_CRS_FIXED_TAG).assertTextContains("EPSG:4258", substring = true)
    }

    @Test
    fun `S053 RF-9 muestra el nombre propuesto con el proyecto, geopackage y la fecha`() {
        show(state())

        compose.onNodeWithTag(GPKG_FILE_NAME_TAG).assertTextContains("Calle Mayor 12_geopackage_", substring = true)
        compose.onNodeWithTag(GPKG_FILE_NAME_TAG).assertTextContains(".gpkg", substring = true)
    }

    @Test
    fun `S053 RF-10 guardar y compartir llevan el nombre propuesto a las acciones`() {
        show(state())

        compose.onNodeWithTag(GPKG_SAVE_TAG).assertIsEnabled().performClick()
        compose.onNodeWithTag(GPKG_SHARE_TAG).performClick()

        calls.size shouldBe 2
        calls[0] shouldStartWith "save:Calle Mayor 12_geopackage_"
        calls[1] shouldStartWith "share:Calle Mayor 12_geopackage_"
    }

    @Test
    fun `S053 RNF-T8 los botones y las opciones miden al menos 56 dp`() {
        show(state())

        compose.onNodeWithTag(GPKG_SAVE_TAG).assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(GPKG_SHARE_TAG).assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(GPKG_CRS_PROJECT_TAG).assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(GPKG_CRS_GEOGRAPHIC_TAG).assertHeightIsAtLeast(56.dp)
    }

    @Test
    fun `S053 RF-12 si no se ha podido exportar lo dice y si se guardo tambien`() {
        show(state(result = ExportResult.FAILED))
        compose.onNodeWithTag(GPKG_RESULT_TAG).assertTextContains("No se ha podido exportar el fichero.")
    }

    @Test
    fun `S053 RF-10 un fichero guardado lo dice`() {
        show(state(result = ExportResult.SAVED))
        compose.onNodeWithTag(GPKG_RESULT_TAG).assertTextContains("Fichero guardado.")
    }

    @Test
    fun `S053 RF-8 el resumen dice los puntos y el sistema y avisa de los que van sin geometria`() {
        show(
            state(
                result = ExportResult.SAVED,
                summary = GpkgExportSummary(points = 3, withoutGeometry = 1, srsId = 25830, customDefinition = false),
            ),
        )

        compose.onNodeWithTag(GPKG_SUMMARY_TAG).assertTextContains("3 puntos", substring = true)
        compose.onNodeWithTag(GPKG_SUMMARY_TAG).assertTextContains("EPSG:25830", substring = true)
        compose.onNodeWithTag(GPKG_NO_GEOMETRY_TAG).assertTextContains("1 punto va sin geometría", substring = true)
        compose.onAllNodesWithTag(GPKG_CUSTOM_TAG).assertCountEquals(0)
    }

    @Test
    fun `S053 RF-8 un sistema sin codigo reconocido lo indica en el resumen`() {
        show(
            state(
                result = ExportResult.SAVED,
                summary = GpkgExportSummary(points = 3, withoutGeometry = 0, srsId = 100_000, customDefinition = true),
            ),
        )

        compose.onNodeWithTag(GPKG_CUSTOM_TAG).assertTextContains("definición completa", substring = true)
        compose.onAllNodesWithTag(GPKG_NO_GEOMETRY_TAG).assertCountEquals(0)
    }

    @Test
    fun `S053 RF-1 la pantalla avisa de que las coordenadas y los atributos van en metros y grados sin redondear`() {
        show(state())

        compose.onNodeWithTag(GPKG_UNITS_TAG).assertIsDisplayed()
        compose.onNodeWithTag(GPKG_UNITS_TAG).assertTextContains("metros y grados", substring = true)
    }
}
