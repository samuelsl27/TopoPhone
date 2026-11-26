package es.topophone.app.ui.points

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.options.BAR_GROUPS
import es.topophone.app.ui.options.OptionGroup
import es.topophone.app.ui.options.ProjectOption
import es.topophone.app.ui.options.ProjectOptions
import es.topophone.app.ui.options.chooseOption
import es.topophone.app.ui.options.groupButtonTag
import es.topophone.app.ui.options.optionTag
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Los menús de la lista de Objetos: la barra de grupos y el «⋮» llevan cada opción a la acción de su spec de origen y no pierden ninguna
 * (S059/RF-1 a RF-4, RF-6, RF-7, RF-10b).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ObjectListMenusTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun call(name: String): () -> Unit = { calls += name }

    private val actions = ObjectListActions(
        onImportPoints = call("import_points"),
        onImportDxf = call("import_cad"),
        onImportSurface = call("import_surface"),
        onImportRaster = call("import_raster"),
        onImportGpkg = call("import_geopackage"),
        onImportShp = call("import_shapefile"),
        onImportKml = call("import_kml"),
        onImportAlignment = call("import_alignment"),
        onExport = call("export_points"),
        onExportGeoPackage = call("export_geopackage"),
        onExportDxf = call("export_cad"),
        onExportShp = call("export_shapefile"),
        onExportKml = call("export_kml"),
        onExportPhotos = call("export_photos"),
        onLineFromPoints = call("line_from_points"),
        onThreePointAngle = call("three_point_angle"),
        onCogo = call("cogo"),
        onBaseShift = call("base_shift"),
        onLocalization = call("localization"),
        onSurfaceStake = call("surface_stake"), // S062/RF-3
        onStakeoutReport = call("stakeout_report"),
        onOpenTrash = call("point_trash"),
    )

    private fun show() = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) {
            ObjectListScreen(ThemeState.DEFAULT, {}, ObjectListUiState(loaded = true), actions, onBack = {})
        }
    }

    @Test
    fun `S059 RF-1 la lista de Objetos muestra la barra con Importar, Exportar y Herramientas`() {
        show()

        BAR_GROUPS.forEach { compose.onNodeWithTag(groupButtonTag(it)).assertIsDisplayed() }
    }

    @Test
    fun `S059 RF-7 cada fila de Importar, Exportar y Herramientas llega a la accion de su spec de origen`() {
        show()

        BAR_GROUPS.flatMap { ProjectOptions.of(it) }.forEach { option ->
            compose.chooseOption(option)
            calls.last() shouldBe option.key
        }
        calls.size shouldBe 20 // S062/RF-3: «Replantear superficie» en Herramientas
    }

    @Test
    fun `S059 RF-6 el menu de mas opciones solo lleva el informe de replanteo y la papelera`() {
        show()

        compose.onNodeWithTag(OBJECTS_MENU_TAG).performClick()
        ProjectOptions.of(OptionGroup.MORE).forEach { compose.onNodeWithTag(optionTag(it)).assertIsDisplayed() }
        ProjectOption.entries.filter { it.group != OptionGroup.MORE }.forEach {
            compose.onAllNodesWithTagCount(optionTag(it)) shouldBe 0
        }
    }

    @Test
    fun `S059 RF-7 el informe de replanteo y la papelera llegan a sus acciones`() {
        show()

        ProjectOptions.of(OptionGroup.MORE).forEach { option ->
            compose.chooseOption(option)
            calls.last() shouldBe option.key
        }
    }

    @Test
    fun `S059 RF-10b una opcion sin nada que hacer sigue visible y su accion se ejecuta como en su spec`() {
        show() // las acciones de esta prueba no hacen nada más que apuntar la llamada: la fila sigue y se puede pulsar

        compose.onNodeWithTag(groupButtonTag(OptionGroup.TOOLS)).performClick()
        compose.onNodeWithTag(optionTag(ProjectOption.BASE_SHIFT)).assertIsDisplayed().performClick()

        calls shouldBe listOf("base_shift")
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String): Int =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
}
