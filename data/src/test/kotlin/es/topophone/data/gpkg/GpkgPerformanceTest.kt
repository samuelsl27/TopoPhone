package es.topophone.data.gpkg

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.geodesy.Utm
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.cad.ImportArea
import es.topophone.core.model.project.CentralPoint
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.cad.CadChunkWriter
import es.topophone.data.cad.RoomCadRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.time.measureTimedValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Un GeoPackage sintético de unos 100 MB se lee y se recorta a la zona en menos de 20 s y cambiar el radio recuenta en menos de 2 s
 * (S048, RNF). Robolectric sirve de cota —no trae el módulo R-tree, así que es el camino lento—; la medida en el móvil de referencia es de la demo.
 */
@RunWith(AndroidJUnit4::class)
class GpkgPerformanceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val cad = RoomCadRepository(db)
    private val crs = ProjectCrs.ETRS89_UTM30
    private val center = Utm.inverse(440_000.0, 4_474_000.0, 30).let { CentralPoint(it.latitudeDeg, it.longitudeDeg) }

    @After
    fun close() = db.close()

    private fun blob(cx: Double, cy: Double): ByteArray {
        val xs = DoubleArray(VERTICES) { cx + it * 3.0 }
        val ys = DoubleArray(VERTICES) { cy + (it % 2) * 4.0 }
        val buffer = ByteBuffer.allocate(8 + 32 + 1 + 4 + 4 + VERTICES * 16).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put('G'.code.toByte()).put('P'.code.toByte()).put(0).put(3).putInt(25830) // little endian con envolvente XY
        buffer.putDouble(xs.min()).putDouble(xs.max()).putDouble(ys.min()).putDouble(ys.max())
        buffer.put(1).putInt(2).putInt(VERTICES)
        for (i in 0 until VERTICES) buffer.putDouble(xs[i]).putDouble(ys[i])
        return buffer.array()
    }

    /** Un GeoPackage de [rows] líneas de 6 vértices en una malla de 25 m de paso sobre 20 × 15 km alrededor de Madrid. */
    private fun build(file: File, rows: Int) {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
            database.execSQL("PRAGMA application_id=${0x47504B47}")
            database.execSQL(
                "CREATE TABLE gpkg_spatial_ref_sys (srs_name TEXT NOT NULL, srs_id INTEGER PRIMARY KEY, organization TEXT NOT NULL, organization_coordsys_id INTEGER NOT NULL, definition TEXT NOT NULL, description TEXT)",
            )
            database.execSQL("INSERT INTO gpkg_spatial_ref_sys VALUES ('ETRS89 / UTM zone 30N', 25830, 'EPSG', 25830, 'x', NULL)")
            database.execSQL(
                "CREATE TABLE gpkg_contents (table_name TEXT NOT NULL PRIMARY KEY, data_type TEXT NOT NULL, identifier TEXT, description TEXT, last_change TEXT NOT NULL, min_x DOUBLE, min_y DOUBLE, max_x DOUBLE, max_y DOUBLE, srs_id INTEGER)",
            )
            database.execSQL(
                "INSERT INTO gpkg_contents VALUES ('tramos', 'features', 'tramos', '', '2026-10-27', NULL, NULL, NULL, NULL, 25830)",
            )
            database.execSQL(
                "CREATE TABLE gpkg_geometry_columns (table_name TEXT NOT NULL, column_name TEXT NOT NULL, geometry_type_name TEXT NOT NULL, srs_id INTEGER NOT NULL, z TINYINT NOT NULL, m TINYINT NOT NULL)",
            )
            database.execSQL("INSERT INTO gpkg_geometry_columns VALUES ('tramos', 'geom', 'LINESTRING', 25830, 0, 0)")
            database.execSQL("CREATE TABLE tramos (fid INTEGER PRIMARY KEY AUTOINCREMENT, geom BLOB, nombre TEXT, codigo INTEGER)")
            database.beginTransaction()
            try {
                val insert = database.compileStatement("INSERT INTO tramos (geom, nombre, codigo) VALUES (?, ?, ?)")
                val padding = "x".repeat(NAME_PADDING)
                for (i in 0 until rows) {
                    insert.clearBindings()
                    insert.bindBlob(1, blob(430_000.0 + (i % COLUMNS) * 25.0, 4_464_000.0 + (i / COLUMNS) * 25.0))
                    insert.bindString(2, "Tramo $i $padding")
                    insert.bindLong(3, i.toLong())
                    insert.executeInsert()
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }
    }

    @Test
    fun `S048 RNF un GeoPackage de 100 MB se lee y se recorta en menos de 20 s y el radio recuenta en menos de 2 s`() {
        val file = folder.newFile("obra.gpkg")
        val built = measureTimedValue { build(file, ROWS) }
        println("S048 generación de ${file.length() / (1024 * 1024)} MB: ${built.duration.inWholeMilliseconds} ms, $ROWS objetos")
        (file.length() > 90L * 1024 * 1024) shouldBe true
        val projectId = runBlocking {
            RoomProjectRepository(
                db,
            ).create(ProjectDefaults().toDraft().copy(name = "Obra grande", crs = crs)).shouldBeInstanceOf<SaveResult.Saved>().id
        }

        val reader = GeoPackageReader(folder.newFolder("cache"))
        val opened = measureTimedValue { runBlocking { reader.open(file.length()) { file.inputStream() } } }
        val session = opened.value.shouldBeInstanceOf<GpkgOpenResult.Opened>().session
        session.use {
            val layer = session.layers.single()
            layer.featureCount shouldBe ROWS.toLong()
            val scan = GeoPackageScan(session)
            val indexed = measureTimedValue { runBlocking { scan.index(layer) } }
            indexed.value.size shouldBe ROWS
            val source = CadSourceCrs.Utm(GeodeticDatum.ETRS89, 30)

            // Cambiar el radio recuenta sobre las envolventes en memoria: menos de 2 s cada vez.
            val times = listOf(1_000.0, 2_000.0, 3_000.0, 500.0).map { radius ->
                val counted =
                    measureTimedValue { runBlocking { scan.count(indexed.value, source, crs, ImportArea.Circle(center, radius)) } }
                println(
                    "S048 recuento con radio de ${radius.toInt()} m: ${counted.value.inZone} objetos en ${counted.duration.inWholeMilliseconds} ms",
                )
                (counted.value.inZone > 0) shouldBe true
                counted.duration.inWholeMilliseconds
            }
            times.forEach { (it < 2_000) shouldBe true }

            val importer = GeoPackageImporter(cad)
            val area = ImportArea.Circle(center, 5_000.0)
            val imported = measureTimedValue {
                runBlocking {
                    importer.import(
                        projectId,
                        crs,
                        "obra.gpkg",
                        GpkgImportOptions(listOf(GpkgLayerChoice(layer, source, "nombre")), true, area),
                        session,
                    )
                }
            }
            println(
                "S048 lectura y recorte: abrir ${opened.duration.inWholeMilliseconds} ms, índice ${indexed.duration.inWholeMilliseconds} ms, importar ${imported.duration.inWholeMilliseconds} ms, pico de buffers ${importer.peakBufferedBytes / 1024} kB",
            )
            val result = imported.value.shouldBeInstanceOf<GpkgImportResult.Imported>()
            (result.summary.importedTotal in 1 until ROWS) shouldBe true
            result.summary.layers.single().outsideArea shouldBe ROWS - result.summary.importedTotal
            (importer.peakBufferedBytes <= 32L * 1024 * 1024 + 256 * 1024) shouldBe true
            val drawn = runBlocking { cad.drawings(projectId).first() }.single().layers.sumOf { it.entityCount }
            drawn shouldBe result.summary.importedTotal

            val total = opened.duration + indexed.duration + imported.duration
            println("S048 leer y recortar un GeoPackage de 100 MB: ${total.inWholeMilliseconds} ms")
            (total.inWholeSeconds < 20) shouldBe true
        }
    }

    private companion object {
        const val VERTICES = 6
        const val COLUMNS = 800
        const val NAME_PADDING = 64
        const val ROWS = 430_000

        init {
            check(CadChunkWriter.MAX_ENTITY_BYTES > 0)
        }
    }
}
