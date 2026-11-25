package es.topophone.app.ui.surface

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.formats.landxml.LandXmlSurfaceInfo
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.surface.SurfaceExtent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** En la pantalla de importación, una superficie sin nombre se llama como el fichero (S061/RF-1). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class SurfaceImportNameScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun info(index: Int, name: String) =
        LandXmlSurfaceInfo(index, name, true, 42, 60, 2, 440_000.0, 440_150.5, 4_474_000.0, 4_474_260.25, 600.0, 608.7)

    @Test
    fun `S061 RF-1 una superficie sin nombre sale con el nombre del fichero al elegirla`() {
        val state = SurfaceImportUiState(
            stage = SurfaceImportStage.CONFIG,
            fileName = "explanacion.xml",
            projectName = "Obra",
            projectCrs = ProjectCrs.ETRS89_UTM30,
            surfaces = listOf(info(0, ""), info(1, "rasante")),
            extent = SurfaceExtent(440_000.0, 440_150.5, 4_474_000.0, 4_474_260.25),
            distanceKm = 0.4,
        )

        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { SurfaceImportScreen(ThemeState.DEFAULT, {}, state, SurfaceImportActions(), onBack = {}) }
        }

        compose.onNodeWithText("explanacion · 42 puntos · 58 triángulos", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("rasante · 42 puntos", substring = true).performScrollTo().assertIsDisplayed()
    }
}
