package es.topophone.app.ui.lines

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.line.LineVertex
import es.topophone.core.model.line.ProjectLine
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** «Replantear» desde la ficha de la línea y desde la hoja que se abre al tocarla en el mapa (S027/RF-1). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class LineActionsTest {

    @get:Rule
    val compose = createComposeRule()

    private val line = ProjectLine(
        id = "l1",
        projectId = "p1",
        name = "BORD_1",
        code = null,
        geometry = CodeGeometry.LINE,
        closed = false,
        hidden = false,
        active = false,
        vertices = listOf(LineVertex("a"), LineVertex("b")),
        createdAtMs = 0,
    )

    private val calls = mutableListOf<String>()

    private fun showDetails() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                LineDetailsScreen(
                    ThemeState.DEFAULT,
                    {},
                    LineDetailsUi(loaded = true, line = line, vertices = listOf(VertexRow(0, "a", "1", 0.0), VertexRow(1, "b", "2", 10.0))),
                    LineDetailsActions(onStakeout = { calls += "stakeout" }),
                    onBack = {},
                )
            }
        }
    }

    private fun showSheet(withAxis: Boolean = false) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                LineMapSheet(
                    name = "BORD_1",
                    onOpen = { calls += "open" },
                    onStakeout = { calls += "stakeout" },
                    onInformation = { calls += "info" },
                    onCreateAxis = if (withAxis) ({ calls += "axis" }) else null,
                    onDismiss = { calls += "dismiss" },
                )
            }
        }
    }

    @Test
    fun `S027 RF-1 la ficha de la linea tiene el boton Replantear que abre el replanteo`() {
        showDetails()

        compose.onNodeWithTag(LINE_STAKEOUT_TAG).performScrollTo().assertHeightIsAtLeast(56.dp).performClick()

        calls shouldBe listOf("stakeout")
    }

    @Test
    fun `S027 RF-1 la hoja del mapa dice la linea y ofrece abrir la ficha, replantear e informacion`() {
        showSheet()

        compose.onNodeWithText("BORD_1").assertIsDisplayed()
        compose.onNodeWithTag(LINE_SHEET_OPEN_TAG).assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithTag(LINE_SHEET_STAKEOUT_TAG).assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithTag(LINE_SHEET_INFO_TAG).assertHeightIsAtLeast(56.dp).performClick()

        calls shouldBe listOf("open", "stakeout", "info")
    }

    @Test
    fun `S027 RF-17 Crear eje solo se ofrece cuando se da`() {
        showSheet(withAxis = false)

        compose.onAllNodesWithTagCount(LINE_SHEET_AXIS_TAG) shouldBe 0
    }

    @Test
    fun `S027 RF-17 con Crear eje la hoja lo ofrece`() {
        showSheet(withAxis = true)

        compose.onNodeWithTag(LINE_SHEET_AXIS_TAG).assertHeightIsAtLeast(56.dp).performClick()

        calls shouldBe listOf("axis")
    }

    private fun showDetails(line: ProjectLine, actions: LineDetailsActions = LineDetailsActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                LineDetailsScreen(
                    ThemeState.DEFAULT,
                    {},
                    LineDetailsUi(
                        loaded = true,
                        line = line,
                        vertices = line.vertices.mapIndexed { i, v ->
                            VertexRow(
                                i,
                                v.pointId,
                                "P$i",
                                i * 10.0,
                            )
                        },
                    ),
                    actions,
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun `S027 RF-17 la ficha de una linea abierta con dos vertices ofrece Crear eje`() {
        showDetails(line, LineDetailsActions(onCreateAxis = { calls += "axis" }))

        compose.onNodeWithTag(LINE_CREATE_AXIS_TAG).performScrollTo().assertHeightIsAtLeast(56.dp).performClick()

        calls shouldBe listOf("axis")
    }

    @Test
    fun `S027 RF-17 un poligono no ofrece Crear eje`() {
        showDetails(line.copy(closed = true, vertices = listOf(LineVertex("a"), LineVertex("b"), LineVertex("c"))))

        compose.onAllNodesWithTagCount(LINE_CREATE_AXIS_TAG) shouldBe 0
    }

    @Test
    fun `S027 RF-17 una linea de un solo vertice no ofrece Crear eje`() {
        showDetails(line.copy(vertices = listOf(LineVertex("a"))))

        compose.onAllNodesWithTagCount(LINE_CREATE_AXIS_TAG) shouldBe 0
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String): Int =
    onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
