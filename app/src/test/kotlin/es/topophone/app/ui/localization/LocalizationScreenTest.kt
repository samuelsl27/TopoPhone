package es.topophone.app.ui.localization

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
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
import es.topophone.core.survey.localization.LocalizationFailure
import es.topophone.core.survey.localization.LocalizationOutcome
import es.topophone.core.survey.localization.LocalizationSolver
import es.topophone.core.survey.localization.assess
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

    /** Cuatro pares con la misma traslación y un residuo de 0,20 m en el Norte del segundo, y el estado calculado como lo hace la pantalla. */
    private fun solvedState(scaled: Double = 1.0, error: Double = 0.20): LocalizationUiState {
        val base = listOf(440_000.0 to 4_474_000.0, 440_800.0 to 4_474_600.0, 439_300.0 to 4_474_100.0, 440_400.0 to 4_473_200.0)
        val pairs = base.mapIndexed { i, (e, n) ->
            val knownE = 440_000.0 + (e - 440_000.0) * scaled + 100.0
            val knownN = 4_474_000.0 + (n - 4_474_000.0) * scaled - 50.0 + (if (i == 1) error else 0.0)
            LocalizationPair("p$i", "pt$i", "Clavo $i", e, n, 650.0 + i, knownE, knownN, 650.25 + i)
        }
        val outcome = LocalizationSolver.solve(pairs, false)
        val solved = outcome as LocalizationOutcome.Solved
        return LocalizationUiState(
            loaded = true,
            project = project,
            pairs = pairs,
            residualLimitM = 0.03,
            scaleLimit = 0.001,
            outcome = outcome,
            assessment = solved.solution.assess(0.03, 0.001),
        )
    }

    @Test
    fun `S039 RF-10 el resultado enseña traslacion, giro, escala, vertical, RMS y el mayor residuo`() {
        show(solvedState())

        compose.onNodeWithTag(LOCALIZATION_TRANSLATION_TAG).performScrollTo().shows("Traslación del centroide")
        compose.onNodeWithTag(LOCALIZATION_ROTATION_TAG).performScrollTo().shows("Giro")
        compose.onNodeWithTag(LOCALIZATION_SCALE_TAG).performScrollTo().shows("Escala").shows("ppm")
        compose.onNodeWithTag(LOCALIZATION_VERTICAL_TAG).performScrollTo().shows("plano inclinado")
        compose.onNodeWithTag(LOCALIZATION_RMS_TAG).performScrollTo().shows("horizontal").shows("vertical")
        compose.onNodeWithTag(LOCALIZATION_WORST_TAG).performScrollTo().shows("Clavo 1")
        compose.onNodeWithTag(localizationResidualTag("p0")).performScrollTo().shows("Clavo 0").shows("Este").shows("Norte")
    }

    @Test
    fun `S039 RF-13 un residuo mayor que el umbral se destaca con texto y los demas no`() {
        show(solvedState())

        compose.onNodeWithTag(localizationResidualHighTag("p1"), useUnmergedTree = true).performScrollTo().shows("Residuo alto")
        compose.onNodeWithTag(LOCALIZATION_SCALE_WARNING_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `S039 RF-13 sin residuos mayores que el umbral no se destaca ninguno`() {
        show(solvedState(error = 0.0))

        for (id in listOf("p0", "p1", "p2", "p3")) {
            compose.onNodeWithTag(localizationResidualHighTag(id), useUnmergedTree = true).assertDoesNotExist()
        }
    }

    @Test
    fun `S039 RF-13 una escala lejos de uno se avisa con texto`() {
        show(solvedState(scaled = 1.002))

        compose.onNodeWithTag(LOCALIZATION_SCALE_WARNING_TAG, useUnmergedTree = true).performScrollTo().shows("se aleja de 1")
    }

    @Test
    fun `S039 RF-12 sin componentes activas o con puntos coincidentes dice el motivo`() {
        show(
            LocalizationUiState(
                loaded = true,
                project = project,
                pairs = listOf(pair("a", "A")),
                outcome = LocalizationOutcome.Failed(LocalizationFailure.NO_PAIRS),
            ),
        )

        compose.onNodeWithTag(LOCALIZATION_RESULT_FAILED_TAG).performScrollTo().shows("Ningún par tiene una componente activa")
    }

    @Test
    fun `S039 RF-12 los puntos coincidentes en planta se explican`() {
        show(
            LocalizationUiState(
                loaded = true,
                project = project,
                pairs = listOf(pair("a", "A")),
                outcome = LocalizationOutcome.Failed(LocalizationFailure.DEGENERATE),
            ),
        )

        compose.onNodeWithTag(LOCALIZATION_RESULT_FAILED_TAG).performScrollTo().shows("coinciden en planta")
    }

    @Test
    fun `S039 RF-13 los umbrales se editan y avisan a la pantalla`() {
        val calls = mutableListOf<Pair<String, String>>()
        show(solvedState(), LocalizationActions(onLimits = { residual, scale -> calls += residual to scale }))

        compose.onNodeWithTag(LOCALIZATION_LIMIT_RESIDUAL_TAG).performScrollTo().performTextReplacement("0,05")

        calls.last().first shouldBe "0,05"
    }

    @Test
    fun `S039 RF-14 aplicar solo esta activo con un borrador que se calcula y pide confirmacion`() {
        val calls = mutableListOf<String>()
        val solved = solvedState()
        show(
            solved.copy(draft = localization(LocalizationStatus.DRAFT, solved.pairs)),
            LocalizationActions(onRequestApply = { calls += "apply" }),
        )

        compose.onNodeWithTag(LOCALIZATION_APPLY_TAG).performScrollTo().assertIsEnabled().performClick()

        calls shouldBe listOf("apply")
    }

    @Test
    fun `S039 RF-14 sin borrador el boton de aplicar esta desactivado`() {
        show(solvedState())

        compose.onNodeWithTag(LOCALIZATION_APPLY_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `S039 RF-22 con el proyecto en terreno lo dice y no deja aplicar`() {
        val solved = solvedState()
        val ground = project.copy(terrain = es.topophone.core.model.project.TerrainPlane(40.4, -3.7, 0.9996))
        show(solved.copy(project = ground, draft = localization(LocalizationStatus.DRAFT, solved.pairs)))

        compose.onNodeWithTag(LOCALIZATION_TERRAIN_NOTE_TAG).performScrollTo().shows("coordenadas de terreno")
        compose.onNodeWithTag(LOCALIZATION_APPLY_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `S039 RF-14 la confirmacion dice cuantos puntos cambian`() {
        val calls = mutableListOf<String>()
        show(
            LocalizationUiState(loaded = true, project = project, confirmApply = ApplyConfirmUi(ApplyStage.CONFIRM, pointsAffected = 7)),
            LocalizationActions(onConfirmApply = { calls += "ok" }, onCancelApply = { calls += "cancel" }),
        )

        compose.onNodeWithTag(LOCALIZATION_CONFIRM_APPLY_TEXT_TAG).shows("7 puntos GNSS")
        compose.onNodeWithTag(LOCALIZATION_CONFIRM_APPLY_TAG).performClick()
        compose.onNodeWithTag(LOCALIZATION_CANCEL_APPLY_TAG).performClick()

        calls shouldBe listOf("ok", "cancel")
    }

    @Test
    fun `S039 RF-15 la segunda confirmacion nombra los pares y la escala`() {
        show(
            LocalizationUiState(
                loaded = true,
                project = project,
                confirmApply = ApplyConfirmUi(
                    ApplyStage.CONFIRM_WARNINGS,
                    7,
                    highPairNames = listOf("Clavo 1", "Clavo 4"),
                    scaleOutOfLimit = true,
                ),
            ),
        )

        compose.onNodeWithTag(LOCALIZATION_CONFIRM_APPLY_TEXT_TAG).shows("Clavo 1, Clavo 4").shows("se aleja de 1")
    }

    @Test
    fun `S039 RF-21 el historial ofrece reactivar y quitar la activa pide confirmacion`() {
        val calls = mutableListOf<String>()
        val old = localization(LocalizationStatus.ARCHIVED, listOf(pair("a", "A")), rmsH = 0.01).copy(id = "old")
        val active = localization(LocalizationStatus.ACTIVE, listOf(pair("a", "A")), rmsH = 0.012)
        show(
            LocalizationUiState(loaded = true, project = project, active = active, history = listOf(old), pairs = active.pairs),
            LocalizationActions(onReactivate = { calls += "reactivate:$it" }, onRequestRemove = { calls += "remove" }),
        )

        compose.onNodeWithTag(localizationReactivateTag("old")).performScrollTo().performClick()
        compose.onNodeWithTag(LOCALIZATION_REMOVE_TAG).performScrollTo().performClick()

        calls shouldBe listOf("reactivate:old", "remove")
    }

    @Test
    fun `S039 RF-14 el aviso de la accion dice cuantos puntos cambiaron`() {
        show(LocalizationUiState(loaded = true, project = project, notice = LocalizationNotice.Applied(5)))

        compose.onNodeWithTag(LOCALIZATION_NOTICE_TAG).shows("han cambiado 5 puntos GNSS")
    }

    @Test
    fun `S039 RF-25 con pares el informe se puede guardar o compartir`() {
        val calls = mutableListOf<String>()
        show(
            LocalizationUiState(loaded = true, project = project, pairs = listOf(pair("a", "A"))),
            LocalizationActions(onSaveReport = { calls += "save" }, onShareReport = { calls += "share" }),
        )

        compose.onNodeWithTag(LOCALIZATION_REPORT_SAVE_TAG).performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag(LOCALIZATION_REPORT_SHARE_TAG).performScrollTo().performClick()

        calls shouldBe listOf("save", "share")
    }

    @Test
    fun `S039 RF-25 sin nada que informar los botones del informe estan desactivados`() {
        show(LocalizationUiState(loaded = true, project = project))

        compose.onNodeWithTag(LOCALIZATION_REPORT_SAVE_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(LOCALIZATION_REPORT_SHARE_TAG).performScrollTo().assertIsNotEnabled()
    }
}
