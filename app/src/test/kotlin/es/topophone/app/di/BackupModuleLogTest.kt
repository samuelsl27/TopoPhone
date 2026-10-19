package es.topophone.app.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.log.RecordingLog
import es.topophone.core.formats.backup.BackupKind
import es.topophone.core.model.log.LogLevel
import es.topophone.data.backup.AppLanguageStore
import es.topophone.data.backup.BackupRepository
import es.topophone.data.backup.SettingsArchive
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.settings.SettingsDataStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** La copia de seguridad que monta la app anota en el log las copias y los ficheros rechazados (S014/RF-4, RF-5). */
@RunWith(AndroidJUnit4::class)
class BackupModuleLogTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "bmlog-${System.nanoTime()}.db"
    private val db = TopoPhoneDatabase.build(context, name)
    private val log = RecordingLog()
    private val language = object : AppLanguageStore {
        override fun current() = ""

        override suspend fun apply(tag: String) = Unit
    }
    private val storesScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: BackupRepository

    @Before
    fun open() {
        db.openHelper.writableDatabase // la copia lee el fichero de la base de datos, que se crea al abrirla
        val stores = SettingsArchive.PORTABLE.keys.associateWith { store ->
            SettingsDataStore.create(storesScope) { File(folder.root, "$store.preferences_pb") }
        }
        repository = BackupModule.provideBackupRepository(context, db, SettingsArchive(stores, language), log)
    }

    @After
    fun close() {
        storesScope.cancel()
        db.close()
        context.deleteDatabase(name)
    }

    @Test
    fun `S014 RF-4 la copia de la app anota que se ha creado`() {
        runBlocking { repository.writeBackup(ByteArrayOutputStream()) } shouldBe true

        log.entries.single().first shouldBe LogLevel.INFO
        log.messages.single() shouldStartWith "Copia de seguridad creada"
    }

    @Test
    fun `S014 RF-5 la copia de la app anota un fichero rechazado`() {
        runBlocking { repository.read({ "no es una copia".byteInputStream() }, BackupKind.BACKUP) }

        log.entries.single().first shouldBe LogLevel.WARNING
        log.messages.single() shouldStartWith "Fichero rechazado como copia de seguridad"
    }
}
