package es.topophone.app.ui.localization

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.localization.Localization
import es.topophone.core.model.localization.LocalizationLimits
import es.topophone.core.model.localization.LocalizationOrigin
import es.topophone.core.model.localization.LocalizationPair
import es.topophone.core.model.localization.LocalizationParams
import es.topophone.core.model.localization.LocalizationStatus
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.shouldBe
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La pantalla de localización: su estado y la lista de pares (S039/RF-1). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class LocalizationScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val project = Project(
        "p1", "Obra de la Dehesa", "", "", ProjectCrs.ETRS89_UTM30, LinearUnit.METRE, AngularUnit.DMS, CorrectionsDatum.ETRS89,
        null, 1_000, 1_000, null, null,
    )

    private fun pair(id: String, name: String, height: Double? = 650.25) =
        LocalizationPair(id, "pt-$id", name, 440_000.0, 4_474_000.0, 650.0, 1_000.5, 2_000.25, height)

    private fun localization(status: LocalizationStatus, pairs: List<LocalizationPair>, rmsH: Double? = null, rmsV: Double? = null) =
        Localization(
            id = "l1",
            projectId = "p1",
            status = status,
            origin = LocalizationOrigin.PAIRS,
            name = null,
            createdAtMs = 1,
            appliedAtMs = 2,
            fixScale = false,
            residualLimitM = 0.03,
            scaleLimit = 0.001,
            crsText = "SPAIN|ETRS89_UTM30",
            heightSystem = "ORTHOMETRIC",
            params = LocalizationParams(),
            rmsHorizontal = rmsH,
            rmsVertical = rmsV,
            pairs = pairs,
        )

    private fun SemanticsNodeInteraction.shows(text: String): SemanticsNodeInteraction =
        assert(hasText(text, substring = true) or hasAnyDescendant(hasText(text, substring = true)))

    private fun metres(value: Double) = "${LengthFormat.inUnit(value, LinearUnit.METRE, Locale.forLanguageTag("es-ES"))} m"

    private fun show(state: LocalizationUiState, actions: LocalizationActions = LocalizationActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                LocalizationScreen(ThemeState.DEFAULT, {}, state, actions, {})
            }
        }
    }

    @Test
    fun `S039 RF-1 un proyecto sin localizacion lo dice y no tiene pares`() {
        show(LocalizationUiState(loaded = true, project = project))

        compose.onNodeWithTag(LOCALIZATION_STATUS_TAG).assertIsDisplayed().shows("Sin localización")
        compose.onNodeWithTag(LOCALIZATION_PAIRS_TITLE_TAG).shows("Pares (0 de 50)")
        compose.onNodeWithTag(LOCALIZATION_PAIRS_EMPTY_TAG).assertIsDisplayed()
    }

    @Test
    fun `S039 RF-1 una localizacion activa enseña su RMS y los pares con sus coordenadas conocidas`() {
        val pairs = listOf(pair("a", "Clavo 1"), pair("b", "Clavo 2", height = null))
        val active = localization(LocalizationStatus.ACTIVE, pairs, rmsH = 0.012, rmsV = 0.008)
        show(LocalizationUiState(loaded = true, project = project, active = active, pairs = pairs))

        compose.onNodeWithTag(LOCALIZATION_STATUS_TAG).shows("Localización activa").shows("0,012 m").shows("0,008 m")
        compose.onNodeWithTag(LOCALIZATION_PAIRS_TITLE_TAG).shows("Pares (2 de 50)")
        compose.onNodeWithTag(localizationPairTag("a")).performScrollTo().shows("Clavo 1").shows(metres(1_000.5)).shows(metres(650.25))
        compose.onNodeWithTag(localizationPairTag("b")).performScrollTo().shows("Clavo 2").shows("sin cota")
        compose.onNodeWithTag(LOCALIZATION_PAIRS_EMPTY_TAG).assertDoesNotExist()
    }

    @Test
    fun `S039 RF-28 una localizacion de un SC guardado dice su nombre y que no tiene pares`() {
        val saved = localization(LocalizationStatus.ACTIVE, emptyList()).copy(origin = LocalizationOrigin.SAVED_CRS, name = "Plano de obra")
        show(LocalizationUiState(loaded = true, project = project, active = saved))

        compose.onNodeWithTag(LOCALIZATION_STATUS_TAG).shows("Plano de obra").shows("sin pares")
    }

    @Test
    fun `S039 RF-2 el formulario del par enseña los puntos GNSS y los campos de las coordenadas`() {
        val calls = mutableListOf<String>()
        show(
            LocalizationUiState(
                loaded = true,
                project = project,
                gnssCandidates = listOf(GnssCandidate("g1", "Clavo 1")),
                addPair = AddPairUi(gnssId = "g1", error = PairError.INVALID_COORDINATES),
            ),
            LocalizationActions(onSelectGnss = { calls += "gnss:$it" }, onConfirmAddPair = { calls += "confirm" }),
        )

        compose.onNodeWithTag(LOCALIZATION_FORM_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(localizationGnssTag("g1")).performScrollTo().shows("Clavo 1").performClick()
        compose.onNodeWithTag(LOCALIZATION_FORM_EASTING_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(LOCALIZATION_FORM_ERROR_TAG).performScrollTo().shows("Este y un Norte válidos")
        compose.onNodeWithTag(LOCALIZATION_FORM_CONFIRM_TAG).performScrollTo().performClick()

        calls shouldBe listOf("gnss:g1", "confirm")
    }

    @Test
    fun `S039 RF-2 con la fuente de un punto el formulario enseña los puntos manuales`() {
        show(
            LocalizationUiState(
                loaded = true,
                project = project,
                knownCandidates = listOf(KnownCandidate("m1", "Replanteo A", 1.0, 2.0, null)),
                addPair = AddPairUi(source = KnownSource.POINT),
            ),
        )

        compose.onNodeWithTag(localizationKnownTag("m1")).performScrollTo().shows("Replanteo A")
        compose.onNodeWithTag(LOCALIZATION_FORM_EASTING_TAG).assertDoesNotExist()
    }

    @Test
    fun `S039 RF-6 un par sin confirmar lo avisa en texto y ofrece confirmar su uso`() {
        val calls = mutableListOf<String>()
        val unconfirmed = pair("a", "Clavo 1").copy(confirmed = false)
        show(
            LocalizationUiState(loaded = true, project = project, pairs = listOf(unconfirmed)),
            LocalizationActions(onConfirmPair = {
                calls +=
                    it
            }),
        )

        compose.onNodeWithTag(localizationUnconfirmedTag("a")).performScrollTo().shows("no cuenta hasta que confirmes")
        compose.onNodeWithTag(localizationConfirmPairTag("a")).performScrollTo().performClick()

        calls shouldBe listOf("a")
    }

    @Test
    fun `S039 RF-4 con 50 pares el boton de anadir queda desactivado`() {
        val fifty = List(LocalizationLimits.MAX_PAIRS) { pair("p$it", "P$it") }
        show(LocalizationUiState(loaded = true, project = project, pairs = fifty))

        compose.onNodeWithTag(LOCALIZATION_ADD_PAIR_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `S039 RF-5 cada par ofrece sus componentes y quitarlo`() {
        val calls = mutableListOf<String>()
        val withoutHeight = pair("a", "Clavo 1", height = null)
        show(
            LocalizationUiState(loaded = true, project = project, pairs = listOf(withoutHeight)),
            LocalizationActions(
                onComponent = { id, horizontal, enabled -> calls += "$id:$horizontal:$enabled" },
                onRemovePair = { calls += "remove:$it" },
            ),
        )

        compose.onNodeWithTag(localizationPairHorizontalTag("a")).performScrollTo().performClick()
        compose.onNodeWithTag(localizationPairVerticalTag("a")).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(localizationRemovePairTag("a")).performScrollTo().performClick()

        calls shouldBe listOf("a:true:false", "remove:a")
    }

    @Test
    fun `S039 RF-8 el interruptor de la escala fija cambia el ajuste`() {
        val calls = mutableListOf<Boolean>()
        show(LocalizationUiState(loaded = true, project = project), LocalizationActions(onFixScale = { calls += it }))

        compose.onNodeWithTag(LOCALIZATION_FIX_SCALE_TAG).performScrollTo().shows("Fijar escala = 1").performClick()

        calls shouldBe listOf(true)
    }
}
