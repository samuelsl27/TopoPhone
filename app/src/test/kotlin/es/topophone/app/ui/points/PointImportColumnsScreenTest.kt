package es.topophone.app.ui.points

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.point.ColumnMapping
import es.topophone.core.model.point.ColumnRole
import es.topophone.core.model.point.DuplicatePolicy
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.ImportCoordinates
import es.topophone.core.model.point.ImportOptions
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.LinearUnit
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** El asistente de importación: columnas, SC, unidad, altura y nombres repetidos (S028/RF-10 a RF-20). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class PointImportColumnsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = PointImportActions(
        onColumnRole = { index, role -> calls += "role:$index:$role" },
        onPenzd = { calls += "penzd" },
        onPnezd = { calls += "pnezd" },
        onCoordinates = { form, kind -> calls += "coordinates:$form:$kind" },
        onUnit = { calls += "unit:$it" },
        onZeroAsNoHeight = { calls += "zero:$it" },
        onDuplicates = { calls += "duplicates:$it" },
    )

    private val header = listOf("Name", "Easting", "Northing", "Elevation", "Description")
    private val wizard = PointImportUiState(
        stage = ImportStage.WIZARD,
        fileName = "puntos.csv",
        columnCount = 5,
        headerCells = header,
        options = ImportOptions(hasHeader = true, mapping = ColumnMapping.penzd(5)),
    )

    private fun show(state: PointImportUiState = wizard) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { PointImportScreen(ThemeState.DEFAULT, {}, state, actions, onBack = {}) }
        }
    }

    private fun withOptions(change: (ImportOptions) -> ImportOptions) = wizard.copy(options = change(wizard.options))

    @Test
    fun `S028 RF-11 cada columna del fichero tiene su selector de papel con el nombre de la columna`() {
        show()

        header.indices.forEach { compose.onNodeWithTag(importColumnTag(it)).performScrollTo().assertIsDisplayed() }
        compose.onNodeWithText("Columna 2: Easting").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(importColumnTag(0)).assertTextContains("Nombre")
        compose.onNodeWithTag(importColumnTag(1)).assertTextContains("Este")
        compose.onNodeWithTag(importColumnTag(2)).assertTextContains("Norte")
        compose.onNodeWithTag(importColumnTag(3)).assertTextContains("Altura")
        compose.onNodeWithTag(importColumnTag(4)).assertTextContains("Descripción")
    }

    @Test
    fun `S028 RF-11 se cambia el papel de una columna desde su desplegable`() {
        show()

        compose.onNodeWithTag(importColumnTag(3)).performScrollTo().performClick()
        compose.onNodeWithTag(importRoleTag(3, ColumnRole.IGNORE)).performClick()

        calls shouldBe listOf("role:3:IGNORE")
    }

    @Test
    fun `S028 RF-11 el desplegable ofrece todos los papeles`() {
        show()

        compose.onNodeWithTag(importColumnTag(0)).performScrollTo().performClick()

        ColumnRole.entries.forEach { compose.onNodeWithTag(importRoleTag(0, it)).assertIsDisplayed() }
    }

    @Test
    fun `S028 RF-11 las columnas sin asignar se ven como ignoradas`() {
        show(
            withOptions {
                it.copy(
                    mapping = ColumnMapping(
                        listOf(ColumnRole.NAME, ColumnRole.FIRST, ColumnRole.SECOND, ColumnRole.IGNORE, ColumnRole.IGNORE),
                    ),
                )
            },
        )

        compose.onNodeWithTag(importColumnTag(3)).performScrollTo().assertTextContains("Ignorar")
    }

    @Test
    fun `S028 RF-11 PENZD y PNEZD se ponen con un toque`() {
        show()

        compose.onNodeWithTag(IMPORT_PENZD_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(IMPORT_PNEZD_TAG).performScrollTo().performClick()

        calls shouldBe listOf("penzd", "pnezd")
    }

    @Test
    fun `S028 RF-11 sin nombre se dice que falta la columna`() {
        show(withOptions { it.copy(mapping = ColumnMapping(listOf(ColumnRole.IGNORE, ColumnRole.FIRST, ColumnRole.SECOND))) })

        compose.onNodeWithTag(IMPORT_MAPPING_PROBLEM_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Falta asignar la columna «Nombre».").assertIsDisplayed()
    }

    @Test
    fun `S028 RF-11 sin una coordenada se dice cual falta`() {
        show(withOptions { it.copy(mapping = ColumnMapping(listOf(ColumnRole.NAME, ColumnRole.FIRST, ColumnRole.IGNORE))) })

        compose.onNodeWithText("Falta asignar la columna «Norte».").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S028 RF-11 con un papel repetido se avisa`() {
        show(
            withOptions {
                it.copy(mapping = ColumnMapping(listOf(ColumnRole.NAME, ColumnRole.FIRST, ColumnRole.FIRST, ColumnRole.SECOND)))
            },
        )

        compose.onNodeWithText("Hay un papel asignado a más de una columna.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S028 RF-11 con una asignacion valida no hay aviso`() {
        show()

        compose.onNodeWithTag(IMPORT_MAPPING_PROBLEM_TAG).assertDoesNotExist()
    }

    @Test
    fun `S028 RF-12 con coordenadas geograficas las columnas se llaman latitud y longitud`() {
        show(withOptions { it.copy(coordinates = ImportCoordinates(EntryForm.GEOGRAPHIC, HeightKind.PROJECT)) })

        compose.onNodeWithTag(importColumnTag(1)).performScrollTo().assertTextContains("Latitud")
        compose.onNodeWithTag(importColumnTag(2)).performScrollTo().assertTextContains("Longitud")
    }

    @Test
    fun `S028 RF-12 se propone el SC del proyecto y se puede elegir geograficas`() {
        show()

        compose.onNodeWithTag(importFormTag(EntryForm.PROJECT_CRS)).performScrollTo().assertIsSelected()
        compose.onNodeWithTag(importFormTag(EntryForm.GEOGRAPHIC)).performScrollTo().assertIsNotSelected().performClick()

        calls shouldBe listOf("coordinates:GEOGRAPHIC:PROJECT")
    }

    @Test
    fun `S028 RF-13 la altura solo se elige entre altitud y elipsoidal en geograficas`() {
        show()

        compose.onNodeWithTag(importHeightKindTag(HeightKind.ELLIPSOIDAL)).assertDoesNotExist()
    }

    @Test
    fun `S028 RF-13 en geograficas se elige la altura del proyecto o la elipsoidal`() {
        show(withOptions { it.copy(coordinates = ImportCoordinates(EntryForm.GEOGRAPHIC, HeightKind.PROJECT)) })

        compose.onNodeWithTag(importHeightKindTag(HeightKind.PROJECT)).performScrollTo().assertIsSelected()
        compose.onNodeWithTag(importHeightKindTag(HeightKind.ELLIPSOIDAL)).performScrollTo().assertIsEnabled().performClick()

        calls shouldBe listOf("coordinates:GEOGRAPHIC:ELLIPSOIDAL")
    }

    @Test
    fun `S028 RF-13 un proyecto en ED50 no deja elegir la altura elipsoidal`() {
        show(withOptions { it.copy(coordinates = ImportCoordinates(EntryForm.GEOGRAPHIC, HeightKind.PROJECT)) }.copy(ed50 = true))

        compose.onNodeWithTag(importHeightKindTag(HeightKind.ELLIPSOIDAL)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("No disponible en un proyecto en ED50.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(importHeightKindTag(HeightKind.PROJECT)).assertIsEnabled()
    }

    @Test
    fun `S028 RF-14 la unidad propuesta sale marcada y se puede elegir otra`() {
        show(withOptions { it.copy(unit = LinearUnit.INTERNATIONAL_FOOT) })

        compose.onNodeWithTag(importUnitTag(LinearUnit.INTERNATIONAL_FOOT)).performScrollTo().assertIsSelected()
        compose.onNodeWithTag(importUnitTag(LinearUnit.METRE)).assertIsNotSelected()
        compose.onNodeWithTag(importUnitTag(LinearUnit.US_SURVEY_FOOT)).performScrollTo().performClick()

        calls shouldBe listOf("unit:US_SURVEY_FOOT")
    }

    @Test
    fun `S028 RF-14 las tres unidades llevan su nombre`() {
        show()

        compose.onNodeWithText("Metros").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Pies").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Pies topográficos de EE. UU.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S028 RF-16 la casilla de tratar 0 como sin altura viene desmarcada y se marca`() {
        show()

        compose.onNodeWithTag(IMPORT_ZERO_TAG).performScrollTo().assertIsOff().performClick()

        calls shouldBe listOf("zero:true")
    }

    @Test
    fun `S028 RF-16 con la casilla marcada se puede desmarcar`() {
        show(withOptions { it.copy(zeroAsNoHeight = true) })

        compose.onNodeWithTag(IMPORT_ZERO_TAG).performScrollTo().assertIsOn().performClick()

        calls shouldBe listOf("zero:false")
    }

    @Test
    fun `S028 RF-17 saltar viene marcado por defecto y se ofrecen renombrar y cancelar`() {
        show()

        compose.onNodeWithTag(importDuplicatesTag(DuplicatePolicy.SKIP)).performScrollTo().assertIsSelected()
        compose.onNodeWithText("Saltar").assertIsDisplayed()
        compose.onNodeWithText("Renombrar").assertIsDisplayed()
        compose.onNodeWithText("Cancelar la importación").assertIsDisplayed()
    }

    @Test
    fun `S028 RF-17 se elige renombrar o cancelar la importacion`() {
        show()

        compose.onNodeWithTag(importDuplicatesTag(DuplicatePolicy.RENAME)).performScrollTo().performClick()
        compose.onNodeWithTag(importDuplicatesTag(DuplicatePolicy.CANCEL)).performScrollTo().performClick()

        calls shouldBe listOf("duplicates:RENAME", "duplicates:CANCEL")
    }

    @Test
    fun `S028 RF-17 si se cancela ante un repetido se explica por que no se puede importar`() {
        show(withOptions { it.copy(duplicates = DuplicatePolicy.CANCEL) }.copy(blockedByDuplicates = true))

        compose.onNodeWithTag(IMPORT_DUPLICATES_BLOCK_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Hay nombres repetidos: cambia la opción para poder importar.").assertIsDisplayed()
    }

    @Test
    fun `S028 RF-17 sin repetidos no hay aviso aunque se elija cancelar`() {
        show(withOptions { it.copy(duplicates = DuplicatePolicy.CANCEL) })

        compose.onNodeWithTag(IMPORT_DUPLICATES_BLOCK_TAG).assertDoesNotExist()
    }
}
