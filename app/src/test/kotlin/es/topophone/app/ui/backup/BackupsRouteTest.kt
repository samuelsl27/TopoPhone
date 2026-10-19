package es.topophone.app.ui.backup

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.backup.AutoBackupScheduler
import es.topophone.app.backup.FakeFolder
import es.topophone.app.backup.FakePackageBackups
import es.topophone.app.backup.FakePreferences
import es.topophone.app.ui.collector.MeasurementInProgress
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.formats.backup.BackupKind
import es.topophone.core.formats.backup.BackupManifest
import es.topophone.core.formats.backup.BackupSettings
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.data.backup.BackupRead
import es.topophone.data.backup.RestoreResult
import io.kotest.matchers.shouldBe
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Tras restaurar se vuelve una sola vez a la lista de proyectos, aunque se vuelva a la pantalla (S013/RF-8, RF-11). */
@RunWith(AndroidJUnit4::class)
class BackupsRouteTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val backups = object : FakePackageBackupsWithRestore() {}

    /** Las copias de [FakePackageBackups] con una restauración que va bien. */
    open class FakePackageBackupsWithRestore : es.topophone.data.backup.Backups by FakePackageBackups() {
        override suspend fun read(input: () -> java.io.InputStream, kind: BackupKind): BackupRead = BackupRead.Valid(
            BackupManifest(BackupKind.BACKUP, 6, "0.13.0", Instant.EPOCH, 1, 1),
            File("data.db"),
            BackupSettings(emptyMap(), ""),
        )

        override suspend fun restore(valid: BackupRead.Valid, keepCopy: suspend (File) -> Unit): RestoreResult =
            RestoreResult.Restored(1, 1)
    }

    @Test
    fun `S013 RF-8 volver a la pantalla tras restaurar no lleva otra vez a la lista`() {
        val preferences = FakePreferences()
        val scheduler = AutoBackupScheduler({}, FakeFolder(), preferences, folder.newFolder(), CoroutineScope(Dispatchers.Unconfined))
        val vm = BackupsViewModel(backups, preferences, scheduler, MeasurementInProgress())
        var restored = 0
        val shown = mutableStateOf(true)
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                if (shown.value) BackupsRoute(ThemeState.DEFAULT, {}, onBack = {}, onRestored = { restored++ }, viewModel = vm)
            }
        }

        vm.open { "zip".byteInputStream() }
        vm.confirmRestore()
        compose.waitForIdle()
        restored shouldBe 1

        // Se sale de la pantalla y se vuelve (como al cambiar de sección y regresar a Ajustes).
        compose.runOnIdle { shown.value = false }
        compose.runOnIdle { shown.value = true }
        compose.waitForIdle()

        restored shouldBe 1
    }
}
