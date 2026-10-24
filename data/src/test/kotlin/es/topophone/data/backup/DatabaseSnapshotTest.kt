package es.topophone.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.data.backup.BackupFixture.Companion.TABLES
import es.topophone.data.backup.BackupFixture.Companion.columns
import es.topophone.data.backup.BackupFixture.Companion.rows
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.PointSaveResult
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Instantánea de la base de datos para la copia y el paquete (S013/RF-2, RF-3, RF-24 y RNF de la spec). */
@RunWith(AndroidJUnit4::class)
class DatabaseSnapshotTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "snapshot-${System.nanoTime()}.db"
    private lateinit var db: TopoPhoneDatabase
    private lateinit var fixture: BackupFixture
    private lateinit var source: File
    private lateinit var target: File

    @Before
    fun open() {
        db = TopoPhoneDatabase.build(context, name)
        fixture = BackupFixture(db).fill()
        source = context.getDatabasePath(name)
        target = File(context.cacheDir, "snapshot-target-${System.nanoTime()}.db")
    }

    @After
    fun close() {
        db.close()
        context.deleteDatabase(name)
        target.delete()
    }

    @Test
    fun `S013 RF-2 la copia lleva todas las tablas con los double identicos`() {
        val counts = DatabaseSnapshot(context, source).copy(target, SnapshotScope.All)

        for (table in TABLES - "ntrip_profiles") rows(target, table) shouldBe rows(source, table)
        rows(target, "points").size shouldBe 6 // 1, E1, 2, 3, 4 (papelera) y 9 (proyecto en la papelera)
        rows(target, "point_history").size shouldBe 1
        // Recuento para el resumen de la restauración: proyectos activos y sus puntos activos (S013/RF-6).
        counts shouldBe SnapshotCounts(projects = 2, points = 4)
    }

    @Test
    fun `S017 RF-12 la copia y el paquete llevan los tramos de frases de un promediado largo`() {
        val raw = (0 until 4_000).joinToString("\n") {
            "\$GNGGA,$it,4025.0080000,N,00342.2280000,W,4,28,0.5,650.123,M,51.234,M,1.0,0001*41"
        }
        val long = fixture.measured("L", fixture.streetId)
        runBlocking { fixture.points.create(long.copy(measurement = long.measurement!!.copy(rawNmea = raw))) }

        DatabaseSnapshot(context, source).copy(target, SnapshotScope.All)

        val chunkCols = columns(target, "point_nmea")
        val chunks = rows(target, "point_nmea")
        chunks.size shouldBe 2
        rows(target, "point_nmea") shouldBe rows(source, "point_nmea")
        chunks.joinToString("") { it[chunkCols.indexOf("text")] as String } shouldBe raw
        columns(target, "points").let { cols ->
            rows(target, "points").single { it[cols.indexOf("id")] == "id-L" }[cols.indexOf("nmea_chunks")]
        } shouldBe
            2L

        val packageTarget = File(context.cacheDir, "snapshot-package-${System.nanoTime()}.db")
        try {
            DatabaseSnapshot(context, source).copy(packageTarget, SnapshotScope.Project(fixture.streetId))
            rows(packageTarget, "point_nmea") shouldBe rows(source, "point_nmea")
        } finally {
            packageTarget.delete()
        }
    }

    @Test
    fun `S013 RF-3 las contrasenas NTRIP no salen, solo la marca de que el perfil la tenia`() {
        DatabaseSnapshot(context, source).copy(target, SnapshotScope.All)

        val cols = columns(target, "ntrip_profiles")
        val profiles = rows(target, "ntrip_profiles").associateBy { it[cols.indexOf("id")] }
        val ign = profiles.getValue("ntrip-ign")
        ign[cols.indexOf("password_iv")] shouldBe emptyList<Byte>()
        ign[cols.indexOf("password_ct")] shouldBe emptyList<Byte>()
        val free = profiles.getValue("ntrip-libre")
        free[cols.indexOf("password_iv")] shouldBe null
        free[cols.indexOf("password_ct")] shouldBe null
        ign[cols.indexOf("username")] shouldBe "ana"
    }

    @Test
    fun `S013 RF-24 el paquete lleva su proyecto entero y sin carpeta`() {
        val counts = DatabaseSnapshot(context, source).copy(target, SnapshotScope.Project(fixture.streetId))

        val projectCols = columns(target, "projects")
        val projects = rows(target, "projects")
        projects.map { it[projectCols.indexOf("id")] } shouldBe listOf(fixture.streetId)
        projects.single()[projectCols.indexOf("folder_id")] shouldBe null
        val pointCols = columns(target, "points")
        rows(target, "points").map { it[pointCols.indexOf("name")] } shouldContainExactlyInAnyOrder listOf("1", "E1", "2", "3", "4")
        rows(target, "point_history").size shouldBe 1
        rows(target, "map_viewports").size shouldBe 1
        rows(target, "project_folders") shouldBe emptyList()
        rows(target, "ntrip_profiles") shouldBe emptyList()
        counts shouldBe SnapshotCounts(projects = 1, points = 4)
    }

    @Test
    fun `S013 RNF un punto se guarda durante la instantanea y no entra en ella`() {
        var saved: Any? = null
        val snapshot = DatabaseSnapshot(context, source, onSnapshotOpen = {
            saved = runBlocking { fixture.points.create(fixture.measured("10", fixture.streetId)) }
        })

        snapshot.copy(target, SnapshotScope.All)

        saved shouldBe PointSaveResult.Saved
        val pointCols = columns(target, "points")
        rows(target, "points").map { it[pointCols.indexOf("name")] }.contains("10") shouldBe false
        rows(source, "points").map { it[pointCols.indexOf("name")] }.contains("10") shouldBe true
    }
}
