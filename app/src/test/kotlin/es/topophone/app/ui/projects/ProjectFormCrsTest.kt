package es.topophone.app.ui.projects

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.project.ProjectDraft
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** SC del proyecto en el formulario: datum, proyección y altura (S008/RF-1 a RF-3, RF-17 a RF-19). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ProjectFormCrsTest {

    @get:Rule
    val compose = createComposeRule()

    private val initial = ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")

    /** Formulario con su estado, como el ViewModel: devuelve el borrador actual. */
    private fun show(draft: ProjectDraft = initial, editing: Boolean = false): () -> ProjectDraft {
        var current by mutableStateOf(draft)
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectFormScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectFormUiState(draft = current, loaded = true, editing = editing),
                    ProjectFormActions(onChange = { current = it }),
                )
            }
        }
        return { current }
    }

    private fun click(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()

    private fun shown(tag: String) = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()

    @Test
    fun `S008 RF-1 se elige el datum y despues la proyeccion de ese datum`() {
        val draft = show()

        GeodeticDatum.entries.forEach { shown(geodeticDatumOptionTag(it)) }
        listOf(ProjectCrs.ETRS89_UTM29, ProjectCrs.ETRS89_UTM30, ProjectCrs.ETRS89_UTM31, ProjectCrs.ETRS89_GEOGRAPHIC).forEach {
            shown(crsOptionTag(it))
        }

        click(geodeticDatumOptionTag(GeodeticDatum.REGCAN95))

        draft().crs shouldBe ProjectCrs.REGCAN95_UTM28
        listOf(ProjectCrs.REGCAN95_UTM27, ProjectCrs.REGCAN95_UTM28, ProjectCrs.REGCAN95_GEOGRAPHIC).forEach { shown(crsOptionTag(it)) }
        compose.onNodeWithTag(crsOptionTag(ProjectCrs.ETRS89_UTM30)).assertDoesNotExist()

        click(crsOptionTag(ProjectCrs.REGCAN95_GEOGRAPHIC))
        draft().crs shouldBe ProjectCrs.REGCAN95_GEOGRAPHIC
        compose.onNodeWithText(text(R.string.project_projection_geographic)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S008 RF-2 se elige altitud o altura elipsoidal`() {
        val draft = show()

        shown(heightOptionTag(HeightSystem.ORTHOMETRIC)).assertIsSelected()
        click(heightOptionTag(HeightSystem.ELLIPSOIDAL))

        draft().heightSystem shouldBe HeightSystem.ELLIPSOIDAL
    }

    @Test
    fun `S008 RF-3 en ED50 no se ofrece la altura elipsoidal y se pasa a altitud`() {
        val draft = show(initial.copy(heightSystem = HeightSystem.ELLIPSOIDAL))

        click(geodeticDatumOptionTag(GeodeticDatum.ED50))

        draft().crs shouldBe ProjectCrs.ED50_UTM30
        draft().heightSystem shouldBe HeightSystem.ORTHOMETRIC
        compose.onNodeWithTag(heightOptionTag(HeightSystem.ELLIPSOIDAL)).assertDoesNotExist()
        compose.onNodeWithTag(crsOptionTag(ProjectCrs.ETRS89_GEOGRAPHIC)).assertDoesNotExist() // ED50 solo en UTM
    }

    @Test
    fun `S008 RF-17 el datum de las correcciones sigue al SC elegido hasta que el usuario lo cambia`() {
        val draft = show()

        click(geodeticDatumOptionTag(GeodeticDatum.REGCAN95))
        draft().correctionsDatum shouldBe CorrectionsDatum.REGCAN95

        click(datumOptionTag(CorrectionsDatum.ITRF_WGS84))
        draft().correctionsDatum shouldBe CorrectionsDatum.ITRF_WGS84

        click(geodeticDatumOptionTag(GeodeticDatum.ED50))
        draft().correctionsDatum shouldBe CorrectionsDatum.ETRS89
    }

    @Test
    fun `S008 RF-18 si las correcciones no estan en el datum del SC se avisa`() {
        show(initial.copy(crs = ProjectCrs.REGCAN95_UTM28, correctionsDatum = CorrectionsDatum.ETRS89))

        compose.onNodeWithTag(DATUM_WARNING_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.project_datum_warning_general, text(R.string.datum_etrs89), "REGCAN95")).assertIsDisplayed()
    }

    @Test
    fun `S008 RF-18 con el datum de las correcciones del SC no hay aviso`() {
        show(initial.copy(crs = ProjectCrs.REGCAN95_UTM28, correctionsDatum = CorrectionsDatum.REGCAN95))

        compose.onNodeWithTag(DATUM_WARNING_TAG).assertDoesNotExist()
    }

    @Test
    fun `S008 RF-19 al editar no se puede cambiar el datum, solo la proyeccion del mismo datum`() {
        val draft = show(initial.copy(crs = ProjectCrs.ED50_UTM30), editing = true)

        compose.onNodeWithTag(geodeticDatumOptionTag(GeodeticDatum.ETRS89)).performScrollTo().assertIsNotEnabled()
        click(geodeticDatumOptionTag(GeodeticDatum.ETRS89))
        draft().crs shouldBe ProjectCrs.ED50_UTM30

        click(crsOptionTag(ProjectCrs.ED50_UTM31))
        draft().crs shouldBe ProjectCrs.ED50_UTM31
    }
}
