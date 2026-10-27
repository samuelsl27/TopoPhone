package es.topophone.app.ui.alignment

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.formats.alignment.ProfileColumns
import es.topophone.core.formats.alignment.ProfileCsvError
import es.topophone.core.formats.alignment.ProfileCsvRow
import es.topophone.core.formats.alignment.ProfileRole
import es.topophone.core.model.alignment.AlignmentExtent
import es.topophone.core.model.alignment.AlignmentSource
import es.topophone.core.model.alignment.AlignmentSummary
import es.topophone.core.model.alignment.ProfileIssue
import es.topophone.core.model.point.ImportDecimal
import es.topophone.core.model.point.ImportEncoding
import es.topophone.core.model.point.ImportSeparator
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La pantalla de asociar la rasante de un CSV a un eje: problemas, eje, lectura, columnas, vista previa, errores y rechazo (S049/RF-9, RF-10). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ProfileCsvImportScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun axis(id: String, name: String, start: Double, length: Double, profile: Boolean) = AlignmentSummary(
        id, "p", name, "ejes.xml", 0L, AlignmentSource.LANDXML, start, length, true, profile, false, AlignmentExtent(0.0, 1.0, 0.0, 1.0),
    )

    private val config = ProfileCsvUiState(
        stage = ProfileCsvStage.CONFIG,
        fileName = "rasante.csv",
        alignments = listOf(axis("a", "Eje A", 0.0, 420.0, true), axis("b", "Eje B", 1000.5, 380.0, false)),
        selectedAlignment = "a",
        encoding = ImportEncoding.UTF8,
        separator = ImportSeparator.SEMICOLON,
        decimal = ImportDecimal.COMMA,
        hasHeader = true,
        firstRow = listOf("PK", "Cota", "Kv"),
        columns = ProfileColumns(listOf(ProfileRole.CHAINAGE, ProfileRole.ELEVATION, ProfileRole.KV)),
        preview = listOf(ProfileCsvRow(2, 1000.5, 50.0, null), ProfileCsvRow(3, 1200.0, 52.0, 2000.0)),
        rowCount = 2,
    )

    private fun show(state: ProfileCsvUiState, actions: ProfileCsvImportActions = ProfileCsvImportActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { ProfileCsvImportScreen(ThemeState.DEFAULT, {}, state, actions, {}) }
        }
    }

    @Test
    fun `S049 RF-9 cada problema del fichero se dice con su texto`() {
        val state = mutableStateOf(ProfileCsvUiState(stage = ProfileCsvStage.PROBLEM, fileName = "x.csv"))
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProfileCsvImportScreen(ThemeState.DEFAULT, {}, state.value, ProfileCsvImportActions(), {})
            }
        }
        val cases = listOf(
            ProfileCsvProblem.EMPTY to "está vacío",
            ProfileCsvProblem.NOT_TEXT to "no es de texto",
            ProfileCsvProblem.NO_COLUMNS to "al menos dos columnas",
            ProfileCsvProblem.TOO_BIG to "20 MB",
            ProfileCsvProblem.NO_ALIGNMENT to "no tiene ningún eje",
            ProfileCsvProblem.UNREADABLE to "No se ha podido leer",
        )
        for ((problem, text) in cases) {
            state.value = state.value.copy(problem = problem)
            compose.waitForIdle()
            compose.onNodeWithTag(PROFILE_CSV_PROBLEM_TAG).assertIsDisplayed()
            compose.onNodeWithText(text, substring = true).assertIsDisplayed()
        }
    }

    @Test
    fun `S049 RF-9 la lista de ejes marca el elegido, dice su PK y avisa de que se sustituye la rasante`() {
        var chosen: String? = null
        show(config, ProfileCsvImportActions(onAlignment = { chosen = it }))

        compose.onNodeWithText("Eje A · del PK 0+000,00 al 0+420,00").assertIsDisplayed()
        compose.onNodeWithText("ya tiene rasante: se sustituirá").assertIsDisplayed()
        compose.onNodeWithTag(profileCsvAlignmentTag("b")).performScrollTo().performClick()

        chosen shouldBe "b"
    }

    @Test
    fun `S049 RF-9 las opciones de lectura de S028 se pueden cambiar`() {
        var encoding: ImportEncoding? = null
        var separator: ImportSeparator? = null
        var decimal: ImportDecimal? = null
        var header: Boolean? = null
        show(
            config,
            ProfileCsvImportActions(
                onEncoding = { encoding = it },
                onSeparator = { separator = it },
                onDecimal = { decimal = it },
                onHasHeader = { header = it },
            ),
        )

        compose.onNodeWithTag(profileCsvEncodingTag(ImportEncoding.WINDOWS_1252)).performScrollTo().performClick()
        compose.onNodeWithTag(profileCsvSeparatorTag(ImportSeparator.TAB)).performScrollTo().performClick()
        compose.onNodeWithTag(profileCsvDecimalTag(ImportDecimal.POINT)).performScrollTo().performClick()
        compose.onNodeWithTag(PROFILE_CSV_HEADER_TAG).performScrollTo().performClick()

        encoding shouldBe ImportEncoding.WINDOWS_1252
        separator shouldBe ImportSeparator.TAB
        decimal shouldBe ImportDecimal.POINT
        header shouldBe false
    }

    @Test
    fun `S049 RF-9 cada columna ofrece PK cota Kv e ignorar`() {
        var changed: Pair<Int, ProfileRole>? = null
        show(config, ProfileCsvImportActions(onRole = { index, role -> changed = index to role }))

        compose.onNodeWithTag(profileCsvRoleTag(1, ProfileRole.KV)).performScrollTo().performClick()

        changed shouldBe (1 to ProfileRole.KV)
        compose.onNodeWithText("Columna 3: Kv").assertExists()
    }

    @Test
    fun `S049 RF-9 sin la columna del PK o de la cota se dice y no se puede asociar`() {
        show(config.copy(columns = ProfileColumns(listOf(ProfileRole.IGNORE, ProfileRole.ELEVATION, ProfileRole.KV))))

        compose.onNodeWithTag(PROFILE_CSV_MAPPING_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Falta asignar la columna del PK.").assertExists()
        compose.onNodeWithTag(PROFILE_CSV_ATTACH_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `S049 RF-9 la vista previa muestra los vertices leidos con y sin acuerdo`() {
        show(config)

        compose.onNodeWithTag(PROFILE_CSV_PREVIEW_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("2 vértices leídos").assertExists()
        compose.onNodeWithText("sin acuerdo", substring = true).assertExists()
        compose.onNodeWithText("Kv 2.000,0", substring = true).assertExists()
    }

    @Test
    fun `S049 RF-9 los errores dicen la linea el papel y el motivo y bloquean el boton`() {
        show(
            config.copy(
                errors = listOf(
                    ProfileCsvError(3, ProfileRole.ELEVATION, ProfileCsvError.Reason.NOT_A_NUMBER),
                    ProfileCsvError(4, ProfileRole.CHAINAGE, ProfileCsvError.Reason.MISSING),
                ),
            ),
        )

        compose.onNodeWithTag(PROFILE_CSV_ERRORS_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Línea 3, Cota: no es un número").assertExists()
        compose.onNodeWithText("Línea 4, PK: falta el valor").assertExists()
        compose.onNodeWithTag(PROFILE_CSV_ATTACH_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `S049 RF-9 con todo bien el boton asocia la rasante`() {
        var attached = 0
        show(config, ProfileCsvImportActions(onAttach = { attached++ }))

        compose.onNodeWithTag(PROFILE_CSV_ATTACH_TAG).performScrollTo().assertIsEnabled().performClick()

        attached shouldBe 1
    }

    @Test
    fun `S049 RF-10 una rasante rechazada dice el vertice y el motivo`() {
        show(config.copy(rejected = ProfileIssue.OutsideAxis(3, 1380.5)))

        compose.onNodeWithTag(PROFILE_CSV_REJECTED_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("el vértice 3 (PK 1+380,50) está fuera del eje", substring = true).assertExists()
    }

    @Test
    fun `S049 RF-9 un fallo al guardar y el final se dicen`() {
        show(config.copy(writeError = true))
        compose.onNodeWithTag(PROFILE_CSV_FAILED_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S049 RF-9 al terminar se dice a que eje se asocio`() {
        show(config.copy(stage = ProfileCsvStage.DONE, attachedTo = "Eje B"))

        compose.onNodeWithTag(PROFILE_CSV_DONE_TAG).assertIsDisplayed()
        compose.onNodeWithText("Rasante asociada al eje «Eje B».").assertExists()
    }
}
