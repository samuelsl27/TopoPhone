package es.topophone.data.kml

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.kml.KmlFileKind
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.cad.ImportArea
import es.topophone.core.model.project.CentralPoint
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.cad.RoomCadRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.gpkg.GeoPackageImporter
import es.topophone.data.gpkg.GeoPackageScan
import es.topophone.data.gpkg.GpkgImportOptions
import es.topophone.data.gpkg.GpkgImportResult
import es.topophone.data.gpkg.GpkgLayerChoice
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.BufferedWriter
import java.io.File
import java.util.Locale
import kotlin.time.measureTimedValue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Un KML sintético de unos 100 MB (unas 205.000 líneas con atributos) se lee por flujo y se recorta a la zona en menos de 30 s y cambiar el
 * radio recuenta en menos de 2 s (S031, RNF). Robolectric sirve de cota; la medida en el móvil de referencia es de la demo.
 */
@RunWith(AndroidJUnit4::class)
class KmlPerformanceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val cad = RoomCadRepository(db)
    private val crs = ProjectCrs.ETRS89_UTM30
    private val center = CentralPoint(40.4168, -3.7038)

    @After
    fun close() = db.close()

    /** Un KML de [rows] marcas con una línea de 6 vértices cada una, sobre una malla de unos 25 m de paso alrededor de Madrid. */
    private fun build(file: File, rows: Int) {
        val noise = java.util.Random(7)
        BufferedWriter(file.writer(Charsets.UTF_8), 1 shl 20).use { out ->
            out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            out.write("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>Obra</name>")
            out.write("<Style id=\"s\"><LineStyle><color>ff0000ff</color></LineStyle></Style><Folder><name>Tramos</name>")
            for (i in 0 until rows) {
                val lon0 = -3.80 + (i % COLUMNS) * 0.0003
                val lat0 = 40.38 + (i / COLUMNS) * 0.0002
                out.write("<Placemark><name>Tramo $i</name><description>Tramo de prueba número $i</description><styleUrl>#s</styleUrl>")
                out.write("<ExtendedData><Data name=\"codigo\"><value>$i</value></Data>")
                out.write("<Data name=\"tipo\"><value>Red</value></Data></ExtendedData>")
                out.write("<LineString><coordinates>")
                for (v in 0 until VERTICES) {
                    // Cada vértice con sus propios decimales: las coordenadas de un levantamiento real no se comprimen bien.
                    val lon = lon0 + v * 0.00004 + noise.nextDouble() * 0.000005
                    val lat = lat0 + (v % 2) * 0.00005 + noise.nextDouble() * 0.000005
                    out.write(String.format(Locale.ROOT, "%.9f,%.9f ", lon, lat))
                }
                out.write("</coordinates></LineString></Placemark>\n")
            }
            out.write("</Folder></Document></kml>")
        }
    }

    @Test
    fun `S031 RNF un KML de 100 MB se lee y se recorta en menos de 30 s y el radio recuenta en menos de 2 s`() {
        val file = folder.newFile("obra.kml")
        val built = measureTimedValue { build(file, ROWS) }
        println("S031 generación de ${file.length() / (1024 * 1024)} MB: ${built.duration.inWholeMilliseconds} ms, $ROWS marcas")
        (file.length() > 80L * 1024 * 1024) shouldBe true
        (file.length() < KmlFileKind.MAX_FILE_BYTES) shouldBe true
        val projectId = runBlocking {
            RoomProjectRepository(db).create(ProjectDefaults().toDraft().copy(name = "Obra grande", crs = crs))
                .shouldBeInstanceOf<SaveResult.Saved>().id
        }

        val reader = KmlReader(folder.newFolder("cache"))
        val opened = measureTimedValue {
            runBlocking { reader.open(KmlInput("obra.kml", file.length()) { file.inputStream() }, GeodeticDatum.ETRS89) }
        }
        val session = opened.value.shouldBeInstanceOf<KmlOpenResult.Opened>().session
        session.use {
            val layer = session.layers.single()
            layer.featureCount shouldBe ROWS.toLong()
            val scan = GeoPackageScan(session)
            val indexed = measureTimedValue { runBlocking { scan.index(layer) } }
            indexed.value.size shouldBe ROWS
            val source = CadSourceCrs.Geographic(GeodeticDatum.ETRS89)

            // Cambiar el radio recuenta sobre las envolventes en memoria: menos de 2 s cada vez.
            val times = listOf(1_000.0, 2_000.0, 3_000.0, 500.0).map { radius ->
                val counted =
                    measureTimedValue { runBlocking { scan.count(indexed.value, source, crs, ImportArea.Circle(center, radius)) } }
                val millis = counted.duration.inWholeMilliseconds
                println("S031 recuento con radio de ${radius.toInt()} m: ${counted.value.inZone} objetos en $millis ms")
                (counted.value.inZone > 0) shouldBe true
                counted.duration.inWholeMilliseconds
            }
            times.forEach { (it < 2_000) shouldBe true }

            val importer = GeoPackageImporter(cad)
            val area = ImportArea.Circle(center, 3_000.0)
            val imported = measureTimedValue {
                runBlocking {
                    importer.import(
                        projectId,
                        crs,
                        "obra.kml",
                        GpkgImportOptions(listOf(GpkgLayerChoice(layer, source, "name", wgs84 = true)), false, area),
                        session,
                    )
                }
            }
            println(
                "S031 lectura y recorte: abrir ${opened.duration.inWholeMilliseconds} ms, " +
                    "índice ${indexed.duration.inWholeMilliseconds} ms, importar ${imported.duration.inWholeMilliseconds} ms",
            )
            val result = imported.value.shouldBeInstanceOf<GpkgImportResult.Imported>()
            (result.summary.importedTotal in 1 until ROWS) shouldBe true

            val total = opened.duration + indexed.duration + imported.duration
            println("S031 leer y recortar un KML de 100 MB: ${total.inWholeMilliseconds} ms")
            (total.inWholeSeconds < 30) shouldBe true
        }
    }

    private companion object {
        const val VERTICES = 6
        const val COLUMNS = 700
        const val ROWS = 205_000
    }
}
