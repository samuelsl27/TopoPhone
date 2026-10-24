package es.topophone.app.ui.backup

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.backup.AutoBackupScheduler
import es.topophone.app.backup.FakeFolder
import es.topophone.app.backup.FakePreferences
import es.topophone.app.ui.projects.BACKUP_CHOOSE_FOLDER_TAG
import es.topophone.app.ui.projects.BACKUP_NOTICE_DISMISS_TAG
import es.topophone.app.ui.projects.BACKUP_NOTICE_TAG
import es.topophone.app.ui.projects.BACKUP_REVIEW_TAG
import es.topophone.app.ui.projects.ProjectListActions
import es.topophone.app.ui.projects.ProjectListScreen
import es.topophone.app.ui.projects.ProjectListUiState
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.backup.AutoBackupState
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import io.kotest.matchers.shouldBe
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Aviso sin carpeta y alerta de fallo de las copias automáticas en la lista de proyectos (S013/RF-21, RF-22, RF-23). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ProjectListBackupNoticeTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val calls = mutableListOf<String>()
    private val actions = ProjectListActions(
        onChooseBackupFolder = { calls += "choose" },
        onDismissBackupNotice = { calls += "dismiss" },
        onReviewBackups = { calls += "review" },
    )

    private fun show(notice: BackupNotice) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { ProjectListScreen(ThemeState.DEFAULT, {}, ProjectListUiState(), actions, notice) }
        }
    }

    @Test
    fun `S013 RF-22 sin carpeta se avisa con un boton para elegirla`() {
        show(BackupNotice.NO_FOLDER)

        compose.onNodeWithText("Las copias automáticas están desactivadas.").assertIsDisplayed()
        compose.onNodeWithTag(BACKUP_CHOOSE_FOLDER_TAG).performClick()
        compose.onNodeWithTag(BACKUP_NOTICE_DISMISS_TAG).performClick()

        calls shouldBe listOf("choose", "dismiss")
    }

    @Test
    fun `S013 RF-21 el fallo se avisa con un boton para revisarlo`() {
        show(BackupNotice.FAILED)

        compose.onNodeWithText("No se ha podido hacer la copia automática.").assertIsDisplayed()
        compose.onNodeWithTag(BACKUP_REVIEW_TAG).performClick()

        calls shouldBe listOf("review")
    }

    @Test
    fun `S013 RF-22 con las copias en marcha no hay aviso`() {
        show(BackupNotice.NONE)

        compose.onNodeWithTag(BACKUP_NOTICE_TAG).assertDoesNotExist()
    }

    private class Setup(folder: TemporaryFolder, initial: AutoBackupState) {
        // Los hilos de Room son de la prueba: al cerrar se espera a la consulta en vuelo, que si no lee una base ya cerrada.
        private val executor = Executors.newSingleThreadExecutor()
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
            .setQueryExecutor(executor)
            .build()
        val projects = RoomProjectRepository(db)
        val preferences = FakePreferences(initial)
        val backupFolder = FakeFolder()
        var now = 1_792_400_000_000L
        val vm = BackupNoticeViewModel(
            preferences,
            projects,
            AutoBackupScheduler({
                it.writeText("copia")
            }, backupFolder, preferences, folder.newFolder(), CoroutineScope(Dispatchers.Unconfined)),
        ).also { vm -> vm.clock = { now } }

        val collector = CoroutineScope(Dispatchers.Unconfined).launch { vm.notice.collect {} }

        /** Antes de cerrar la base de datos, que nada siga leyéndola. */
        fun close() {
            collector.cancel()
            vm.viewModelScope.cancel()
            executor.shutdown()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            db.close()
        }

        fun await(expected: BackupNotice) = runBlocking { withTimeout(5_000) { vm.notice.first { it == expected } } }

        fun addProject(name: String = "Calle Mayor 12") = runBlocking { projects.create(ProjectDefaults().toDraft().copy(name = name)) }
    }

    private var setup: Setup? = null

    private fun setup(initial: AutoBackupState = AutoBackupState()): Setup {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        return Setup(folder, initial).also { setup = it }
    }

    @After
    fun tearDown() {
        setup?.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `S013 RF-22 el aviso sale con el primer proyecto`() {
        val s = setup()
        s.vm.notice.value shouldBe BackupNotice.NONE

        s.addProject()

        s.await(BackupNotice.NO_FOLDER) shouldBe BackupNotice.NO_FOLDER
    }

    @Test
    fun `S013 RF-23 cerrado no vuelve hasta 7 dias despues`() {
        val s = setup()
        s.addProject()
        s.await(BackupNotice.NO_FOLDER)

        s.vm.dismiss()
        s.await(BackupNotice.NONE) shouldBe BackupNotice.NONE

        s.now += 7 * 24 * 60 * 60 * 1000L
        s.addProject("Puerto") // Cualquier cambio vuelve a calcular el aviso, como al abrir la lista.
        s.await(BackupNotice.NO_FOLDER) shouldBe BackupNotice.NO_FOLDER
    }

    @Test
    fun `S013 RF-22 elegir la carpeta desde el aviso lo quita y copia`() {
        val s = setup()
        s.addProject()
        s.await(BackupNotice.NO_FOLDER)

        s.vm.folderChosen("content://tree/copias")

        s.await(BackupNotice.NONE) shouldBe BackupNotice.NONE
        s.backupFolder.files.size shouldBe 1
    }

    @Test
    fun `S013 RF-21 una copia fallida se avisa`() {
        val s = setup(AutoBackupState(folderUri = "content://tree/copias", failing = true))

        s.await(BackupNotice.FAILED) shouldBe BackupNotice.FAILED
    }
}
