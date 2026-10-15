package es.topophone.core.gnss.nmea

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import org.junit.jupiter.api.Test

/** Agrupación de frases en épocas por hora UTC (S003/RF-7 a S003/RF-11). */
class EpochAssemblerTest {

    private val assembler = EpochAssembler()
    private var now = 0L
    private val completed = mutableListOf<Epoch>()

    private fun nmea(body: String) =
        NmeaFramer().feed(("$" + body + "*" + "%02X".format(NmeaChecksum.compute(body)) + "\r\n").toByteArray()).single()

    /** Envía las frases con 2 ms entre ellas, como una ráfaga del receptor. */
    private fun burst(vararg bodies: String) {
        bodies.forEach { body ->
            assembler.feed(nmea(body), now)?.let(completed::add)
            now += 2
        }
    }

    private fun silence(ms: Long) {
        now += ms
        assembler.tick(now)?.let(completed::add)
    }

    private fun gga(time: String) = "GNGGA,$time,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001"

    private fun etc(time: String) = "GNETC,$time,30,0,123.40,210.50,15.25,1.10,1.20,0.30"

    @Test
    fun `S003 RF-8 las frases de una rafaga forman una epoca con las de sin hora incluidas`() {
        burst(
            gga("101530.00"),
            etc("101530.00"),
            "GPGSA,A,3,05,12,13,15,,,,,,,,,1.2,0.6,1.0,1",
            "GLGSA,A,3,70,71,,,,,,,,,,,1.2,0.6,1.0,2",
            "GPGSV,2,1,07,05,62,045,48,12,35,301,44,13,22,089,40,15,10,190,35,1",
            "GPGSV,2,2,07,18,05,150,30,20,40,060,42,24,15,320,30,1",
            "GLGSV,1,1,03,70,50,100,41,71,20,200,38,72,10,300,30,1",
            "GNRMC,101530.00,A,4025.0080000,N,00342.2228000,W,0.025,123.4,151026,,,R,V",
            "GNVTG,123.4,T,,M,0.025,N,0.046,K,R",
            "GNZDA,101530.00,15,10,2026,00,00",
            "GNEBP,4025.0000000,N,00342.0000000,W,700.000,M",
            "GNGST,101530.00,0.012,0.010,0.007,45.0,0.008,0.007,0.015",
        )
        silence(150)

        val epoch = completed.single()
        epoch.utcMillisOfDay shouldBe 36_930_000
        epoch.date shouldBe LocalDate.of(2026, 10, 15)
        epoch.gga.shouldNotBeNull().satellites shouldBe 24
        epoch.gst.shouldNotBeNull()
        epoch.rmc.shouldNotBeNull()
        epoch.vtg.shouldNotBeNull()
        epoch.zda.shouldNotBeNull()
        epoch.basePosition.shouldNotBeNull()
        epoch.tilt.shouldNotBeNull().state shouldBe TiltState.COMPENSATING
        epoch.gsa.keys shouldBe setOf(Constellation.GPS, Constellation.GLONASS)
        epoch.satellitesUsed shouldBe 6
        epoch.satellitesInView shouldBe 10
        epoch.completedAtMs shouldBe now
    }

    @Test
    fun `S003 RF-9 a 5 Hz cada epoca se completa al llegar otra hora y la ultima tras 150 ms de silencio`() {
        listOf("101530.00", "101530.20", "101530.40").forEach { time ->
            burst(gga(time), etc(time))
            silence(196)
        }
        completed.map { it.utcMillisOfDay } shouldBe listOf(36_930_000, 36_930_200, 36_930_400)
    }

    @Test
    fun `S003 RF-9 una frase con otra hora completa la epoca en curso al momento`() {
        burst(gga("101530.00"))
        burst(gga("101530.20"))

        completed.single().utcMillisOfDay shouldBe 36_930_000
    }

    @Test
    fun `S003 RF-9 antes de 150 ms de silencio la epoca sigue abierta`() {
        burst(gga("101530.00"))
        silence(147)
        completed.shouldBeEmpty()
        silence(1)
        completed.size shouldBe 1
    }

    @Test
    fun `S003 RF-10 una frase con una hora anterior a la ultima epoca completa se descarta`() {
        burst(gga("101530.20"))
        silence(200)
        burst(gga("101530.00"), gga("101530.20"))
        silence(200)

        completed.map { it.utcMillisOfDay } shouldBe listOf(36_930_200)
    }

    @Test
    fun `S003 RF-10 S003 RF-11 el paso de medianoche no se descarta y la fecha avanza un dia`() {
        burst(gga("235959.80"), "GNZDA,235959.80,31,12,2026,00,00")
        silence(200)
        burst(gga("000000.00"))
        silence(200)

        completed.map { it.utcMillisOfDay } shouldBe listOf(86_399_800, 0)
        completed.map { it.date } shouldBe listOf(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 1))
    }

    @Test
    fun `S003 RF-11 sin RMC ni ZDA la epoca no tiene fecha y despues hereda la ultima`() {
        burst(gga("101530.00"))
        silence(200)
        burst(gga("101530.20"), "GNZDA,101530.20,15,10,2026,00,00")
        silence(200)
        burst(gga("101530.40"))
        silence(200)

        completed.map { it.date } shouldBe listOf(null, LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 15))
    }

    @Test
    fun `S003 RF-10 al reiniciar el bucle de una reproduccion vuelven a valer las horas anteriores`() {
        burst(gga("101540.00"), "GNZDA,101540.00,15,10,2026,00,00")
        silence(200)
        assembler.reset()
        burst(gga("101530.00"))
        silence(200)

        completed.map { it.utcMillisOfDay } shouldBe listOf(36_940_000, 36_930_000)
        completed.last().date.shouldBeNull()
    }

    @Test
    fun `S003 RF-7 una frase corrupta no entra en la epoca y no estropea el resto`() {
        burst(gga("101530.00"), "GPGSA,A,4,05,12,,,,,,,,,,,1.2,0.6,1.0,1", "GPGSA,A,3,05,12,,,,,,,,,,,1.2,0.6,1.0,1")
        silence(150)

        completed.single().gsa.getValue(Constellation.GPS).mode shouldBe FixMode.FIX_3D
    }

    @Test
    fun `S003 RF-8 sin frases con hora no se completa ninguna epoca`() {
        burst("GPGSV,1,1,01,05,62,045,48,1")
        silence(500)

        completed.shouldBeEmpty()
    }
}
