package es.topophone.app.ui.points

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.FakeMapController
import es.topophone.app.ui.map.fakeMap
import es.topophone.app.ui.points.PointFixtures.manual
import es.topophone.app.ui.points.PointFixtures.measured
import es.topophone.app.ui.projects.CONFIRM_TAG
import es.topophone.app.ui.projects.ProjectViewScreen
import es.topophone.app.ui.projects.ProjectViewUiState
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.point.PointChange
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointHistoryEntry
import es.topophone.core.model.point.PointSnapshot
import es.topophone.core.model.point.PrecisionFailure
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

/** Ficha del punto y toque en el mapa (S011/RF-5 a RF-7, RF-12, RF-13). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class PointDetailsScreenTest {

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

    private val calls = mutableListOf<String>()

    /** El texto está en el nodo o en alguno de sus descendientes (cada fila de coordenadas es un nodo propio). */
    private fun SemanticsNodeInteraction.shows(text: String): SemanticsNodeInteraction =
        assert(hasText(text, substring = true) or hasAnyDescendant(hasText(text, substring = true)))

    private fun show(state: PointDetailsUiState) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                PointDetailsScreen(
                    ThemeState.DEFAULT,
                    {},
                    state,
                    PointDetailsActions(
                        onRevert = { calls += "revert" },
                        onCorrectPole = { calls += "pole:$it" },
                        onDismissNotice = { calls += "dismiss" },
                        onDuplicate = { calls += "duplicate" },
                        onDelete = { calls += "delete" },
                    ),
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun `S011 RF-6 la ficha da las coordenadas en el SC del proyecto y en geograficas, en sus unidades`() {
        show(PointDetailsUiState(project, measured("12")))

        compose.onNodeWithTag(POINT_COORDINATES_TAG)
            .shows("440.297,988 m")
            .shows("4.474.244,001 m")
            .shows("648,311 m")
            .shows("40° 25′ 00,048000″ N")
            .shows("003° 42′ 13,356000″ W")
            .shows("699,423 m")
    }

    @Test
    fun `S011 RF-6 un punto sin altura lo dice`() {
        show(PointDetailsUiState(project, manual("E1")))

        compose.onNodeWithTag(POINT_COORDINATES_TAG).shows("Sin altura")
        compose.onNodeWithTag(POINT_MEASUREMENT_TAG).assertDoesNotExist()
    }

    @Test
    fun `S011 RF-7 un punto medido da su jalon, su antena, su calidad, su fecha y lo que incumplio`() {
        show(PointDetailsUiState(project, measured("12", failures = setOf(PrecisionFailure.NOT_FIX))))

        compose.onNodeWithTag(POINT_MEASUREMENT_TAG).performScrollTo()
            .shows("1,800 m")
            .shows("701,357 m")
            .shows("Fija")
            .shows("24")
            .shows("0,6")
            .shows("Instantáneo")
            .shows("No es FIX")
            .shows("1970")
    }

    @Test
    fun `S011 RF-7 las frases NMEA guardadas se ven en un dialogo`() {
        show(PointDetailsUiState(project, measured("12")))

        compose.onNodeWithTag(POINT_NMEA_TAG).performScrollTo().performClick()

        compose.onNodeWithTag(NMEA_DIALOG_TAG).shows("\$GNGGA,100000.00*00")
            .shows("\$GNGST,100000.00*00")
    }

    @Test
    fun `S011 RF-12 y RF-13 un punto editado muestra tambien las coordenadas de la medida y puede volver a ellas`() {
        val point = measured("12")
        val edited = point.copy(coordinates = PointFixtures.sol.copy(easting = 440_300.0), edited = true)
        show(PointDetailsUiState(project, edited))

        compose.onNodeWithTag(POINT_COORDINATES_TAG).shows("440.300,000 m")
        compose.onNodeWithTag(POINT_MEASURED_COORDINATES_TAG).performScrollTo().shows("440.297,988 m")
        compose.onNodeWithTag(POINT_REVERT_TAG).performScrollTo().performClick()

        calls shouldBe listOf("revert")
    }

    @Test
    fun `S011 RF-12 sin editar no hay coordenadas de la medida aparte ni vuelta atras`() {
        show(PointDetailsUiState(project, measured("12")))

        compose.onNodeWithTag(POINT_MEASURED_COORDINATES_TAG).assertDoesNotExist()
        compose.onNodeWithTag(POINT_REVERT_TAG).assertDoesNotExist()
    }

    @Test
    fun `S011 RF-6 en un proyecto en geograficas no hay Este ni Norte`() {
        val geographic = PointCoordinates(PointFixtures.sol.ground, null, null, 648.311)
        show(PointDetailsUiState(project.copy(crs = ProjectCrs.ETRS89_GEOGRAPHIC), measured("12").copy(coordinates = geographic)))

        compose.onNodeWithText("Este (E)").assertDoesNotExist()
        compose.onNodeWithTag(POINT_COORDINATES_TAG).shows("40° 25′ 00,048000″ N")
    }

    @Test
    fun `S011 RF-5 tocar un punto en el mapa abre su ficha`() {
        val map = FakeMapController()
        var opened: Pair<String, String>? = null
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectViewScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectViewUiState(project),
                    onBack = {},
                    map = fakeMap(map),
                    onOpenPoint = { projectId, pointId -> opened = projectId to pointId },
                )
            }
        }
        compose.waitForIdle()

        map.clickPoint("id-12")
        compose.waitForIdle()

        opened shouldBe ("p1" to "id-12")
    }

    @Test
    fun `S011 RF-5 la ficha lleva el nombre del punto`() {
        show(PointDetailsUiState(project, measured("12")))

        compose.onNodeWithText("12").assertIsDisplayed()
    }

    @Test
    fun `S011 RF-14 desde la ficha de un punto medido se corrige su jalon`() {
        show(PointDetailsUiState(project, measured("12")))

        compose.onNodeWithTag(POINT_POLE_TAG).performClick()
        compose.onNodeWithTag(POLE_FIELD_TAG).performTextReplacement("2")
        compose.onNodeWithTag(POLE_APPLY_TAG).performClick()

        calls shouldBe listOf("pole:2.0")
    }

    @Test
    fun `S011 RF-15 un punto manual no tiene jalon que corregir`() {
        show(PointDetailsUiState(project, manual("E1")))

        compose.onNodeWithTag(POINT_POLE_TAG).assertDoesNotExist()
    }

    @Test
    fun `S011 RF-14 si se mantienen las coordenadas editadas la ficha lo dice`() {
        show(PointDetailsUiState(project, measured("12"), keptEdited = true))

        compose.onNodeWithText("Las coordenadas editadas se mantienen; se ha recalculado la medida.").assertIsDisplayed()
        compose.onNodeWithTag(POINT_NOTICE_DISMISS_TAG).performClick()

        calls shouldBe listOf("dismiss")
    }

    @Test
    fun `S011 RF-21 se duplica desde la ficha y la copia dice de que punto es`() {
        show(PointDetailsUiState(project, manual("13").copy(copyOf = "12")))

        compose.onNodeWithText("Copia de 12").assertIsDisplayed()
        compose.onNodeWithTag(POINT_DUPLICATE_TAG).performClick()

        calls shouldBe listOf("duplicate")
    }

    @Test
    fun `S011 RF-22 se borra desde la ficha tras confirmar`() {
        show(PointDetailsUiState(project, measured("12")))

        compose.onNodeWithTag(POINT_DELETE_TAG).performClick()
        compose.onNodeWithText("¿Mover 12 a la papelera? Se puede recuperar durante 30 días.").assertIsDisplayed()
        compose.onNodeWithTag(CONFIRM_TAG).performClick()

        calls shouldBe listOf("delete")
    }

    @Test
    fun `S011 RF-11 el historial muestra los cambios del mas reciente al mas antiguo`() {
        val v1 = measured("P1")
        val v2 = v1.copy(name = "P1a")
        val v3 = v2.copy(measurement = v2.measurement!!.copy(poleHeightM = 2.0))
        val history = listOf(
            PointHistoryEntry("h2", "id-P1", 3_000, PointChange.POLE, PointSnapshot.of(v2)),
            PointHistoryEntry("h1", "id-P1", 2_000, PointChange.EDIT, PointSnapshot.of(v1)),
        )
        show(PointDetailsUiState(project, v3, history = history))

        compose.onNodeWithTag(POINT_HISTORY_TAG).performClick()

        compose.onNodeWithTag(HISTORY_DIALOG_TAG).shows("Jalón: 1,800 m → 2,000 m").shows("Nombre: P1 → P1a")
        val texts = compose.onNodeWithTag(HISTORY_DIALOG_TAG, useUnmergedTree = true).fetchSemanticsNode().let { node ->
            fun collect(n: androidx.compose.ui.semantics.SemanticsNode): List<String> =
                n.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.map { it.text } +
                    n.children.flatMap { collect(it) }
            collect(node)
        }
        (texts.indexOfFirst { it.startsWith("Jalón") } < texts.indexOfFirst { it.startsWith("Nombre") }) shouldBe true
    }

    @Test
    fun `S011 RF-11 sin cambios el historial lo dice`() {
        show(PointDetailsUiState(project, measured("12")))

        compose.onNodeWithTag(POINT_HISTORY_TAG).performClick()

        compose.onNodeWithText("Sin cambios.").assertIsDisplayed()
    }
}
