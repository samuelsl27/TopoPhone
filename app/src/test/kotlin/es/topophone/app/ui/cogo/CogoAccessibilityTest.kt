package es.topophone.app.ui.cogo

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.cogo.IntersectionSide
import es.topophone.core.model.cogo.LegKind
import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.cogo.ClosureError
import es.topophone.core.survey.cogo.CogoPoint
import es.topophone.core.survey.cogo.CogoScale
import es.topophone.core.survey.cogo.Intersection
import es.topophone.core.survey.cogo.Inverse
import es.topophone.core.survey.cogo.TraverseStep
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Las pantallas de COGO cumplen RNF-T7 y RNF-T8: todo control tiene texto o etiqueta, ninguno mide menos de 56 dp, la izquierda, la
 * derecha y la distancia activa se dicen con texto, en inglés salen textos en inglés y ninguna cadena de usuario está en el código (S038).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class CogoAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private val a = CogoPoint(440_000.0, 4_474_000.0, 650.0, 40.4144604937, -3.7071991234, 699.5)
    private val b = CogoPoint(440_150.25, 4_474_040.125, 652.5, 40.4148327766, -3.7054320814, 702.0)

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { TopoPhoneTheme(EffectiveTheme.LIGHT) { content() } }
    }

    private fun label(node: SemanticsNode): String {
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
        return (text + description).trim()
    }

    /** Todo lo que se puede pulsar tiene etiqueta (de sí mismo o de sus hijos) y mide al menos 56 dp de alto. */
    private fun assertControls(screen: String) {
        val density = compose.density.density
        val nodes = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        (nodes.size > 0) shouldBe true
        nodes.filter { label(it).isEmpty() }.map { "$screen: sin etiqueta ${it.id}" }.shouldBeEmpty()
        nodes.filter {
            it.size.height / density < 55.5f
        }.map { "$screen: ${label(it)} mide ${it.size.height / density} dp" }.shouldBeEmpty()
    }

    private val inverse = InverseUi(
        loaded = true,
        mode = DistanceMode.GRID,
        linearUnit = LinearUnit.METRE,
        angularUnit = AngularUnit.DMS,
        a = CogoSlotValue.Found(a, "P1"),
        b = CogoSlotValue.Found(b, "P2"),
        result = Inverse.of(a, b, CogoScale.forFirst(a, ProjectCrs.ETRS89_UTM30, DistanceMode.GRID)),
    )

    private val solutions = listOf(
        IntersectionSolutionUi(IntersectionSide.LEFT, 440_036.5, 4_474_048.25, 60.0, 80.0, false, "", null, false, true),
        IntersectionSolutionUi(IntersectionSide.RIGHT, 440_036.5, 4_473_951.0, 60.0, 80.0, true, "x", null, true, false),
    )

    private val intersection = IntersectionUi(
        loaded = true,
        first = CogoSlotValue.Found(a, "P1"),
        second = CogoSlotValue.Found(b, "P2"),
        value1Text = "60",
        value2Text = "80",
        result = Intersection.distanceDistance(a, b, 100.0, 80.0, CogoScale.forFirst(a, ProjectCrs.ETRS89_UTM30, DistanceMode.GRID)),
        solutions = solutions,
        markedCount = 1,
        canSave = false,
    )

    private val leg = TraverseLegUi(
        1,
        LegKind.DEFLECTION_RIGHT,
        "30",
        "100",
        "",
        valueInvalid = false,
        distanceInvalid = false,
        dHeightInvalid = false,
        step = TraverseStep(1, 440_100.0, 4_474_000.0, 1.0, 100.0, null),
        height = null,
    )

    private val traverse = TraverseUi(
        loaded = true,
        start = CogoSlotValue.Found(a, "P1"),
        reference = CogoSlotValue.Found(b, "R"),
        closurePoint = CogoSlotValue.Found(a, "P1"),
        legs = listOf(leg, leg.copy(number = 2, kind = LegKind.ANGLE_LEFT)),
        closure = ClosureError(-3.0, 4.0, 5.0, 100.0, 20.0),
        hasDraft = true,
        markedCount = 1,
    )

    @Test
    fun `S038 RNF-T8 el menu de COGO tiene etiquetas y 56 dp`() {
        show { CogoMenuScreen(ThemeState.DEFAULT, {}, CogoMenuUi(loaded = true), CogoMenuActions(), onBack = {}) }

        assertControls("menu")
    }

    @Test
    fun `S038 RNF-T8 la inversa tiene etiquetas y 56 dp y dice la distancia activa con texto`() {
        show { InverseScreen(ThemeState.DEFAULT, {}, inverse, InverseActions(), onBack = {}) }

        compose.onNodeWithText("Distancia de cuadrícula (activa)").assertExists()
        assertControls("inversa")
    }

    @Test
    fun `S038 RNF-T8 la interseccion tiene etiquetas y 56 dp y dice izquierda y derecha con texto`() {
        show { IntersectionScreen(ThemeState.DEFAULT, {}, intersection, IntersectionActions(), onBack = {}) }

        compose.onNode(hasText("izquierda", substring = true)).assertExists()
        compose.onNode(hasText("derecha", substring = true)).assertExists()
        assertControls("interseccion")
    }

    @Test
    fun `S038 RNF-T8 la poligonal tiene etiquetas y 56 dp`() {
        show { TraverseScreen(ThemeState.DEFAULT, {}, traverse, TraverseActions(), onBack = {}) }

        assertControls("poligonal")
    }

    @Test
    fun `S038 RNF-T8 el selector de puntos tiene etiquetas y 56 dp`() {
        show {
            CogoPickerDialog(
                CogoPickerUi("a", "", listOf(CogoCandidate("p1", "P1", true), CogoCandidate("p2", "P2", false))),
                "Punto A",
                null,
                {},
                {},
                { _, _, _ -> },
                {},
            )
        }

        assertControls("selector")
    }

    @Test
    fun `S038 RNF-T8 la hoja de guardado tiene etiquetas y 56 dp`() {
        show {
            CogoSaveDialog(
                CogoSaveUi(true, listOf(CogoNameRow("leg1", "P4", NameProblem.EXISTS)), CogoSaveStatus.NameProblems),
                mapOf("leg1" to "Punto al final del tramo 1"),
                { _, _ -> },
                {},
                {},
            )
        }

        assertControls("guardado")
    }

    @Test
    @Config(qualifiers = "en")
    fun `S038 RNF-T7 en ingles el menu sale en ingles`() {
        show { CogoMenuScreen(ThemeState.DEFAULT, {}, CogoMenuUi(loaded = true), CogoMenuActions(), onBack = {}) }

        compose.onNodeWithText("Inverse").assertIsDisplayed()
        compose.onNodeWithText("Traverse").assertIsDisplayed()
        compose.onNodeWithText("Intersection").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en")
    fun `S038 RNF-T7 en ingles la inversa sale en ingles`() {
        show { InverseScreen(ThemeState.DEFAULT, {}, inverse, InverseActions(), onBack = {}) }

        compose.onNodeWithText("Grid distance (active)").assertExists()
        compose.onNodeWithText("Azimuth from A to B").assertExists()
    }

    @Test
    @Config(qualifiers = "en")
    fun `S038 RNF-T7 en ingles la interseccion y la poligonal salen en ingles`() {
        show { IntersectionScreen(ThemeState.DEFAULT, {}, intersection, IntersectionActions(), onBack = {}) }
        compose.onNode(hasText("To the left", substring = true)).assertExists()
    }

    @Test
    @Config(qualifiers = "en")
    fun `S038 RNF-T7 en ingles la poligonal sale en ingles`() {
        show { TraverseScreen(ThemeState.DEFAULT, {}, traverse, TraverseActions(), onBack = {}) }

        compose.onNodeWithText("Start point: P1", substring = true).assertExists()
    }

    @Test
    fun `S038 RNF-T7 los textos de COGO estan en es y en con las mismas claves y no hay literales de usuario en el codigo`() {
        fun keys(path: String) =
            Regex("""<string name="(cogo_[a-z_0-9]+)"""").findAll(File(path).readText()).map { it.groupValues[1] }.toSet()
        val es = keys("src/main/res/values/strings.xml")
        val en = keys("src/main/res/values-en/strings.xml")

        (es.size > 50) shouldBe true
        (es - en).shouldBeEmpty()
        (en - es).shouldBeEmpty()

        val literal = Regex("""Text\(\s*"|label = "|contentDescription = "|text = "|title = """")
        val offenders = File("src/main/kotlin/es/topophone/app/ui/cogo").walkTopDown().filter { it.extension == "kt" }
            .filter { file ->
                file.readLines().any { line ->
                    literal.containsMatchIn(line) &&
                        !line.trimStart().startsWith("//") &&
                        !line.trimStart().startsWith("*")
                }
            }
            .map { it.name }.toList()
        offenders.shouldBeEmpty()
    }
}
