package es.topophone.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.backup.BackupKind
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.SpainCrs
import es.topophone.core.model.project.TerrainPlane
import es.topophone.data.crs.RoomProjectCrsHistoryRepository
import es.topophone.data.crs.TerrainChange
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** El plano de terreno y su historial viajan en la copia completa y en el paquete de un proyecto (S058/RF-18). */
@RunWith(AndroidJUnit4::class)
class TerrainBackupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mine = "terrain-mine-${System.nanoTime()}.db"
    private val theirs = "terrain-theirs-${System.nanoTime()}.db"
    private lateinit var db: TopoPhoneDatabase
    private lateinit var other: TopoPhoneDatabase
    private lateinit var settings: TestSettings
    private lateinit var repository: BackupRepository
    private lateinit var otherRepository: BackupRepository
    private lateinit var fixture: BackupFixture
    private val plane = TerrainPlane(40.416801234567891, -3.7037123456789, 0.99954151490660681)

    @Before
    fun open() = runBlocking {
        db = TopoPhoneDatabase.build(context, mine)
        other = TopoPhoneDatabase.build(context, theirs)
        fixture = BackupFixture(db).fill()
        settings = TestSettings(folder.newFolder("settings"))
        repository =
            BackupRepository(context, db, settings.archive, "0.40.0", workDir = folder.newFolder("wa"), undoDir = folder.newFolder("ua"))
        otherRepository =
            BackupRepository(context, other, settings.archive, "0.40.0", workDir = folder.newFolder("wb"), undoDir = folder.newFolder("ub"))
        RoomProjectCrsHistoryRepository(
            db,
        ).apply(fixture.streetId, SpainCrs.ETRS89_UTM30, HeightSystem.ELLIPSOIDAL, TerrainChange.Set(plane))
            .shouldBeInstanceOf<es.topophone.data.crs.CrsChangeResult.Applied>()
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

    private fun backup(): ByteArray = ByteArrayOutputStream().also {
        runBlocking { repository.writeBackup(it) } shouldBe true
    }.toByteArray()

    @Test
    fun `S058 RF-18 la copia lleva el plano y al restaurarla vuelve igual`() = runBlocking<Unit> {
        val projects = RoomProjectRepository(db)
        projects.project(fixture.streetId)!!.terrain shouldBe plane
        val bytes = backup()
        projects.update(fixture.streetId, projects.project(fixture.streetId)!!.toDraft().copy(terrain = null))
        projects.project(fixture.streetId)!!.terrain shouldBe null

        val valid = repository.read({ bytes.inputStream() }, BackupKind.BACKUP).shouldBeInstanceOf<BackupRead.Valid>()
        repository.restore(valid).shouldBeInstanceOf<RestoreResult.Restored>()

        projects.project(fixture.streetId).shouldNotBeNull().terrain shouldBe plane
        RoomProjectCrsHistoryRepository(db).observe(fixture.streetId).first().single().toTerrain shouldBe plane
    }

    @Test
    fun `S058 RF-18 el paquete lleva el plano y su historial y se importa igual`() = runBlocking<Unit> {
        val pkg = ByteArrayOutputStream().also { repository.writePackage(fixture.streetId, it) shouldBe true }.toByteArray()
        val valid = otherRepository.read({ pkg.inputStream() }, BackupKind.PACKAGE).shouldBeInstanceOf<BackupRead.Valid>()

        otherRepository.importPackage(valid, null, importedLabel = "importado").shouldBeInstanceOf<ImportResult.Imported>()

        RoomProjectRepository(other).project(fixture.streetId).shouldNotBeNull().terrain shouldBe plane
        val change = RoomProjectCrsHistoryRepository(other).observe(fixture.streetId).first().single()
        change.fromTerrain shouldBe null
        change.toTerrain shouldBe plane
    }
}
