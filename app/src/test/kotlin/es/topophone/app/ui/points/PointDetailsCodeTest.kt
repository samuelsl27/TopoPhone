package es.topophone.app.ui.points

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.points.PointFixtures.manual
import es.topophone.app.ui.points.PointFixtures.measured
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.PointAttribute
import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** El codigo y los atributos en la ficha del punto (S024/RF-12). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class PointDetailsCodeTest {

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

    private val code = PointCode(
        "ARQ",
        "Arqueta",
        0x1E88E5,
        CodeSymbol.SQUARE,
        listOf(
            PointAttribute("Material", AttributeType.LIST, "PVC"),
            PointAttribute("Diametro", AttributeType.NUMBER, "0.25"),
            PointAttribute("Nota", AttributeType.TEXT, ""),
        ),
    )

    private fun show(state: PointDetailsUiState) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { PointDetailsScreen(ThemeState.DEFAULT, {}, state, PointDetailsActions(), onBack = {}) }
        }
    }

    @Test
    fun `S024 RF-12 la ficha da el codigo, su color y simbolo y los valores de los atributos con la coma de la interfaz`() {
        show(PointDetailsUiState(project, measured("12").copy(code = code)))

        compose.onNodeWithTag(POINT_CODE_TAG).performScrollTo()
            .assert(hasText("ARQ · Arqueta", substring = true))
            .assert(hasText("Color #1E88E5 · símbolo Cuadrado", substring = true))
            .assert(hasText("Material: PVC", substring = true))
            .assert(hasText("Diametro: 0,25", substring = true))
            .assert(hasText("Nota: ", substring = true))
    }

    @Test
    fun `S024 RF-12 un punto sin codigo no tiene la tarjeta de codigo`() {
        show(PointDetailsUiState(project, manual("E1")))

        compose.onNodeWithTag(POINT_CODE_TAG).assertDoesNotExist()
    }
}
