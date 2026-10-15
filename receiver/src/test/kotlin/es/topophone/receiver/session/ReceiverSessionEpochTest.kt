package es.topophone.receiver.session

import es.topophone.core.gnss.nmea.NmeaChecksum
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.receiver.transport.StreamTransport
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test

/** La sesión agrupa en épocas y detecta los datos obsoletos (S003/RF-8, S003/RF-12). */
class ReceiverSessionEpochTest {

    private fun nmea(body: String) = "$" + body + "*" + "%02X".format(NmeaChecksum.compute(body)) + "\r\n"

    private fun gga(time: String) = nmea("GNGGA,$time,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val toApp = PipedOutputStream()
    private val transport = StreamTransport("RS3 de prueba", PipedInputStream(toApp, 64 * 1024), ByteArrayOutputStream())
    private val noNtrip = NtripConnector { _, _, _, _ -> NtripFailure.CONNECTION_LOST }

    @After
    fun tearDown() = scope.cancel()

    private fun send(text: String) {
        toApp.write(text.toByteArray(Charsets.US_ASCII))
        toApp.flush()
    }

    private fun ReceiverSession.await(predicate: (ReceiverSnapshot) -> Boolean): ReceiverSnapshot =
        runBlocking { withTimeout(5_000) { state.first(predicate) } }

    @Test
    fun `S003 RF-8 la sesion publica la ultima epoca completa con su fecha y sus satelites en vista`() {
        val session = ReceiverSession(scope, noNtrip)
        session.connect(transport)

        send(
            gga("101530.00") +
                nmea("GPGSA,A,3,05,12,13,,,,,,,,,,1.2,0.6,1.0,1") +
                nmea("GPGSV,1,1,04,05,62,045,48,12,35,301,44,13,22,089,40,15,10,190,35,1") +
                nmea("GNZDA,101530.00,15,10,2026,00,00"),
        )

        val epoch = session.await { it.epoch != null }.epoch.shouldNotBeNull()
        epoch.utcMillisOfDay shouldBe 36_930_000
        epoch.date shouldBe LocalDate.of(2026, 10, 15)
        epoch.satellitesUsed shouldBe 3
        epoch.satellitesInView shouldBe 4
    }

    @Test
    fun `S003 RF-8 la posicion de la GGA se publica al momento, sin esperar a cerrar la epoca`() {
        val session = ReceiverSession(scope, noNtrip)
        session.connect(transport)

        send(gga("101530.00"))

        val snapshot = session.await { it.gga != null }
        snapshot.epoch.shouldBeNull()
    }

    @Test
    fun `S003 RF-12 si pasan mas de 2 s sin epoca se pasa a Sin datos`() {
        val session = ReceiverSession(scope, noNtrip)
        session.connect(transport)
        send(gga("101530.00"))
        val lastEpochAt = session.await { it.epoch != null }.epoch.shouldNotBeNull().completedAtMs

        session.await { it.connection is ConnectionState.NoData }
        val elapsed = System.nanoTime() / 1_000_000 - lastEpochAt

        ReceiverSession.NO_DATA_TIMEOUT_MS shouldBe 2_000L
        elapsed shouldBeGreaterThanOrEqual 2_000L
        elapsed shouldBeLessThan 2_600L
    }

    @Test
    fun `S003 RF-12 frases sin hora que siguen llegando no evitan el Sin datos`() {
        val session = ReceiverSession(scope, noNtrip, noDataTimeoutMs = 300)
        session.connect(transport)
        send(gga("101530.00"))
        session.await { it.epoch != null }

        repeat(10) {
            send(nmea("GPGSV,1,1,01,05,62,045,48,1"))
            Thread.sleep(50)
        }

        session.await { it.connection is ConnectionState.NoData }
    }

    @Test
    fun `S003 RF-12 con Sin datos la siguiente epoca vuelve a Recibiendo`() {
        val session = ReceiverSession(scope, noNtrip, noDataTimeoutMs = 300)
        session.connect(transport)
        send(gga("101530.00"))
        session.await { it.connection is ConnectionState.NoData }

        send(gga("101531.00"))

        session.await { it.connection is ConnectionState.Receiving }.epoch.shouldNotBeNull().utcMillisOfDay shouldBe 36_931_000
        session.state.value.connection.shouldBeInstanceOf<ConnectionState.Receiving>()
    }
}
