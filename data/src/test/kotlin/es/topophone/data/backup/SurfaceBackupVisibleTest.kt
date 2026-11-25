package es.topophone.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.backup.BackupKind
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.surface.SurfaceExtent
import es.topophone.core.model.surface.SurfaceTile
import es.topophone.core.model.surface.SurfaceTileCodec
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.SaveResult
import es.topophone.data.surface.RoomSurfaceRepository
import es.topophone.data.surface.SurfaceDraft
import es.topophone.data.surface.SurfaceSummary
import es.topophone.data.surface.SurfaceTileWrite
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContain
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

/** La copia de seguridad conserva qué superficies se ven y no lleva sus contornos, que se recalculan (S061/RF-5, plan «Room v41»). */
@RunWith(AndroidJUnit4::class)
class SurfaceBackupVisibleTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "surf-vis-${System.nanoTime()}.db"
    private lateinit var db: TopoPhoneDatabase
    private lateinit var settings: TestSettings
    private lateinit var backups: BackupRepository
    private lateinit var surfaces: RoomSurfaceRepository
    private lateinit var project: String

    @Before
    fun open() = runBlocking {
        db = TopoPhoneDatabase.build(context, name)
        val fixture = BackupFixture(db).fill()
        settings = TestSettings(folder.newFolder("settings"))
        backups =
            BackupRepository(context, db, settings.archive, "0.13.0", workDir = folder.newFolder("w"), undoDir = folder.newFolder("u"))
        surfaces = RoomSurfaceRepository(db)
        project = (fixture.projects.create(ProjectDefaults().toDraft().copy(name = "Con superficies")) as SaveResult.Saved).id
        surface("s1")
        surface("s2")
        surfaces.setVisible("s1", true)
        surfaces.setVisible("s2", false)
        surfaces.outline("s1").shouldNotBeNull()
        Unit
    }

    @After
    fun close() {
        settings.close()
        db.close()
        context.deleteDatabase(name)
    }

    private suspend fun surface(id: String) {
        val tile = SurfaceTile(
            doubleArrayOf(440_000.0, 440_010.0, 440_000.0),
            doubleArrayOf(4_474_000.0, 4_474_000.0, 4_474_010.0),
            doubleArrayOf(600.0, 601.0, 602.0),
            intArrayOf(0, 1, 2),
        )
        surfaces.begin(SurfaceDraft(id, project, "terreno", "$id.xml", 1L, CadSourceCrs.Project, LinearUnit.METRE))
        surfaces.addTiles(
            id,
            listOf(SurfaceTileWrite(SurfaceExtent(440_000.0, 440_010.0, 4_474_000.0, 4_474_010.0), 1, SurfaceTileCodec.encode(tile))),
        )
        surfaces.finish(id, SurfaceSummary(SurfaceExtent(440_000.0, 440_010.0, 4_474_000.0, 4_474_010.0), 600.0, 602.0, 3, 1, 0))
    }

    @Test
    fun `S061 RF-5 los contornos no son una tabla de la copia`() {
        DatabaseSnapshot.TABLES.map { it.name } shouldNotContain "surface_outlines"
    }

    @Test
    fun `S061 RF-5 restaurar conserva que se ve y los contornos se recalculan`() = runBlocking<Unit> {
        val backup = ByteArrayOutputStream().also { backups.writeBackup(it) shouldBe true }.toByteArray()
        surfaces.setVisible("s1", false)
        surfaces.setVisible("s2", true)

        val valid = backups.read({ backup.inputStream() }, BackupKind.BACKUP).shouldBeInstanceOf<BackupRead.Valid>()
        backups.restore(valid).shouldBeInstanceOf<RestoreResult.Restored>()

        surfaces.surfaces(project).first().associate { it.id to it.visible } shouldBe mapOf("s1" to true, "s2" to false)
        db.surfaces().outlineParts("s1").shouldBeEmpty()
        surfaces.outline("s1").shouldNotBeNull().rings.single().vertexCount shouldBe 3
        db.surfaces().outlineParts("s1").shouldNotBeEmpty()
    }
}
