package es.topophone.data.point

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Las frases NMEA de un promediado largo se guardan con el punto, en tramos que caben en el cursor (S017/RF-12; plan A6). */
@RunWith(AndroidJUnit4::class)
class PointRepositoryNmeaTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db, clock = { 50_000 })

    @After
    fun close() = db.close()

    private val project = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    /** Las frases de [epochs] épocas a 10 Hz: GGA, GST y ETC, de unos 220 caracteres cada época. */
    private fun sentences(epochs: Int): String = (0 until epochs).joinToString("\n") {
        "\$GNGGA,10$it,4025.0080000,N,00342.2280000,W,4,28,0.5,650.123,M,51.234,M,1.0,0001*41\n" +
            "\$GNGST,10$it,0.5,0.01,0.01,0.0,0.004,0.003,0.007*5D\n" +
            "\$GNETC,10$it,30,0,12.345,168.000,123.400,1.013,0.993,1.021,0.000*2F"
    }

    private fun point(name: String, raw: String, id: String = "id-$name") = Point.measured(
        id = id,
        projectId = project,
        name = name,
        description = "",
        measurement = Measurement(
            antenna = GeodeticPosition(40.41668, -3.70371, 701.357),
            poleHeightM = 1.8,
            quality = PointQuality("FIX", 24, 0.6, 1.0, 0.009, 0.014, MeasurementMode.AVERAGED, 36_000, 0, fromReplay = false),
            rawNmea = raw,
            outOfTolerance = emptySet(),
            measuredFromMs = 1_000,
            measuredToMs = 3_601_000,
            coordinates = PointCoordinates(GeodeticPosition(40.41668, -3.70371, 699.423), 440_297.988, 4_474_244.001, 648.311),
        ),
        createdAtMs = 1_000,
    )

    private fun count(sql: String): Int = db.openHelper.readableDatabase.query(sql).use {
        it.moveToFirst() shouldBe true
        it.getInt(0)
    }

    @Test
    fun `S017 RF-12 un punto de unos 8 MB se guarda de una vez en menos de 3 s, en tramos de 256000 caracteres como mucho`() {
        val raw = sentences(36_000)
        raw.length shouldBeGreaterThan 7_000_000

        val millis = measureTimeMillis { runBlocking { points.create(point("L", raw)) shouldBe PointSaveResult.Saved } }

        millis shouldBeLessThan 3_000L
        val chunks = count("SELECT nmea_chunks FROM points WHERE id = 'id-L'")
        chunks shouldBeGreaterThan 28
        count("SELECT LENGTH(raw_nmea) FROM points WHERE id = 'id-L'") shouldBe 0
        count("SELECT COUNT(*) FROM point_nmea WHERE point_id = 'id-L'") shouldBe chunks
        count("SELECT MAX(LENGTH(text)) FROM point_nmea") shouldBeLessThanOrEqual NmeaChunks.MAX_CHARS
        count("SELECT MAX(seq) FROM point_nmea") shouldBe chunks - 1
        // La fila del punto cabe sobrada en la ventana del cursor de Android (unos 2 MB).
        count("SELECT MAX(LENGTH(raw_nmea)) FROM points") shouldBeLessThanOrEqual NmeaChunks.MAX_CHARS
    }

    @Test
    fun `S017 RF-12 el punto largo se lee sin las frases y los tramos devuelven el texto exacto`() {
        val raw = sentences(8_000)
        runBlocking { points.create(point("L", raw)) }

        val read = runBlocking { points.point("id-L").first() }.shouldNotBeNull().measurement.shouldNotBeNull()
        val all = runBlocking { points.points(project).first() }.single().measurement.shouldNotBeNull()

        read.rawNmea shouldBe ""
        read.nmeaChunks shouldBeGreaterThan 1
        all.nmeaChunks shouldBe read.nmeaChunks
        val joined = runBlocking { (0 until read.nmeaChunks).map { points.nmeaChunk("id-L", it).shouldNotBeNull() } }.joinToString("")
        joined shouldBe raw
        runBlocking { points.nmeaChunk("id-L", read.nmeaChunks) }.shouldBeNull()
        runBlocking { points.nmeaChunk("no-existe", 0) }.shouldBeNull()
    }

    @Test
    fun `S017 RF-12 un punto con pocas frases las deja en su fila y sin tramos`() {
        val raw = sentences(600) // un minuto a 10 Hz: unos 200.000 caracteres
        runBlocking { points.create(point("C", raw)) }

        val read = runBlocking { points.point("id-C").first() }.shouldNotBeNull().measurement.shouldNotBeNull()

        read.rawNmea shouldBe raw
        read.nmeaChunks shouldBe 0
        count("SELECT COUNT(*) FROM point_nmea") shouldBe 0
        runBlocking { points.nmeaChunk("id-C", 0) }.shouldBeNull()
    }

    @Test
    fun `S017 RF-12 un nombre repetido no deja los tramos del segundo punto`() {
        val raw = sentences(8_000)
        runBlocking { points.create(point("L", raw)) }
        val chunks = count("SELECT COUNT(*) FROM point_nmea")

        val second = runBlocking { points.create(point("L", raw, id = "id-otro")) }

        second shouldBe PointSaveResult.DuplicateName
        count("SELECT COUNT(*) FROM points") shouldBe 1
        count("SELECT COUNT(*) FROM point_nmea") shouldBe chunks
    }

    @Test
    fun `S017 RF-12 la papelera conserva los tramos y borrar el punto para siempre los borra`() {
        val raw = sentences(8_000)
        runBlocking { points.create(point("L", raw)) }
        val chunks = count("SELECT COUNT(*) FROM point_nmea")

        runBlocking { points.moveToTrash(listOf("id-L")) }
        count("SELECT COUNT(*) FROM point_nmea") shouldBe chunks

        runBlocking { points.restore("id-L") shouldBe PointSaveResult.Saved }
        runBlocking { points.nmeaChunk("id-L", 0) }.shouldNotBeNull()

        runBlocking { points.moveToTrash(listOf("id-L")) }
        runBlocking { points.deletePermanently("id-L") }
        count("SELECT COUNT(*) FROM point_nmea") shouldBe 0
    }

    @Test
    fun `S017 RF-12 al purgar la papelera se van los tramos de los puntos viejos`() {
        runBlocking { points.create(point("L", sentences(8_000))) }
        runBlocking { points.moveToTrash(listOf("id-L")) }

        // El reloj del repositorio da 50.000 y el punto entró en la papelera en ese instante: 30 días después, sobra.
        val purged = runBlocking { RoomPointRepository(db, clock = { 50_000 + 31L * 24 * 3_600_000 }).purgeTrash() }

        purged shouldBe 1
        count("SELECT COUNT(*) FROM point_nmea") shouldBe 0
    }
}
