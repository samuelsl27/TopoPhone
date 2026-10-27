package es.topophone.app.ui.projects

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.geodesy.Utm
import es.topophone.core.model.format.AngleFormat
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CentralPoint
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.nulls.shouldNotBeNull
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** El punto central en los datos del proyecto, con sus coordenadas en el SC y las unidades del proyecto (S006/RF-36). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ProjectInfoCentralPointTest {

    @get:Rule
    val compose = createComposeRule()

    private val locale = Locale("es", "ES")
    private val madrid = CentralPoint(40.4168123456, -3.7038123456)

    private val project = Project(
        id = "p1",
        name = "Calle Mayor 12",
        author = "",
        description = "",
        crs = ProjectCrs.ETRS89_UTM30,
        linearUnit = LinearUnit.US_SURVEY_FOOT,
        angularUnit = AngularUnit.GON,
        correctionsDatum = CorrectionsDatum.ETRS89,
        folderId = null,
        createdAtMs = 1_000,
        modifiedAtMs = 1_000,
        openedAtMs = null,
        deletedAtMs = null,
    )

    private fun show(project: Project) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectInfoScreen(ThemeState.DEFAULT, {}, ProjectInfoUiState(project, pointCount = 0), onBack = {})
            }
        }
    }

    @Test
    fun `S006 RF-36 con punto central los datos del proyecto dan su Este y Norte en las unidades del proyecto`() {
        show(project.copy(centralPoint = madrid))
        val utm = Utm.forward(madrid.latitudeDeg, madrid.longitudeDeg, 30)
        val unit = text(R.string.unit_symbol_us_survey_foot)

        compose.onNodeWithTag(CENTRAL_POINT_EASTING_TAG).performScrollTo()
            .assert(hasText("${LengthFormat.inUnit(utm.easting, LinearUnit.US_SURVEY_FOOT, locale)} $unit", substring = true))
        compose.onNodeWithTag(CENTRAL_POINT_NORTHING_TAG)
            .assert(hasText("${LengthFormat.inUnit(utm.northing, LinearUnit.US_SURVEY_FOOT, locale)} $unit", substring = true))
    }

    @Test
    fun `S006 RF-36 sin punto central los datos del proyecto dicen que no esta fijado`() {
        show(project)

        compose.onNodeWithTag(CENTRAL_POINT_EMPTY_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(CENTRAL_POINT_EMPTY_TAG).assert(hasText(text(R.string.central_point_unset)))
        compose.onNodeWithTag(CENTRAL_POINT_EASTING_TAG).assertDoesNotExist()
    }

    @Test
    fun `S006 RF-36 con un SC geografico el punto central se da en latitud y longitud en la unidad angular`() {
        val geographic = ProjectCrs.of(GeodeticDatum.ETRS89, null).shouldNotBeNull()
        show(project.copy(crs = geographic, centralPoint = madrid))

        compose.onNodeWithTag(CENTRAL_POINT_LATITUDE_TAG).performScrollTo()
            .assert(hasText(AngleFormat.latitude(madrid.latitudeDeg, AngularUnit.GON, locale), substring = true))
        compose.onNodeWithTag(CENTRAL_POINT_LONGITUDE_TAG)
            .assert(hasText(AngleFormat.longitude(madrid.longitudeDeg, AngularUnit.GON, locale), substring = true))
        compose.onNodeWithTag(CENTRAL_POINT_EASTING_TAG).assertDoesNotExist()
    }

    @Test
    fun `S006 RF-36 si el SC cambia y el punto queda fuera de su ambito lo dice en lugar de dar coordenadas`() {
        show(project.copy(centralPoint = CentralPoint(28.1, -15.4))) // Gran Canaria con un SC de la Península

        compose.onNodeWithTag(CENTRAL_POINT_UNAVAILABLE_TAG).performScrollTo()
            .assert(hasText(text(R.string.project_outside_crs)))
        compose.onNodeWithTag(CENTRAL_POINT_EASTING_TAG).assertDoesNotExist()
    }
}
