package es.topophone.app.ui.points

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.points.PointFixtures.measured
import es.topophone.app.ui.points.PointFixtures.staked
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.point.LineStakeoutMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.StakeSource
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

/** La ficha de un punto replanteado: de qué diseño viene y cuánto se desvió (S018/RF-32). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class PointDetailsStakeoutTest {

    @get:Rule
    val compose = createComposeRule()

    private fun project(unit: LinearUnit = LinearUnit.METRE) = Project(
        id = "p1",
        name = "Calle Mayor 12",
        author = "",
        description = "",
        crs = ProjectCrs.ETRS89_UTM30,
        linearUnit = unit,
        angularUnit = AngularUnit.DECIMAL_DEGREES,
        correctionsDatum = CorrectionsDatum.ETRS89,
        folderId = null,
        createdAtMs = 1_000,
        modifiedAtMs = 1_000,
        openedAtMs = null,
        deletedAtMs = null,
    )

    private fun SemanticsNodeInteraction.shows(text: String): SemanticsNodeInteraction =
        assert(hasText(text, substring = true) or hasAnyDescendant(hasText(text, substring = true)))

    private fun show(state: PointDetailsUiState) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { PointDetailsScreen(ThemeState.DEFAULT, {}, state, PointDetailsActions(), onBack = {}) }
        }
    }

    @Test
    fun `S018 RF-32 la ficha del replanteado da el punto de diseno, sus coordenadas y las diferencias`() {
        show(PointDetailsUiState(project(), staked("12_stkd")))

        compose.onNodeWithTag(POINT_STAKEOUT_INFO_TAG).performScrollTo()
            .shows("12")
            .shows("440.290,458 m")
            .shows("4.474.257,382 m")
            .shows("648,190 m")
            .shows("+0,012 m")
            .shows("-0,004 m")
            .shows("0,013 m")
            .shows("-0,021 m")
    }

    @Test
    fun `S018 RF-32 las diferencias y el diseno van en las unidades del proyecto`() {
        show(PointDetailsUiState(project(LinearUnit.INTERNATIONAL_FOOT), staked("12_stkd")))

        compose.onNodeWithTag(POINT_STAKEOUT_INFO_TAG).performScrollTo()
            .shows("1.444.522,")
            .shows("+0,039 ft")
            .shows("-0,013 ft")
            .shows("-0,069 ft")
    }

    @Test
    fun `S018 RF-32 sin cota de diseno la ficha lo dice en lugar de la diferencia de cota`() {
        show(PointDetailsUiState(project(), staked("12_stkd", dHeightM = null)))

        compose.onNodeWithTag(POINT_STAKEOUT_INFO_TAG).performScrollTo().shows("Sin cota")
    }

    private fun checked(
        dHeightM: Double? = -0.021,
        designElevationM: Double? = 648.19,
        within: Boolean? = true,
        toleranceHM: Double? = 0.025,
        toleranceVM: Double? = 0.03,
    ) = staked(
        "12_stkd",
        dHeightM = dHeightM,
        designElevationM = designElevationM,
        toleranceHM = toleranceHM,
        toleranceVM = toleranceVM,
        withinTolerance = within,
    )

    @Test
    fun `S019 RF-15 la ficha dice la cota de diseno usada, en cota y dentro de la tolerancia`() {
        show(PointDetailsUiState(project(), checked()))

        compose.onNodeWithTag("point_stakeout_elevation").performScrollTo().shows("648,190 m")
        compose.onNodeWithTag("point_stakeout_level").performScrollTo().shows("En cota")
        compose.onNodeWithTag("point_stakeout_within").performScrollTo().shows("Dentro de la tolerancia")
    }

    @Test
    fun `S019 RF-15 con la punta por encima de la cota la ficha dice desmonte y fuera de la tolerancia`() {
        show(PointDetailsUiState(project(), checked(dHeightM = 0.12, within = false)))

        compose.onNodeWithTag("point_stakeout_level").performScrollTo().shows("Desmonte 0,120 m")
        compose.onNodeWithTag("point_stakeout_within").performScrollTo().shows("Fuera de la tolerancia")
    }

    @Test
    fun `S019 RF-15 con la punta por debajo de la cota la ficha dice terraplen`() {
        show(PointDetailsUiState(project(), checked(dHeightM = -0.5, within = false)))

        compose.onNodeWithTag("point_stakeout_level").performScrollTo().shows("Terraplén 0,500 m")
    }

    @Test
    fun `S019 RF-15 la cota escrita se ve aparte de la altura del punto de diseno`() {
        show(PointDetailsUiState(project(), checked(designElevationM = 646.9, dHeightM = 1.0, within = false)))

        compose.onNodeWithTag("point_stakeout_elevation").performScrollTo().shows("646,900 m")
        compose.onNodeWithTag("point_stakeout_design_h").performScrollTo().shows("648,190 m")
    }

    @Test
    fun `S019 RF-15 las tolerancias con las que se midio salen en la ficha`() {
        show(PointDetailsUiState(project(), checked()))

        compose.onNodeWithTag("point_stakeout_tolerances").performScrollTo().shows("0,025 m").shows("0,030 m")
    }

    @Test
    fun `S019 RF-15 el desmonte va en la unidad del proyecto`() {
        show(PointDetailsUiState(project(LinearUnit.INTERNATIONAL_FOOT), checked(dHeightM = 0.12, within = false)))

        compose.onNodeWithTag("point_stakeout_level").performScrollTo().shows("Desmonte 0,394 ft")
    }

    @Test
    fun `S019 RF-26 un replanteo de antes dice sin evaluar, sin tolerancias, y el desmonte por su signo`() {
        show(PointDetailsUiState(project(), checked(within = null, toleranceHM = null, toleranceVM = null)))

        compose.onNodeWithTag("point_stakeout_within").performScrollTo().shows("Sin evaluar")
        compose.onNodeWithTag("point_stakeout_tolerances").performScrollTo().shows("Sin evaluar")
        compose.onNodeWithTag("point_stakeout_level").performScrollTo().shows("Terraplén 0,021 m")
    }

    @Test
    fun `S019 RF-15 sin cota de diseno la ficha lo dice en la cota usada y en el nivel`() {
        show(PointDetailsUiState(project(), checked(dHeightM = null, designElevationM = null)))

        compose.onNodeWithTag("point_stakeout_elevation").performScrollTo().shows("Sin cota")
        compose.onNodeWithTag("point_stakeout_level").performScrollTo().shows("Sin cota")
        compose.onNodeWithTag("point_stakeout_within").performScrollTo().shows("Dentro de la tolerancia")
    }

    @Test
    fun `S018 RF-32 un punto medido no tiene tarjeta de replanteo`() {
        show(PointDetailsUiState(project(), measured("12")))

        compose.onNodeWithTag(POINT_STAKEOUT_INFO_TAG).assertDoesNotExist()
    }

    private fun fromDrawing(mode: LineStakeoutMode?, withLine: Boolean = true): Point {
        val base = staked("Eje_1_stkd")
        val ref = base.staked!!.copy(
            designName = "Eje",
            source = StakeSource.CadElement("calle.dxf", "Eje", CadEntityKind.POLYLINE),
            mode = mode,
            chainageM = 45.25.takeIf { withLine },
            lateralOffsetM = (-1.5).takeIf { withLine },
            dLateralM = 0.012.takeIf { withLine },
        )
        return base.copy(staked = ref)
    }

    @Test
    fun `S041 RF-29 la ficha del replanteado de un elemento da el dibujo, la capa, el tipo, el modo, el PK y el desplazamiento`() {
        show(PointDetailsUiState(project(), fromDrawing(LineStakeoutMode.ALONG)))

        compose.onNodeWithTag(POINT_STAKEOUT_INFO_TAG).performScrollTo()
            .shows("calle.dxf")
            .shows("Eje")
            .shows("Polilínea")
            .shows("A lo largo de la línea")
            .shows("45,250 m")
            .shows("-1,500 m")
        compose.onNodeWithTag("point_stakeout_drawing").performScrollTo().shows("calle.dxf")
        compose.onNodeWithTag("point_stakeout_layer").performScrollTo().shows("Eje")
        compose.onNodeWithTag("point_stakeout_element").performScrollTo().shows("Polilínea")
        compose.onNodeWithTag("point_stakeout_mode").performScrollTo().shows("A lo largo de la línea")
        compose.onNodeWithTag("point_stakeout_chainage").performScrollTo().shows("45,250 m")
        compose.onNodeWithTag("point_stakeout_offset").performScrollTo().shows("-1,500 m")
    }

    @Test
    fun `S041 RF-29 la ficha da el error lateral con su signo junto a la desviacion respecto al diseno`() {
        show(PointDetailsUiState(project(), fromDrawing(LineStakeoutMode.TO_LINE)))

        compose.onNodeWithTag("point_stakeout_diff_lateral").performScrollTo().shows("+0,012 m")
        compose.onNodeWithTag("point_stakeout_diff_n").performScrollTo().shows("+0,012 m")
    }

    @Test
    fun `S041 RF-29 el error lateral y el PK van en las unidades del proyecto`() {
        show(PointDetailsUiState(project(LinearUnit.INTERNATIONAL_FOOT), fromDrawing(LineStakeoutMode.ALONG)))

        compose.onNodeWithTag("point_stakeout_chainage").performScrollTo().shows("148,458 ft")
        compose.onNodeWithTag("point_stakeout_diff_lateral").performScrollTo().shows("+0,039 ft")
    }

    @Test
    fun `S041 RF-29 un punto fijo de un dibujo da su origen pero no modo, PK ni desplazamiento`() {
        show(PointDetailsUiState(project(), fromDrawing(mode = null, withLine = false)))

        compose.onNodeWithTag(POINT_STAKEOUT_INFO_TAG).performScrollTo()
        compose.onNodeWithTag("point_stakeout_drawing").performScrollTo().shows("calle.dxf")
        compose.onNodeWithTag("point_stakeout_mode").assertDoesNotExist()
        compose.onNodeWithTag("point_stakeout_chainage").assertDoesNotExist()
        compose.onNodeWithTag("point_stakeout_offset").assertDoesNotExist()
        compose.onNodeWithTag("point_stakeout_diff_lateral").assertDoesNotExist()
    }

    @Test
    fun `S041 RF-29 un replanteo de un punto del proyecto no tiene filas de dibujo ni de linea`() {
        show(PointDetailsUiState(project(), staked("12_stkd")))

        compose.onNodeWithTag(POINT_STAKEOUT_INFO_TAG).performScrollTo()
        compose.onNodeWithTag("point_stakeout_drawing").assertDoesNotExist()
        compose.onNodeWithTag("point_stakeout_layer").assertDoesNotExist()
        compose.onNodeWithTag("point_stakeout_element").assertDoesNotExist()
        compose.onNodeWithTag("point_stakeout_mode").assertDoesNotExist()
        compose.onNodeWithTag("point_stakeout_diff_lateral").assertDoesNotExist()
    }
}
