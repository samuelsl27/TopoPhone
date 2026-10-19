package es.topophone.app.ui.backup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.StatefulTestApp
import es.topophone.app.ui.TopLevelDestination
import es.topophone.app.ui.navItem
import es.topophone.app.ui.projects.CONFIRM_TAG
import es.topophone.app.ui.settings.BACKUPS_SETTING_TAG
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Pantalla "Copias de seguridad" de Ajustes (S013/RF-1, RF-6, RF-11, RF-12, RF-17, RF-20, RF-21). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class BackupsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = BackupsActions(
        onCreate = { calls += "create:$it" },
        onRestore = { calls += "restore" },
        onConfirmRestore = { calls += "confirm" },
        onCancelRestore = { calls += "cancel" },
        onUndo = { calls += "undo" },
        onChooseFolder = { calls += "folder" },
        onRemoveFolder = { calls += "remove" },
        onDismissMessage = { calls += "dismiss" },
    )

    private fun show(state: BackupsUiState) {
        compose.setContent { TopoPhoneTheme(EffectiveTheme.LIGHT) { BackupsScreen(ThemeState.DEFAULT, {}, state, actions, onBack = {}) } }
    }

    @Test
    fun `S013 RF-1 crear la copia propone su nombre`() {
        show(BackupsUiState())

        compose.onNodeWithTag(BACKUP_CREATE_TAG).performClick()

        calls.single() shouldStartWith "create:TopoPhone_"
        calls.single() shouldEndWith ".zip"
    }

    @Test
    fun `S013 RF-6 se ve la fecha y lo que tiene antes de restaurar`() {
        show(BackupsUiState(step = BackupStep.Confirm(Instant.parse("2026-10-19T06:02:31Z"), projects = 2, points = 4)))

        compose.onNodeWithText("2 proyectos y 4 puntos", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Sustituirá todos los datos del móvil", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(CONFIRM_TAG).performClick()

        calls shouldBe listOf("confirm")
    }

    @Test
    fun `S013 RF-6 elegir la copia abre el selector`() {
        show(BackupsUiState())

        compose.onNodeWithTag(BACKUP_RESTORE_TAG).performClick()

        calls shouldBe listOf("restore")
    }

    @Test
    fun `S013 RF-11 deshacer solo tras restaurar y con confirmacion`() {
        show(BackupsUiState(undoAvailable = true))

        compose.onNodeWithTag(BACKUP_UNDO_TAG).performClick()
        compose.onNodeWithText("vuelven a como estaban", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(CONFIRM_TAG).performClick()

        calls shouldBe listOf("undo")
    }

    @Test
    fun `S013 RF-11 sin restauracion no se ofrece deshacer`() {
        show(BackupsUiState(undoAvailable = false))

        compose.onNodeWithTag(BACKUP_UNDO_TAG).assertDoesNotExist()
    }

    @Test
    fun `S013 RF-17 y RF-20 la carpeta y la ultima copia`() {
        show(
            BackupsUiState(
                folderUri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FTopoPhone",
                lastAutoBackupMs = Instant.parse("2026-10-19T06:00:00Z").toEpochMilli(),
            ),
        )

        compose.onNodeWithText("Documents/TopoPhone").assertIsDisplayed()
        compose.onNodeWithText("Última copia automática").assertIsDisplayed()
        compose.onNodeWithText("2026", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(BACKUP_FOLDER_REMOVE_TAG).performScrollTo().performClick()

        calls shouldBe listOf("remove")
    }

    @Test
    fun `S013 RF-17 sin carpeta se ofrece elegirla`() {
        show(BackupsUiState())

        compose.onNodeWithText("Sin elegir").assertIsDisplayed()
        compose.onNodeWithTag(BACKUP_FOLDER_REMOVE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(BACKUP_FOLDER_TAG).performClick()

        calls shouldBe listOf("folder")
    }

    @Test
    fun `S013 RF-20 sin copias todavia se dice`() {
        show(BackupsUiState(folderUri = "content://tree/primary%3ACopias"))

        compose.onNodeWithText("Todavía no se ha hecho ninguna").assertIsDisplayed()
    }

    @Test
    fun `S013 RF-21 el fallo de la copia automatica se ve`() {
        show(BackupsUiState(folderUri = "content://tree/primary%3ACopias", autoFailing = true))

        compose.onNodeWithText("No se ha podido hacer la copia automática.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S013 RF-12 y RF-13 los avisos se ven y se cierran`() {
        show(BackupsUiState(message = BackupMessage.NEWER))

        compose.onNodeWithText("versión más nueva", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Entendido").performClick()

        calls shouldBe listOf("dismiss")
    }

    @Test
    fun `S013 RF-1 Ajustes lleva a Copias de seguridad`() {
        compose.setContent { StatefulTestApp() }

        compose.navItem(TopLevelDestination.SETTINGS).performClick()
        compose.onNodeWithTag(BACKUPS_SETTING_TAG).performScrollTo().performClick()

        compose.onNodeWithTag(BACKUP_CREATE_TAG).assertIsDisplayed()
    }
}
