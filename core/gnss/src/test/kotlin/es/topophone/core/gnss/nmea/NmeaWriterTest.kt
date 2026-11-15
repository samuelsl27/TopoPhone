package es.topophone.core.gnss.nmea

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import org.junit.jupiter.api.Test

/** Las frases que escribe la app se leen con los mismos analizadores que las del receptor (S022/RF-15). */
class NmeaWriterTest {

    private fun parse(text: String): List<NmeaSentence> = NmeaFramer().feed(text.toByteArray(Charsets.US_ASCII))

    private fun one(text: String): NmeaSentence = parse(text).also { it shouldHaveSize 1 }.single()

    // 23:59:57.00
    private val utc = 23 * 3_600_000 + 59 * 60_000 + 57_000

    @Test
    fun `S022 RF-15 la GGA coincide byte a byte con la del fixture del receptor`() {
        val text = NmeaWriter.gga(utc, 40.0 + 25.008 / 60, -(3.0 + 42.228 / 60), SolutionStatus.FIX, 28, 0.5, 650.123, 51.234, 1.0, 1)

        text shouldBe "\$GNGGA,235957.00,4025.0080000,N,00342.2280000,W,4,28,0.5,650.123,M,51.234,M,1.0,0001*4F\r\n"
    }

    @Test
    fun `S022 RF-15 GST, GSA, GSV, ZDA, EBP y ETC coinciden con las del fixture`() {
        NmeaWriter.gst(utc, 0.008, 0.007, 0.015, 0.012, 0.010, 0.007, 45.0) shouldBe
            "\$GNGST,235957.00,0.012,0.010,0.007,45.0,0.008,0.007,0.015*79\r\n"
        NmeaWriter.gsa("GP", FixMode.FIX_3D, 1, listOf(5, 12, 13, 15, 18, 20, 24, 25), 1.2, 0.5, 1.0) shouldBe
            "\$GPGSA,A,3,05,12,13,15,18,20,24,25,,,,,1.2,0.5,1.0,1*22\r\n"
        NmeaWriter.zda(utc, LocalDate.of(2026, 10, 15)) shouldBe "\$GNZDA,235957.00,15,10,2026,00,00*74\r\n"
        NmeaWriter.ebp(BasePosition(40.0 + 25.0 / 60, -(3.0 + 42.0 / 60), 700.0)) shouldBe
            "\$GNEBP,4025.0000000,N,00342.0000000,W,700.000,M*15\r\n"
        NmeaWriter.etc(TiltData(utc, TiltState.ALIGNMENT, TiltNotification.NONE, Math.toRadians(123.4), null, null, null)) shouldBe
            "\$GNETC,235957.00,20,0,123.40,,,,,*7E\r\n"
    }

    @Test
    fun `S022 RF-15 un GSV de cinco satelites se parte en dos frases de cuatro y uno`() {
        val sats = listOf(5 to 35, 12 to 42, 13 to 43, 15 to 45, 18 to 48).map { (prn, snr) ->
            SatelliteView(prn, Math.toRadians(40.0), Math.toRadians(185.0), snr)
        }

        val lines = NmeaWriter.gsv("GP", sats, signalId = 1)

        lines shouldHaveSize 2
        val parsed = lines.map { GsvParser.parse(one(it)).shouldNotBeNull() }
        parsed.map { it.totalMessages } shouldBe listOf(2, 2)
        parsed.map { it.messageNumber } shouldBe listOf(1, 2)
        parsed.all { it.satellitesInView == 5 } shouldBe true
        parsed.flatMap { it.satellites }.map { it.prn } shouldBe listOf(5, 12, 13, 15, 18)
        parsed[0].satellites[0].elevationRad.shouldNotBeNull() shouldBe (Math.toRadians(40.0) plusOrMinus 1e-9)
        parsed[0].satellites[0].snrDbHz shouldBe 35
        parsed[0].signalId shouldBe 1
        lines.all { NmeaChecksum.isValid(it.trim()) } shouldBe true
    }

    @Test
    fun `S022 RF-15 sin satelites se escribe un GSV vacio que se lee`() {
        val data = GsvParser.parse(one(NmeaWriter.gsv("GL", emptyList(), 2).single())).shouldNotBeNull()

        data.satellites shouldHaveSize 0
        data.satellitesInView shouldBe 0
    }

    @Test
    fun `S022 RF-15 la GGA escrita se lee con las mismas coordenadas y estado`() {
        val lat = 39.46991234567891
        val lon = -0.37631234567891
        val text = NmeaWriter.gga(utc + 450, lat, lon, SolutionStatus.SINGLE, 9, null, 12.345, 0.0, null)
        val fix = GgaParser.parse(one(text)).shouldNotBeNull()

        fix.latitudeDeg shouldBe (lat plusOrMinus 1e-8)
        fix.longitudeDeg shouldBe (lon plusOrMinus 1e-8)
        fix.status shouldBe SolutionStatus.SINGLE
        fix.satellites shouldBe 9
        fix.hdop.shouldBeNull()
        fix.altitudeM shouldBe 12.345
        fix.geoidSeparationM shouldBe 0.0
        fix.correctionAgeS.shouldBeNull()
        fix.utcTime shouldBe "235957.45"
    }

    @Test
    fun `S022 RF-15 sin solucion la GGA no lleva posicion y se lee como sin posicion`() {
        val fix = GgaParser.parse(one(NmeaWriter.gga(utc, 40.0, -3.0, SolutionStatus.NONE, 0, null, null, null, null))).shouldNotBeNull()

        fix.status shouldBe SolutionStatus.NONE
        fix.hasPosition shouldBe false
    }

    @Test
    fun `S022 RF-15 las coordenadas del sur y del este llevan su hemisferio`() {
        val text = NmeaWriter.gga(utc, -33.8688, 151.2093, SolutionStatus.FIX, 20, 0.7, 10.0, 20.0, 1.0)
        val fix = GgaParser.parse(one(text)).shouldNotBeNull()

        text.contains(",S,") shouldBe true
        text.contains(",E,") shouldBe true
        fix.latitudeDeg shouldBe (-33.8688 plusOrMinus 1e-8)
        fix.longitudeDeg shouldBe (151.2093 plusOrMinus 1e-8)
    }

    @Test
    fun `S022 RF-15 el redondeo de los minutos no deja 60,0000000`() {
        // 39,99999999999 grados: los minutos redondean a 60 y deben llevar a 40 grados 00 minutos.
        val text = NmeaWriter.gga(utc, 39.99999999999, 0.0, SolutionStatus.FIX, 10, 1.0, 0.0, 0.0, 0.0)

        text.contains("4000.0000000,N") shouldBe true
    }

    @Test
    fun `S022 RF-15 la GST se lee con sus sigmas y la horizontal sale de las dos`() {
        val gst = GstParser.parse(one(NmeaWriter.gst(utc, 0.6, 0.8, null))).shouldNotBeNull()

        gst.sigmaLatitudeM shouldBe 0.6
        gst.sigmaLongitudeM shouldBe 0.8
        gst.sigmaAltitudeM.shouldBeNull()
        gst.horizontalM.shouldNotBeNull() shouldBe (1.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S022 RF-15 la GSA, la ZDA, la EBP y la ETC escritas se leen de vuelta`() {
        val gsa = GsaParser.parse(one(NmeaWriter.gsa("GA", FixMode.FIX_3D, 3, listOf(2, 7, 11), 1.5, 0.9, 1.2))).shouldNotBeNull()
        gsa.mode shouldBe FixMode.FIX_3D
        gsa.constellation shouldBe Constellation.GALILEO
        gsa.satellitesUsed shouldBe listOf(2, 7, 11)
        gsa.hdop shouldBe 0.9

        val zda = ZdaParser.parse(one(NmeaWriter.zda(utc + 120, LocalDate.of(2026, 1, 2)))).shouldNotBeNull()
        zda.date shouldBe LocalDate.of(2026, 1, 2)
        zda.utcMillisOfDay shouldBe utc + 120

        val base = BasePosition(43.2630126, -2.9349852, 123.456)
        val ebp = EbpParser.parse(one(NmeaWriter.ebp(base))).shouldNotBeNull()
        ebp.latitudeDeg shouldBe (base.latitudeDeg plusOrMinus 1e-8)
        ebp.longitudeDeg shouldBe (base.longitudeDeg plusOrMinus 1e-8)
        ebp.ellipsoidalHeightM shouldBe 123.456

        val tilt = TiltData(
            utc,
            TiltState.COMPENSATING,
            TiltNotification.FAST_MOTION,
            Math.toRadians(123.4),
            Math.toRadians(90.0),
            Math.toRadians(12.5),
            TiltAccuracyCoefficients(1.0, 1.013, 0.993),
        )
        val etc = EtcParser.parse(one(NmeaWriter.etc(tilt))).shouldNotBeNull()
        etc.state shouldBe TiltState.COMPENSATING
        etc.notification shouldBe TiltNotification.FAST_MOTION
        etc.headingRad.shouldNotBeNull() shouldBe (Math.toRadians(123.4) plusOrMinus 1e-6)
        etc.tiltRad.shouldNotBeNull() shouldBe (Math.toRadians(12.5) plusOrMinus 1e-6)
        etc.accuracyCoefficients shouldBe TiltAccuracyCoefficients(1.0, 1.013, 0.993)
    }

    @Test
    fun `S022 RF-15 la misma frase sale siempre con los mismos bytes`() {
        fun frame() = NmeaWriter.gga(utc, 43.2630126, -2.9349852, SolutionStatus.FLOAT, 18, 0.9, 40.1234, 51.234, 3.2)

        frame() shouldBe frame()
        frame().toByteArray(Charsets.US_ASCII).toList() shouldBe frame().toByteArray(Charsets.US_ASCII).toList()
    }

    @Test
    fun `S022 RF-15 la hora con centesimas y las horas limite se escriben bien`() {
        NmeaWriter.time(0) shouldBe "000000.00"
        NmeaWriter.time(86_399_990) shouldBe "235959.99"
        NmeaWriter.time(3_661_230) shouldBe "010101.23"
    }
}
