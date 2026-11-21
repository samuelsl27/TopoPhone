package es.topophone.app.ui.crs

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CorrectionFrame
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.GridEntry
import es.topophone.core.geodesy.catalog.UnavailableReason
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LibraryCrs
import es.topophone.data.grids.GridState
import es.topophone.data.grids.GridStore
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La ficha de un SC de la biblioteca: datos, transformación, rejillas y geoides (S034/RF-8 a RF-12, RF-14). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class CrsDetailsTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val catalog = CrsCatalog.bundled
    private val madrid = GeoPoint(40.4, -3.7)
    private val directory by lazy { folder.newFolder("grids") }
    private val store by lazy { GridStore(directory) { emptyList() } }
    private val vm by lazy { CrsDetailsViewModel(store) }

    @Test
    fun `S034 RF-9 ETRS89 UTM 30 se elige sin transformacion ni descargas y trae sus datos`() {
        vm.load(25830, CorrectionsDatum.ETRS89, madrid)

        val state = vm.state.value
        state.crs.shouldNotBeNull().name shouldBe "ETRS89 / UTM zone 30N"
        state.datumName shouldBe "ETRS89"
        state.ellipsoid.startsWith("a = 6378137") shouldBe true
        state.selected.shouldNotBeNull().steps.size shouldBe 1
        state.result.shouldBeInstanceOf<CrsBuildResult.Ready>()
        state.chosen.shouldNotBeNull().definition.epsg shouldBe 25830
    }

    @Test
    fun `S034 RF-10 en ED50 se propone la rejilla del IGN para Madrid y se puede elegir otra transformacion`() {
        vm.load(23030, CorrectionsDatum.ETRS89, madrid)

        val state = vm.state.value
        state.selected.shouldNotBeNull().gridNames shouldBe listOf("es_ign_SPED2ETV2.tif")
        state.transformations.first().coversCentre shouldBe true
        state.transformations.first().grids.single().state shouldBe GridState.Bundled
        (state.transformations.size > 3) shouldBe true
        state.transformations.any { !it.coversCentre } shouldBe true

        val other = state.transformations.first { it.option.gridNames.isEmpty() && it.coversCentre }.option
        vm.selectTransformation(other)
        vm.state.value.chosen.shouldNotBeNull().definition.transformationName shouldBe other.name
    }

    @Test
    fun `S034 RF-12 una rejilla que falta se nombra con su tamano y licencia y al instalarla la ficha pasa a poder usarse`() {
        vm.load(27700, CorrectionsDatum.ETRS89, GeoPoint(51.5, -0.12))

        val missing = vm.state.value.result.shouldBeInstanceOf<CrsBuildResult.NeedsGrids>()
        missing.grids.single().name shouldBe "uk_os_OSTN15_NTv2_OSGBtoETRS.tif"
        vm.state.value.chosen.shouldNotBeNull() // se puede elegir: las coordenadas quedan no disponibles hasta descargarla (RF-18)

        File(directory, "uk_os_OSTN15_NTv2_OSGBtoETRS.tif").writeBytes(ByteArray(8))
        vm.refresh()

        vm.state.value.result.shouldBeInstanceOf<CrsBuildResult.Ready>()
    }

    @Test
    fun `S034 RF-8 un SC sin transformacion hasta el datum de las correcciones no se puede elegir y dice por que`() {
        val withoutPath = catalog.crs.first { catalog.optionsFor(it.datumCode, CorrectionFrame.ETRS89).isEmpty() }

        vm.load(withoutPath.epsg, CorrectionsDatum.ETRS89, madrid)

        vm.state.value.unavailableReason shouldBe UnavailableReason.NO_TRANSFORMATION
        vm.state.value.chosen.shouldBeNull()
    }

    @Test
    fun `S034 RF-14 se ofrecen la altura elipsoidal y los geoides de la zona, y elegir uno lo guarda en el SC`() {
        vm.load(25830, CorrectionsDatum.ETRS89, madrid)

        val geoids = vm.state.value.geoids
        geoids.shouldNotBeEmpty()
        geoids.any { it.entry.name == "es_ign_egm08-rednap.tif" && it.state == GridState.Bundled } shouldBe true
        vm.state.value.chosen.shouldNotBeNull().geoidGrid.shouldBeNull() // por defecto, elipsoidal

        vm.selectGeoid("es_ign_egm08-rednap.tif")
        vm.state.value.chosen.shouldNotBeNull().geoidGrid shouldBe "es_ign_egm08-rednap.tif"

        vm.selectGeoid(null)
        vm.state.value.chosen.shouldNotBeNull().geoidGrid.shouldBeNull()
    }

    @Test
    fun `S034 RF-14 un geoide inicial que no es de la zona se ignora`() {
        vm.load(25830, CorrectionsDatum.ETRS89, madrid, initialGeoid = "au_ga_AUSGeoid2020_20180201.tif")

        vm.state.value.geoid.shouldBeNull()
    }

    @Test
    fun `S034 RF-9 la ficha muestra la proyeccion, sus parametros, la transformacion y el boton de usar`() {
        vm.load(25830, CorrectionsDatum.ETRS89, madrid)
        var used: LibraryCrs? = null
        val current = vm.state.value
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                CrsDetailsScreen(ThemeState.DEFAULT, {}, current, CrsDetailsActions(onUse = { used = it }))
            }
        }

        compose.onNodeWithText(text(R.string.crs_method_tm)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.crs_param_scale_factor)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(crsTransformationTag(0)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(CRS_ELLIPSOIDAL_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(CRS_USE_TAG).performScrollTo().assertIsEnabled().performClick()

        used.shouldNotBeNull().epsg shouldBe 25830
    }

    @Test
    fun `S034 RF-12 RF-19 la ficha ofrece descargar la rejilla que falta y no deja usar un SC sin transformacion`() {
        vm.load(27700, CorrectionsDatum.ETRS89, GeoPoint(51.5, -0.12))
        var asked: GridEntry? = null
        val current = vm.state.value
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                CrsDetailsScreen(ThemeState.DEFAULT, {}, current, CrsDetailsActions(onDownload = { asked = it }))
            }
        }

        compose.onNodeWithTag(crsGridDownloadTag("uk_os_OSTN15_NTv2_OSGBtoETRS.tif")).performScrollTo().assertIsDisplayed().performClick()
        asked.shouldNotBeNull().name shouldBe "uk_os_OSTN15_NTv2_OSGBtoETRS.tif"
    }

    @Test
    fun `S034 RF-8 con un SC no elegible el boton de usar esta desactivado y se explica el motivo`() {
        val withoutPath = catalog.crs.first { catalog.optionsFor(it.datumCode, CorrectionFrame.ETRS89).isEmpty() }
        vm.load(withoutPath.epsg, CorrectionsDatum.ETRS89, madrid)
        val current = vm.state.value
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { CrsDetailsScreen(ThemeState.DEFAULT, {}, current, CrsDetailsActions()) }
        }

        compose.onNodeWithTag(CRS_UNAVAILABLE_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(CRS_USE_TAG).performScrollTo().assertIsNotEnabled()
    }
}
