package es.topophone.app.ui.surface

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.CameraPolicy
import es.topophone.app.ui.map.FakeMapController
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.surface.Surface
import es.topophone.core.model.surface.SurfaceExtent
import es.topophone.core.survey.CadPlacement
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** «Ir a la superficie»: el botón de la ficha, el encuadre con margen y que el mapa deja de seguir al jalón (S061/RF-14, RF-15). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class GoToSurfaceTest {

    @get:Rule
    val compose = createComposeRule()

    private val extent = SurfaceExtent(440_000.0, 441_000.0, 4_474_000.0, 4_474_500.0)

    private val surface = Surface(
        id = "s1",
        projectId = "p1",
        name = "terreno",
        fileName = "obra.xml",
        importedAtMs = 1_792_000_000_000L,
        source = CadSourceCrs.Project,
        unit = LinearUnit.METRE,
        extent = extent,
        minElevationM = 600.0,
        maxElevationM = 608.7,
        vertexCount = 42,
        triangleCount = 58,
        holeCount = 2,
    )

    @Test
    fun `S061 RF-14 el boton de la ficha pide ir a esa superficie`() {
        val calls = mutableListOf<String>()
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                SurfacesSheet(SurfacesUiState(listOf(surface)), SurfacesActions(onGoTo = { calls += "goto:${it.id}" }), onDismiss = {})
            }
        }

        compose.onNodeWithTag(surfaceGoToTag("s1")).performScrollTo().assertHeightIsAtLeast(56.dp).performClick()

        calls shouldBe listOf("goto:s1")
    }

    @Test
    fun `S061 RF-14 el encuadre es la extension con un margen del 10 por ciento`() {
        val box = SurfaceGoTo.bounds(ProjectCrs.ETRS89_UTM30, extent).shouldNotBeNull()

        val sw = CadPlacement.geographic(ProjectCrs.ETRS89_UTM30, 439_900.0, 4_473_950.0)!!
        val ne = CadPlacement.geographic(ProjectCrs.ETRS89_UTM30, 441_100.0, 4_474_550.0)!!
        // El recuadro de las esquinas ampliadas: contiene las de la extensión con margen y no mucho más (la cuadrícula UTM gira poco).
        (box.south <= sw.first && box.west <= sw.second && box.north >= ne.first && box.east >= ne.second) shouldBe true
        (box.north - box.south < (ne.first - sw.first) * 1.05) shouldBe true
    }

    @Test
    fun `S061 RF-15 encuadrar cuenta como mover el mapa a mano y se deja de seguir al jalon`() = runBlocking<Unit> {
        val map = FakeMapController()
        val policy = CameraPolicy()
        policy.onCenterPressed(GeoPoint(40.42, -3.70), null)
        policy.following shouldBe true
        val gesture = launch(start = CoroutineStart.UNDISPATCHED) { map.userGestures.first().also { policy.onUserGesture() } }

        map.fitBounds(SurfaceGoTo.bounds(ProjectCrs.ETRS89_UTM30, extent)!!)
        gesture.join()

        map.fitted.size shouldBe 1
        policy.following shouldBe false
    }
}
