package es.topophone.app.ui.points

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.points.PointFixtures.measured
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.PositionUnavailable
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Formulario de punto y sus accesos desde la lista y la ficha (S011/RF-8, RF-9, RF-16 a RF-19). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class PointFormScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = PointFormActions(
        onName = { calls += "name:$it" },
        onDescription = { calls += "description:$it" },
        onForm = { calls += "form:$it" },
        onFirst = { calls += "first:$it" },
        onSecond = { calls += "second:$it" },
        onHeight = { calls += "height:$it" },
        onHeightKind = { calls += "kind:$it" },
        onSave = { calls += "save" },
    )

    private fun show(state: PointFormUiState) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { PointFormScreen(ThemeState.DEFAULT, {}, state, actions, onBack = {}) }
        }
    }

    private val base = PointFormUiState(loaded = true, name = "13")

    @Test
    fun `S011 RF-16 y RF-17 un punto nuevo pide nombre, descripcion, coordenadas del SC del proyecto y altura opcional`() {
        show(base)

        compose.onNodeWithText("Nuevo punto").assertIsDisplayed()
        compose.onNodeWithTag(FORM_FIRST_TAG).performTextReplacement("440297,988")
        compose.onNodeWithTag(FORM_SECOND_TAG).performTextReplacement("4474244,001")
        compose.onNodeWithTag(FORM_HEIGHT_TAG).performScrollTo().performTextReplacement("648,3")
        compose.onNodeWithText("Este (E)").assertIsDisplayed()
        compose.onNodeWithText("Altitud (H, EGM08-REDNAP) (opcional)").assertIsDisplayed()
        compose.onNodeWithTag(FORM_KIND_ELLIPSOIDAL_TAG).assertDoesNotExist()

        // El estado de esta prueba no cambia: basta con que lleguen los valores nuevos.
        calls shouldContainAll listOf("first:440297,988", "second:4474244,001", "height:648,3")
    }

    @Test
    fun `S011 RF-17 en geograficas se escriben latitud y longitud y la altura puede ser elipsoidal`() {
        show(base.copy(form = EntryForm.GEOGRAPHIC, allowEllipsoidalKind = true))

        compose.onNodeWithText("Latitud").assertIsDisplayed()
        compose.onNodeWithTag(FORM_PROJECT_CRS_TAG).performClick()
        compose.onNodeWithTag(FORM_KIND_ELLIPSOIDAL_TAG).performScrollTo().performClick()

        calls shouldBe listOf("form:PROJECT_CRS", "kind:ELLIPSOIDAL")
    }

    @Test
    fun `S011 RF-17 en un proyecto en geograficas no se elige la forma`() {
        show(base.copy(form = EntryForm.GEOGRAPHIC, geographicCrs = true, allowEllipsoidalKind = true))

        compose.onNodeWithTag(FORM_PROJECT_CRS_TAG).assertDoesNotExist()
        compose.onNodeWithTag(FORM_GEOGRAPHIC_TAG).assertDoesNotExist()
    }

    @Test
    fun `S011 RF-19 fuera del ambito se dice por que y no se puede guardar`() {
        show(base.copy(first = "440000", second = "5600000", rejection = PositionUnavailable.OUTSIDE_CRS_AREA))

        compose.onNodeWithText("Fuera del ámbito del sistema de coordenadas del proyecto.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(FORM_SAVE_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `S011 RF-19 con altitud fuera del geoide se dice que se deje vacia o elipsoidal`() {
        show(base.copy(form = EntryForm.GEOGRAPHIC, allowEllipsoidalKind = true, rejection = PositionUnavailable.OUTSIDE_GEOID))

        compose.onNodeWithText(
            "Con altitud, el punto tiene que estar dentro del modelo de geoide. Deja la altura vacía o escríbela elipsoidal.",
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S011 RF-9 un nombre repetido y un valor no valido se marcan`() {
        show(base.copy(name = "12", nameError = NameProblem.TAKEN, first = "44o", firstInvalid = true))

        compose.onNodeWithText("Ya hay un punto con ese nombre.").assertIsDisplayed()
        compose.onNodeWithText("Valor no válido.").assertIsDisplayed()
    }

    @Test
    fun `S011 RF-8 al editar se ve el titulo de edicion y se guarda`() {
        show(base.copy(editing = true, name = "12", first = "440297,988", second = "4474244,001", canSave = true))

        compose.onNodeWithText("Editar punto").assertIsDisplayed()
        compose.onNodeWithTag(FORM_SAVE_TAG).performScrollTo().performClick()

        calls shouldBe listOf("save")
    }

    @Test
    fun `S011 RF-16 la lista de objetos ofrece Anadir punto`() {
        var added = false
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ObjectListScreen(ThemeState.DEFAULT, {
                }, ObjectListUiState(loaded = true), ObjectListActions(onAdd = { added = true }), onBack = {})
            }
        }

        compose.onNodeWithText("Añadir punto").performClick()

        added shouldBe true
    }

    @Test
    fun `S011 RF-8 la ficha ofrece Editar`() {
        var edited = false
        val project = Project(
            id = "p1", name = "P", author = "", description = "", crs = ProjectCrs.ETRS89_UTM30, linearUnit = LinearUnit.METRE,
            angularUnit = AngularUnit.DMS, correctionsDatum = CorrectionsDatum.ETRS89, folderId = null, createdAtMs = 0,
            modifiedAtMs = 0, openedAtMs = null, deletedAtMs = null,
        )
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                PointDetailsScreen(
                    ThemeState.DEFAULT,
                    {},
                    PointDetailsUiState(project, measured("12")),
                    PointDetailsActions(onEdit = {
                        edited =
                            true
                    }),
                    onBack = {},
                )
            }
        }

        compose.onNodeWithTag(POINT_EDIT_TAG).performClick()

        edited shouldBe true
    }

    @Test
    fun `S011 RF-17 la altura escrita en la ficha de edicion se ve con su tipo`() {
        show(base.copy(form = EntryForm.GEOGRAPHIC, allowEllipsoidalKind = true, heightKind = HeightKind.ELLIPSOIDAL, height = "699,4"))

        compose.onNodeWithTag(FORM_HEIGHT_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Altura elipsoidal (h) (opcional)").assertIsDisplayed()
    }

    @Test
    fun `S011 RF-17 cada campo de geograficas da un ejemplo de su eje`() {
        show(base.copy(form = EntryForm.GEOGRAPHIC, allowEllipsoidalKind = true))

        compose.onNodeWithText("Grados, minutos y segundos: 40 25 07,24 N").assertIsDisplayed()
        compose.onNodeWithText("Grados, minutos y segundos: 3 42 13,64 W").assertIsDisplayed()
    }
}
