package es.topophone.app.ui.cad

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.FakeMapController
import es.topophone.app.ui.map.MapCamera
import es.topophone.app.ui.map.fakeMap
import es.topophone.app.ui.projects.ProjectViewScreen
import es.topophone.app.ui.projects.ProjectViewUiState
import es.topophone.core.designsystem.theme.TopoPhoneTheme
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

/** La vista de proyecto lleva el dibujo al mapa y avisa de cuándo la cámara se ha parado (S040/RF-24). */
@RunWith(AndroidJUnit4::class)
class DesignLayerBindingTest {

    @get:Rule
    val compose = createComposeRule()

    private val project = Project(
        id = "p1",
        name = "Calle Mayor 12",
        author = "",
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

    private val scene = DesignScene(
        lines = listOf(DesignLine(doubleArrayOf(-3.70, 40.41, -3.69, 40.42), 0xFFFF0000.toInt())),
        points = listOf(DesignPoint(40.415, -3.695, 0xFF000000.toInt())),
    )

    private fun show(map: FakeMapController, design: DesignScene?, onCamera: (MapCamera) -> Unit = {}) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectViewScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectViewUiState(project),
                    onBack = {},
                    map = fakeMap(map),
                    design = design,
                    onDesignCamera = onCamera,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `S040 RF-24 la vista de proyecto lleva al mapa las lineas y los puntos del dibujo`() {
        val map = FakeMapController()

        show(map, scene)

        map.design shouldBe scene
    }

    @Test
    fun `S040 RF-27 sin dibujo visible el mapa no recibe nada`() {
        val map = FakeMapController()

        show(map, null)

        map.design shouldBe null
    }

    @Test
    fun `S040 RF-24 cuando la camara se para la vista avisa para recargar el dibujo`() {
        val map = FakeMapController()
        val seen = mutableListOf<MapCamera>()
        show(map, null) { seen += it }

        map.camera.value = MapCamera(40.41, -3.70, 16.0)
        compose.waitUntil(5_000) { seen.isNotEmpty() }

        seen.last() shouldBe MapCamera(40.41, -3.70, 16.0)
    }

    @Test
    fun `S040 RF-24 una camara que se mueve sin parar solo avisa de la ultima posicion`() {
        val map = FakeMapController()
        val seen = mutableListOf<MapCamera>()
        show(map, null) { seen += it }

        repeat(10) { map.camera.value = MapCamera(40.41 + it * 0.0001, -3.70, 16.0) }
        compose.waitUntil(5_000) { seen.isNotEmpty() }

        seen.size shouldBe 1
        seen.single().latitudeDeg shouldBe 40.41 + 9 * 0.0001
    }
}
