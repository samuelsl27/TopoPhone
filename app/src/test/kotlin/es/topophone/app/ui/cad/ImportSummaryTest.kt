package es.topophone.app.ui.cad

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.crs.LibrarySourceUi
import es.topophone.app.ui.crs.SOURCE_SUMMARY_TAG
import es.topophone.app.ui.crs.sourceSummaryText
import es.topophone.app.ui.gpkg.GpkgSummaryLabels
import es.topophone.app.ui.gpkg.GpkgSummaryText
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadImportOptions
import es.topophone.core.model.cad.CadImportSummary
import es.topophone.core.model.cad.GpkgImportSummary
import es.topophone.core.model.cad.GpkgLayerSummary
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LibraryCrs
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * El resumen de una importación dice el SC de origen de la biblioteca, el cambio de datum con su precisión y lo importado e ignorado, en pantalla y
 * en el texto que se guarda (S057/RF-11, RF-18). Con un SC de España el resumen no cambia (RF-27). Interfaz en es-ES.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ImportSummaryTest {

    @get:Rule
    val compose = createComposeRule()

    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    private fun library(epsg: Int, at: GeoPoint): LibrarySourceUi {
        val definition = when (val r = CrsCatalog.bundled.build(epsg, CorrectionsDatum.ETRS89, at)) {
            is CrsBuildResult.Ready -> r.definition
            is CrsBuildResult.NeedsGrids -> r.definition
            is CrsBuildResult.Unavailable -> error("EPSG:$epsg")
        }
        return LibrarySourceUi.of(LibraryCrs(definition, CorrectionsDatum.ETRS89), at)
    }

    private val stereo70 = library(3844, GeoPoint(44.43, 26.1))
    private val lambert93 = library(2154, GeoPoint(48.0, 2.0))

    private val summary = CadImportSummary(
        imported = mapOf(CadEntityKind.POINT to 5),
        ignored = emptyMap(),
        layers = 1,
        incomplete = false,
    )

    @Test
    fun `S057 RF-18 el texto dice el SC de origen y el cambio de datum con su precision`() {
        val text = sourceSummaryText(resources, stereo70)

        text shouldContain "Sistema de origen: Pulkovo 1942(58) / Stereo70 (EPSG:3844)"
        text shouldContain "Cambio de datum: Pulkovo 1942(58) to ETRS89 (4)"
        text shouldContain "precisión declarada: 3,0 m"
    }

    @Test
    fun `S057 RF-18 con el mismo datum el resumen dice que no hay cambio de datum`() {
        sourceSummaryText(resources, lambert93) shouldContain "Sin cambio de datum"
    }

    @Test
    fun `S057 RF-18 el resumen en pantalla de un DXF con un SC de la biblioteca lo dice y con uno de Espana no`() {
        val withLibrary = CadImportUiState(
            stage = CadImportStage.RESULT,
            fileName = "plano.dxf",
            options = CadImportOptions(source = stereo70.source),
            library = stereo70,
            result = CadImportOutcome("d1", summary),
        )
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { CadImportScreen(ThemeState.DEFAULT, {}, withLibrary, CadImportActions(), {}) }
        }

        compose.onNodeWithTag(SOURCE_SUMMARY_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S057 RF-27 el resumen en pantalla de un DXF de Espana no lleva el bloque del SC de origen`() {
        val spain = CadImportUiState(stage = CadImportStage.RESULT, fileName = "plano.dxf", result = CadImportOutcome("d1", summary))
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { CadImportScreen(ThemeState.DEFAULT, {}, spain, CadImportActions(), {}) }
        }

        compose.onNodeWithTag(SOURCE_SUMMARY_TAG).assertDoesNotExist()
    }

    @Test
    fun `S057 RF-18 el texto que se guarda de un DXF lleva el SC de origen y sin el no cambia`() {
        val labels = CadSummaryLabels("Resumen", "Importados", "Ignorados", "Capas", "Incompleto", { it.name }, { it.reason.name })

        val plain = CadSummaryText.build(summary, labels)
        val sourceLabels = CadSummaryLabels(
            "Resumen",
            "Importados",
            "Ignorados",
            "Capas",
            "Incompleto",
            { it.name },
            { it.reason.name },
            source = sourceSummaryText(resources, stereo70),
        )
        val withSource = CadSummaryText.build(summary, sourceLabels)

        plain shouldNotContain "Sistema de origen"
        withSource shouldContain "Sistema de origen: Pulkovo 1942(58) / Stereo70"
        withSource.lines().first() shouldBe "Resumen"
    }

    @Test
    fun `S057 RF-18 el texto que se guarda de un GeoPackage lleva el SC de origen de cada capa`() {
        val gpkg = GpkgImportSummary(
            layers = listOf(GpkgLayerSummary("rios", 3, 0), GpkgLayerSummary("parcelas", 2, 0)),
        )
        val labels = GpkgSummaryLabels(
            "T", "Capa", "Imp", "Fuera", "Ign", "Omit", "Total", "Dejadas", "WGS84", { it.name },
            source = { table -> if (table == "rios") sourceSummaryText(resources, stereo70) else null },
        )

        val text = GpkgSummaryText.build(gpkg, labels)

        text shouldContain "Capa: rios\n  Sistema de origen: Pulkovo 1942(58) / Stereo70 (EPSG:3844)"
        text shouldContain "Capa: parcelas\n  Imp: 2"
    }
}
