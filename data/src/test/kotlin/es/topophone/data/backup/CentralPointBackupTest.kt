package es.topophone.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.backup.BackupKind
import es.topophone.core.model.project.CentralPoint
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** El punto central viaja en las copias de seguridad y en los paquetes de proyecto, sin redondear (S006/RF-38). */
@RunWith(AndroidJUnit4::class)
class CentralPointBackupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mine = "central-a-${System.nanoTime()}.db"
    private val theirs = "central-b-${System.nanoTime()}.db"
    private lateinit var db: TopoPhoneDatabase
    private lateinit var other: TopoPhoneDatabase
    private lateinit var settings: TestSettings
    private lateinit var repository: BackupRepository
    private lateinit var otherRepository: BackupRepository
    private lateinit var projects: RoomProjectRepository
    private lateinit var project: String
    private val central = CentralPoint(39.46991234567891, -0.37631234567891)

    @Before
    fun open() = runBlocking {
        db = TopoPhoneDatabase.build(context, mine)
        other = TopoPhoneDatabase.build(context, theirs)
        settings = TestSettings(folder.newFolder("settings"))
        repository =
            BackupRepository(context, db, settings.archive, "0.13.0", workDir = folder.newFolder("wa"), undoDir = folder.newFolder("ua"))
        otherRepository =
            BackupRepository(context, other, settings.archive, "0.13.0", workDir = folder.newFolder("wb"), undoDir = folder.newFolder("ub"))
        projects = RoomProjectRepository(db)
        project =
            (projects.create(ProjectDefaults().toDraft().copy(name = "Con punto central", centralPoint = central)) as SaveResult.Saved).id
        Unit
    }

    @After
    fun close() {
        settings.close()
        db.close()
        other.close()
        context.deleteDatabase(mine)
        context.deleteDatabase(theirs)
    }

    private fun writePackage() = ByteArrayOutputStream().also {
        runBlocking { repository.writePackage(project, it) } shouldBe true
    }.toByteArray()

    private fun import(pkg: ByteArray, choice: ImportChoice? = null): ImportResult {
        val valid = runBlocking { otherRepository.read({ pkg.inputStream() }, BackupKind.PACKAGE) }.shouldBeInstanceOf<BackupRead.Valid>()
        return runBlocking { otherRepository.importPackage(valid, choice, importedLabel = "importado") }
    }

    @Test
    fun `S006 RF-38 el paquete lleva el punto central sin redondear`() {
        val imported = import(writePackage()).shouldBeInstanceOf<ImportResult.Imported>()

        runBlocking { RoomProjectRepository(other).project(imported.projectId) }!!.centralPoint shouldBe central
    }

    @Test
    fun `S006 RF-38 importar el paquete como nuevo conserva el punto central`() {
        val pkg = writePackage()
        import(pkg).shouldBeInstanceOf<ImportResult.Imported>()

        val second = import(pkg, ImportChoice.AS_NEW).shouldBeInstanceOf<ImportResult.Imported>()

        runBlocking { RoomProjectRepository(other).project(second.projectId) }!!.centralPoint shouldBe central
    }

    @Test
    fun `S006 RF-38 sustituir deja el punto central del paquete`() {
        val pkg = writePackage()
        runBlocking { projects.update(project, projects.project(project)!!.toDraft().copy(centralPoint = CentralPoint(41.0, -1.0))) }
        val valid = runBlocking { repository.read({ pkg.inputStream() }, BackupKind.PACKAGE) }.shouldBeInstanceOf<BackupRead.Valid>()

        runBlocking {
            repository.importPackage(valid, ImportChoice.REPLACE, importedLabel = "importado")
        }.shouldBeInstanceOf<ImportResult.Imported>()

        runBlocking { projects.project(project) }!!.centralPoint shouldBe central
    }

    @Test
    fun `S006 RF-38 la copia de seguridad restaura el punto central`() {
        val backup = ByteArrayOutputStream().also { runBlocking { repository.writeBackup(it) } shouldBe true }.toByteArray()
        runBlocking { projects.update(project, projects.project(project)!!.toDraft().copy(centralPoint = null)) }
        runBlocking { projects.project(project) }!!.centralPoint.shouldBeNull()

        val valid = runBlocking { repository.read({ backup.inputStream() }, BackupKind.BACKUP) }.shouldBeInstanceOf<BackupRead.Valid>()
        runBlocking { repository.restore(valid) }.shouldBeInstanceOf<RestoreResult.Restored>()

        runBlocking { projects.project(project) }!!.centralPoint shouldBe central
    }
}
