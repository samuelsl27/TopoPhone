package es.topophone.app.ui.gpkg

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.FakeMapController
import es.topophone.app.ui.map.MapTap
import es.topophone.app.ui.projects.CENTRAL_POINT_PICKER_ACCEPT_TAG
import es.topophone.app.ui.projects.CENTRAL_POINT_PICKER_TAG
import es.topophone.app.ui.projects.CENTRAL_POINT_PICK_TAG
import es.topophone.app.ui.projects.CENTRAL_POINT_POSITION_TAG
import es.topophone.app.ui.projects.CENTRAL_POINT_TYPE_TAG
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.formats.gpkg.GpkgCrsCatalog
import es.topophone.core.model.cad.GpkgColumn
import es.topophone.core.model.cad.GpkgLayerInfo
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.CentralPoint
import es.topophone.core.survey.CentralPointResult
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La zona de importación en la pantalla de importar un GeoPackage: radio, todo el contenido, recuento y punto central (S048/RF-13, RF-14, RF-16). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class GpkgImportZoneScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val layer = GpkgLayerInfo("vertices", "geom", "POINT", 10, 25830, listOf(GpkgColumn("nombre", "TEXT")))
    private val row = GpkgLayerRow(layer, crs = GpkgCrsCatalog.resolveEpsg(25830)!!)
    private val madrid = CentralPoint(40.4168, -3.7038)

    private fun state(
        areaAll: Boolean = false,
        radiusM: Double = 5_000.0,
        hasCenter: Boolean = true,
        zone: GpkgZoneUi = GpkgZoneUi(objects = 1_234),
        centralPoint: CentralPoint? = madrid,
    ) = GpkgImportUiState(
        stage = GpkgImportStage.CONFIG,
        fileName = "capas.gpkg",
        layers = listOf(row),
        areaAll = areaAll,
        radiusM = radiusM,
        hasCenter = hasCenter,
        zone = zone,
        centralPoint = centralPoint,
    )

    private fun show(
        state: GpkgImportUiState,
        actions: GpkgImportActions = GpkgImportActions(),
        map: es.topophone.app.ui.projects.MapSlot? = null,
    ) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                GpkgImportScreen(ThemeState.DEFAULT, {}, state, actions, onBack = {}, map = map)
            }
        }
    }

    @Test
    fun `S048 RF-13 propone el radio y deja elegir Todo el contenido`() {
        val calls = mutableListOf<Boolean>()
        show(state(), GpkgImportActions(onAllContent = { calls += it }))

        compose.onNodeWithTag(GPKG_ZONE_RADIUS_TAG).performScrollTo().assertIsSelected()
        compose.onNodeWithText("Radio: 5 km").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(GPKG_ZONE_ALL_TAG).performScrollTo().performClick()

        calls shouldBe listOf(true)
    }

    @Test
    fun `S048 RF-13 Todo el contenido oculta el radio y el punto central`() {
        show(state(areaAll = true))

        compose.onNodeWithTag(GPKG_ZONE_ALL_TAG).performScrollTo().assertIsSelected()
        compose.onNodeWithTag(GPKG_RADIUS_SLIDER_TAG).assertDoesNotExist()
        compose.onNodeWithTag(GPKG_RADIUS_VALUE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(CENTRAL_POINT_TYPE_TAG).assertDoesNotExist()
    }

    @Test
    fun `S048 RF-13 el radio se escribe en metros o en kilometros`() {
        show(state(radiusM = 250.0))
        compose.onNodeWithTag(GPKG_RADIUS_VALUE_TAG).performScrollTo()
        compose.onNodeWithText("Radio: 250 m").assertIsDisplayed()
    }

    @Test
    fun `S048 RF-13 un radio grande se escribe con un decimal`() {
        show(state(radiusM = 12_300.0))

        compose.onNodeWithText("Radio: 12,3 km").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S048 RF-13 mover el control cambia el radio`() {
        val radii = mutableListOf<Double>()
        show(state(), GpkgImportActions(onRadius = { radii += it }))

        compose.onNodeWithTag(GPKG_RADIUS_SLIDER_TAG).performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }

        radii.last() shouldBe 100_000.0
    }

    @Test
    fun `S048 RF-13 el control va de 100 m a 100 km en escala logaritmica y redondea`() {
        GpkgRadius.fromPosition(0f) shouldBe 100.0
        GpkgRadius.fromPosition(1f) shouldBe 100_000.0
        GpkgRadius.fromPosition(-1f) shouldBe 100.0
        GpkgRadius.fromPosition(2f) shouldBe 100_000.0
        GpkgRadius.toPosition(100.0) shouldBe 0f
        GpkgRadius.toPosition(100_000.0) shouldBe 1f
        GpkgRadius.toPosition(5_000.0).toDouble() shouldBe (0.5663 plusOrMinus 1e-3)
        GpkgRadius.fromPosition(GpkgRadius.toPosition(5_000.0)) shouldBe 5_000.0
        GpkgRadius.fromPosition(GpkgRadius.toPosition(250.0)) shouldBe 250.0
        GpkgRadius.fromPosition(GpkgRadius.toPosition(50_000.0)) shouldBe 50_000.0
        val positions = (0..100).map { GpkgRadius.fromPosition(it / 100f) }
        positions shouldBe positions.sorted()
        positions.all { it in 100.0..100_000.0 } shouldBe true
    }

    @Test
    fun `S048 RF-13 el recuento de objetos de la zona se lee al terminar`() {
        show(state(zone = GpkgZoneUi(objects = 1_234)))

        compose.onNodeWithTag(GPKG_ZONE_COUNT_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Objetos en la zona: 1.234").assertIsDisplayed()
    }

    @Test
    fun `S048 RF-13 mientras cuenta lo dice y no da cifra`() {
        show(state(zone = GpkgZoneUi(counting = true, objects = 7)))

        compose.onNodeWithTag(GPKG_ZONE_COUNTING_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(GPKG_ZONE_COUNT_TAG).assertDoesNotExist()
    }

    @Test
    fun `S048 RF-16 pasar de 500000 objetos dice que hay que reducir la zona o las capas`() {
        show(state(zone = GpkgZoneUi(objects = 500_001, exceeded = true)))

        compose.onNodeWithTag(GPKG_ZONE_LIMIT_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Hay más de 500.000 objetos en la zona. Reduce la zona o las capas.").assertIsDisplayed()
        compose.onNodeWithTag(GPKG_ZONE_COUNT_TAG).assertDoesNotExist()
        compose.onNodeWithTag(GPKG_IMPORT_BUTTON_TAG).performScrollTo()
    }

    @Test
    fun `S048 RF-14 sin centro pide fijar el punto central o elegir Todo el contenido`() {
        show(state(hasCenter = false, centralPoint = null))

        compose.onNodeWithTag(GPKG_NO_CENTER_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("El proyecto no tiene centro. Fija el punto central o elige «Todo el contenido».").assertIsDisplayed()
        compose.onNodeWithTag(GPKG_ZONE_COUNT_TAG).assertDoesNotExist()
        compose.onNodeWithTag(CENTRAL_POINT_TYPE_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S048 RF-14 con Todo el contenido ya no hace falta el centro`() {
        show(state(areaAll = true, hasCenter = false, centralPoint = null))

        compose.onNodeWithTag(GPKG_NO_CENTER_TAG).assertDoesNotExist()
        compose.onNodeWithTag(GPKG_ZONE_COUNT_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S048 RF-14 Usar mi posicion fija el punto central desde la importacion`() {
        var used = 0
        show(state(hasCenter = false, centralPoint = null), GpkgImportActions(onCentralPosition = { used++ }))

        compose.onNodeWithTag(CENTRAL_POINT_POSITION_TAG).performScrollTo().performClick()

        used shouldBe 1
    }

    @Test
    fun `S048 RF-14 sin mapa no se ofrece Elegir en el mapa`() {
        show(state(hasCenter = false, centralPoint = null))

        compose.onNodeWithTag(CENTRAL_POINT_PICK_TAG).assertDoesNotExist()
    }

    @Test
    fun `S048 RF-14 Elegir en el mapa abre el selector a pantalla completa y Aceptar vuelve con el punto`() {
        val fake = FakeMapController()
        val picked = mutableListOf<CentralPoint>()
        val actions = GpkgImportActions(
            onCentralTap = { lat, lon -> CentralPointResult.Fixed(CentralPoint(lat, lon)) },
            onCentralPicked = { picked += it },
        )
        show(
            state(hasCenter = false, centralPoint = null),
            actions,
            map = { modifier, onReady ->
                LaunchedEffect(Unit) { onReady(fake) }
                Box(modifier)
            },
        )

        compose.onNodeWithTag(CENTRAL_POINT_PICK_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(CENTRAL_POINT_PICKER_TAG).assertIsDisplayed()
        compose.runOnIdle { fake.tap(MapTap(40.4168, -3.7038, toleranceM = 5.0)) }
        compose.waitForIdle()
        compose.onNodeWithTag(CENTRAL_POINT_PICKER_ACCEPT_TAG).performClick()

        picked shouldBe listOf(CentralPoint(40.4168, -3.7038))
        compose.onNodeWithTag(CENTRAL_POINT_PICKER_TAG).assertDoesNotExist()
    }
}
