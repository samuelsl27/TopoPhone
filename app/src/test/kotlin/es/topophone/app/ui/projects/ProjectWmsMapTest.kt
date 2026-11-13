package es.topophone.app.ui.projects

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.FakeMapController
import es.topophone.app.ui.map.WmsLayerPlan
import es.topophone.app.ui.map.WmsLayerSource
import es.topophone.app.ui.map.WmsPlanLayer
import es.topophone.app.ui.map.fakeMap
import es.topophone.app.ui.wms.ProjectLayersUiState
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.map.Basemap
import es.topophone.core.model.map.MapSettings
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

/** Las capas WMS del proyecto llegan al mapa y su atribución se ve junto a la del mapa base (S036/RF-22, RF-29, RF-30, RF-33). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ProjectWmsMapTest {

    @get:Rule
    val compose = createComposeRule()

    private val map = FakeMapController()
    private val project = Project(
        id = "p1",
        name = "Calle Mayor 12",
        author = "S. S.",
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
    private val ortofoto = WmsPlanLayer("l1", 1f, WmsLayerSource.Tiles("https://a.es/wms?BBOX={bbox-epsg-3857}"), "© Ortofoto de ejemplo")
    private val mapa = WmsPlanLayer("l2", 0.5f, WmsLayerSource.Tiles("https://b.es/wms?BBOX={bbox-epsg-3857}"), "© Mapa de ejemplo")

    private val layers = mutableStateOf(ProjectLayersUiState())

    private fun show(settings: MapSettings = MapSettings(Basemap.IGN_VECTOR, cadastre = true), plan: WmsLayerPlan = WmsLayerPlan.EMPTY) {
        layers.value = ProjectLayersUiState(plan = plan)
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectViewScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectViewUiState(project, mapSettings = settings),
                    onBack = {},
                    map = fakeMap(map),
                    projectLayers = layers.value,
                )
            }
        }
    }

    @Test
    fun `S036 RF-29 las capas del proyecto llegan al mapa al abrirlo`() {
        val plan = WmsLayerPlan(listOf(mapa, ortofoto))

        show(plan = plan)
        compose.waitForIdle()

        map.wmsPlan shouldBe plan
    }

    @Test
    fun `S036 RF-22 al cambiar las capas el mapa recibe el plan nuevo y no vuelve a recibir los ajustes del mapa base ni del Catastro`() {
        show()
        compose.waitForIdle()
        map.wmsPlan shouldBe WmsLayerPlan.EMPTY
        val settingsBefore = map.settings.toList()

        layers.value = ProjectLayersUiState(plan = WmsLayerPlan(listOf(ortofoto)))
        compose.waitForIdle()

        map.wmsPlan shouldBe WmsLayerPlan(listOf(ortofoto))
        map.settings shouldBe settingsBefore
    }

    @Test
    fun `S036 RF-30 la atribucion de cada servicio con una capa visible se ve con la del mapa base y la del Catastro`() {
        show(plan = WmsLayerPlan(listOf(mapa, ortofoto)))

        compose.onNodeWithTag(ATTRIBUTION_TAG).assertIsDisplayed()
            .assert(hasText("CC BY 4.0 scne.es", substring = true))
            .assert(hasText("Dirección General del Catastro", substring = true))
            .assert(hasText("© Ortofoto de ejemplo · © Mapa de ejemplo", substring = true))
    }

    @Test
    fun `S036 RF-30 una atribucion que ya pone el mapa no se repite aunque la traiga una capa WMS`() {
        val catastro =
            WmsPlanLayer("l3", 1f, WmsLayerSource.Tiles("https://c.es/wms?BBOX={bbox-epsg-3857}"), "© Dirección General del Catastro")
        show(MapSettings(Basemap.NONE, cadastre = true), WmsLayerPlan(listOf(catastro, ortofoto)))

        val text = compose.onNodeWithTag(
            ATTRIBUTION_TAG,
        ).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text]
            .joinToString("") { it.text }
        text shouldBe "© Dirección General del Catastro · © Ortofoto de ejemplo"
    }

    @Test
    fun `S036 RF-30 sin mapa base ni Catastro la atribucion es solo la de las capas`() {
        show(MapSettings(Basemap.NONE), WmsLayerPlan(listOf(ortofoto)))

        compose.onNodeWithTag(ATTRIBUTION_TAG).assert(hasText("© Ortofoto de ejemplo"))
    }

    @Test
    fun `S036 RF-30 sin capas WMS la atribucion es la de siempre`() {
        show(MapSettings(Basemap.OSM_VECTOR))

        compose.onNodeWithTag(ATTRIBUTION_TAG).assert(hasText("OpenFreeMap © OpenMapTiles Data from OpenStreetMap"))
    }
}
