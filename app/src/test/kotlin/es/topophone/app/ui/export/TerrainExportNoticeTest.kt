package es.topophone.app.ui.export

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Shapefile y GeoPackage de un proyecto en terreno van en cuadrícula y la pantalla lo dice; sin terreno no hay aviso (S058/RF-15). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class TerrainExportNoticeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun showShp(terrain: Boolean) = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) {
            ShpExportScreen(
                ThemeState.DEFAULT,
                {},
                ShpExportUiState(loaded = true, projectName = "Obra", pointCount = 3, epsg = 25830, projectEpsg = 25830, terrain = terrain),
                ShpExportActions(),
                onBack = {},
            )
        }
    }

    private fun showGpkg(terrain: Boolean) = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) {
            GpkgExportScreen(
                ThemeState.DEFAULT,
                {},
                GpkgExportUiState(
                    loaded = true,
                    projectName = "Obra",
                    pointCount = 3,
                    srsId = 25830,
                    projectSrsId = 25830,
                    terrain = terrain,
                ),
                GpkgExportActions(),
                onBack = {},
            )
        }
    }

    @Test
    fun `S058 RF-15 el Shapefile de un proyecto en terreno dice que va en cuadricula`() {
        showShp(terrain = true)

        compose.onNodeWithTag(SHP_TERRAIN_NOTICE_TAG).assertIsDisplayed()
    }

    @Test
    fun `S058 RF-15 el Shapefile de un proyecto en cuadricula no lleva aviso`() {
        showShp(terrain = false)

        compose.onNodeWithTag(SHP_TERRAIN_NOTICE_TAG).assertDoesNotExist()
    }

    @Test
    fun `S058 RF-15 el GeoPackage de un proyecto en terreno dice que va en cuadricula`() {
        showGpkg(terrain = true)

        compose.onNodeWithTag(GPKG_TERRAIN_NOTICE_TAG).assertIsDisplayed()
    }

    @Test
    fun `S058 RF-15 el GeoPackage de un proyecto en cuadricula no lleva aviso`() {
        showGpkg(terrain = false)

        compose.onNodeWithTag(GPKG_TERRAIN_NOTICE_TAG).assertDoesNotExist()
    }
}
