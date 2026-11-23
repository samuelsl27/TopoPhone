package es.topophone.app.ui.points

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.localization.ActiveLocalizationInfo
import es.topophone.core.model.localization.LocalizationParams
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.TypedCoordinates
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.localization.PointLocalization
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La ficha de un punto GNSS localizado: las dos coordenadas y la marca «Localizado»; un punto editado no se transforma (S039/RF-18, RF-20). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class PointDetailsLocalizationTest {

    @get:Rule
    val compose = createComposeRule()

    private val spanish = Locale.forLanguageTag("es-ES")
    private val project = Project(
        "p1", "Obra", "", "", ProjectCrs.ETRS89_UTM30, LinearUnit.METRE, AngularUnit.DMS, CorrectionsDatum.ETRS89,
        null, 1_000, 1_000, null, null,
    )
    private val active = ActiveLocalizationInfo(
        "loc-1",
        LocalizationParams(
            centreE = 0.0,
            centreN = 0.0,
            knownCentreE = 1_000.0,
            knownCentreN = -500.0,
            hasHorizontal = true,
        ),
    )

    private fun SemanticsNodeInteraction.shows(text: String): SemanticsNodeInteraction =
        assert(hasText(text, substring = true) or hasAnyDescendant(hasText(text, substring = true)))

    private fun metres(value: Double) = "${LengthFormat.inUnit(value, LinearUnit.METRE, spanish)} m"

    private fun show(point: Point, localization: ActiveLocalizationInfo?) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                PointDetailsScreen(
                    ThemeState.DEFAULT,
                    {},
                    PointDetailsUiState(project, point, localization = localization),
                    PointDetailsActions(),
                    {},
                )
            }
        }
    }

    private fun row(tag: String) = compose.onNodeWithTag(tag).performScrollTo()

    @Test
    fun `S039 RF-18 un punto localizado muestra la marca y las dos coordenadas`() {
        val sc = PointFixtures.measured("12")
        val localized = PointLocalization.localize(sc, active)

        show(localized, active)

        compose.onNodeWithTag(POINT_LOCALIZED_TAG, useUnmergedTree = true).performScrollTo().shows("Localizado")
        val card = compose.onNodeWithTag(POINT_COORDINATES_TAG).performScrollTo()
        card.shows(metres(sc.coordinates.easting!! + 1_000.0))
        compose.onNodeWithTag(POINT_UNLOCALIZED_COORDINATES_TAG).performScrollTo().shows(metres(sc.coordinates.easting!!))
    }

    @Test
    fun `S039 RF-18 un punto sin localizacion no muestra la marca ni la segunda tarjeta`() {
        show(PointFixtures.measured("12"), null)

        compose.onNodeWithTag(POINT_LOCALIZED_TAG, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(POINT_UNLOCALIZED_COORDINATES_TAG).assertDoesNotExist()
        compose.onNodeWithTag(POINT_NOT_LOCALIZED_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `S039 RF-20 un punto editado con una localizacion activa dice que no se transforma`() {
        val edited = PointFixtures.measured("12").copy(
            edited = true,
            typed = TypedCoordinates(EntryForm.PROJECT_CRS, 1.0, 2.0, 3.0, HeightKind.PROJECT),
        )

        show(edited, active)

        compose.onNodeWithTag(POINT_NOT_LOCALIZED_TAG, useUnmergedTree = true).performScrollTo().shows("la localización no las transforma")
        compose.onNodeWithTag(POINT_UNLOCALIZED_COORDINATES_TAG).assertDoesNotExist()
        compose.onNodeWithTag(POINT_LOCALIZED_TAG, useUnmergedTree = true).assertDoesNotExist()
    }
}
