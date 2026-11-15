package es.topophone.receiver.transport

import es.topophone.core.gnss.nmea.GgaParser
import es.topophone.core.gnss.nmea.GstParser
import es.topophone.core.gnss.nmea.NmeaFramer
import es.topophone.core.gnss.nmea.NmeaSentence
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.gnss.nmea.ZdaParser
import es.topophone.core.model.point.PositionSource
import es.topophone.core.ntrip.NtripConfig
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.NtripState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.session.ReceiverSnapshot
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test

/** El GNSS del móvil como receptor: siempre SINGLE, altura elipsoidal, precisión de Android y sin correcciones (S022/RF-5 a RF-9, RF-20). */
class PhoneGnssTransportTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connector = NtripConnector { _, _, _, _ -> NtripFailure.CONNECTION_LOST }

    @After
    fun tearDown() = scope.cancel()

    private class FakeLocation(var permission: Boolean = true, var enabled: Boolean = true) : LocationSource {
        var started = false
        var stops = 0
        private var callback: ((LocationFix) -> Unit)? = null

        override fun hasPermission() = permission

        override fun isEnabled() = enabled

        override fun start(onFix: (LocationFix) -> Unit) {
            started = true
            callback = onFix
        }

        override fun stop() {
            stops++
        }

        fun emit(fix: LocationFix) = callback!!.invoke(fix)
    }

    // 2026-11-15 10:00:03.450 UTC
    private val t0 = 1_794_736_803_450L

    private fun fix(
        timeMs: Long = t0,
        height: Double? = 123.456,
        horizontal: Double? = 1.0,
        vertical: Double? = null,
        mock: Boolean = false,
        satellites: Int? = null,
    ) = LocationFix(43.2630126, -2.9349852, height, horizontal, vertical, timeMs, mock, satellites)

    private fun open(location: FakeLocation = FakeLocation()): PhoneGnssTransport =
        PhoneGnssTransport("GNSS del móvil", location).also { it.open() }

    /** Lo que ha llegado hasta ahora (la entrada entrega lo que hay y no espera más). */
    private fun PhoneGnssTransport.sentences(): List<NmeaSentence> {
        val buffer = ByteArray(8192)
        val count = input.read(buffer)
        return NmeaFramer().feed(buffer, 0, count)
    }

    @Test
    fun `S022 RF-2 sin permiso de ubicacion o con la ubicacion apagada no se abre y no se empieza a leer`() {
        val noPermission = FakeLocation(permission = false)
        shouldThrow<PhonePermissionException> { PhoneGnssTransport("GNSS del móvil", noPermission).open() }
        noPermission.started shouldBe false

        val off = FakeLocation(enabled = false)
        shouldThrow<LocationDisabledException> { PhoneGnssTransport("GNSS del móvil", off).open() }
        off.started shouldBe false
    }

    @Test
    fun `S022 RF-5 aunque Android declare un metro de precision la solucion es SINGLE con esa precision horizontal`() {
        val location = FakeLocation()
        val transport = open(location)

        location.emit(fix(horizontal = 1.0))
        val sentences = transport.sentences()

        val gga = sentences.firstNotNullOf { GgaParser.parse(it) }
        gga.status shouldBe SolutionStatus.SINGLE
        gga.hdop.shouldBeNull()
        gga.correctionAgeS.shouldBeNull()
        val gst = sentences.firstNotNullOf { GstParser.parse(it) }
        // Las sigmas se escriben al milímetro: 1 m / √2 = 0,707 en cada eje.
        gst.horizontalM.shouldNotBeNull() shouldBe (1.0 plusOrMinus 1e-3)
    }

    @Test
    fun `S022 RF-5 la precision vertical solo sale si Android la da`() {
        val location = FakeLocation()
        val transport = open(location)

        location.emit(fix(vertical = null))
        transport.sentences().firstNotNullOf { GstParser.parse(it) }.verticalM.shouldBeNull()

        location.emit(fix(timeMs = t0 + 1_000, vertical = 2.5))
        transport.sentences().firstNotNullOf { GstParser.parse(it) }.verticalM shouldBe 2.5
    }

    @Test
    fun `S022 RF-5 sin precision horizontal de Android la GST no lleva sigmas`() {
        val location = FakeLocation()
        val transport = open(location)

        location.emit(fix(horizontal = null))

        val gst = transport.sentences().firstNotNullOf { GstParser.parse(it) }
        gst.sigmaLatitudeM.shouldBeNull()
        gst.sigmaLongitudeM.shouldBeNull()
    }

    @Test
    fun `S022 RF-6 la altura es la elipsoidal de Android sin separacion del geoide`() {
        val location = FakeLocation()
        val transport = open(location)

        location.emit(fix(height = 123.456))
        val gga = transport.sentences().firstNotNullOf { GgaParser.parse(it) }

        gga.altitudeM shouldBe 123.456
        gga.geoidSeparationM shouldBe 0.0
        gga.latitudeDeg shouldBe (43.2630126 plusOrMinus 1e-8)
        gga.longitudeDeg shouldBe (-2.9349852 plusOrMinus 1e-8)
    }

    @Test
    fun `S022 RF-6 sin altura de Android no hay altitud ni geoide`() {
        val location = FakeLocation()
        val transport = open(location)

        location.emit(fix(height = null))

        val gga = transport.sentences().firstNotNullOf { GgaParser.parse(it) }
        gga.altitudeM.shouldBeNull()
        gga.geoidSeparationM.shouldBeNull()
    }

    @Test
    fun `S022 RF-5 la hora y la fecha de las frases son las de la posicion`() {
        val location = FakeLocation()
        val transport = open(location)

        location.emit(fix(timeMs = t0, satellites = 9))
        val sentences = transport.sentences()

        val gga = sentences.firstNotNullOf { GgaParser.parse(it) }
        gga.utcTime shouldBe "100003.45"
        gga.satellites shouldBe 9
        val zda = sentences.firstNotNullOf { ZdaParser.parse(it) }
        zda.date shouldBe LocalDate.of(2026, 11, 15)
        zda.utcMillisOfDay shouldBe 10 * 3_600_000 + 3_450
    }

    @Test
    fun `S022 RF-7 una posicion simulada por otra app cambia la fuente y la siguiente real la devuelve`() {
        val location = FakeLocation()
        val transport = open(location)
        transport.positionSource shouldBe PositionSource.PHONE

        location.emit(fix(mock = true))
        transport.positionSource shouldBe PositionSource.PHONE_MOCK

        location.emit(fix(timeMs = t0 + 1_000, mock = false))
        transport.positionSource shouldBe PositionSource.PHONE
    }

    @Test
    fun `S022 RF-9 el movil no admite correcciones, no graba, no es simulado y espera 3 s antes de Sin datos`() {
        val transport = open()

        transport.acceptsCorrections shouldBe false
        transport.recordsSession shouldBe false
        transport.simulated shouldBe false
        transport.noDataTimeoutMs shouldBe 3_000L
        transport.output.write(byteArrayOf(1, 2, 3)) // se descarta sin fallar
    }

    @Test
    fun `S022 RF-4 cerrar el transporte para la lectura del sistema y termina una lectura en espera`() {
        val location = FakeLocation()
        val transport = open(location)
        var result = 0
        val reader = thread { result = transport.input.read(ByteArray(64)) }
        Thread.sleep(100)

        transport.close()
        reader.join(2_000)

        reader.isAlive shouldBe false
        result shouldBe -1
        location.stops shouldBe 1
    }

    private fun ReceiverSession.await(predicate: (ReceiverSnapshot) -> Boolean): ReceiverSnapshot =
        runBlocking { withTimeout(10_000) { state.first(predicate) } }

    @Test
    fun `S022 RF-5 la sesion lee el movil como un receptor SINGLE y no abre el caster`() {
        val location = FakeLocation()
        val transport = PhoneGnssTransport("GNSS del móvil", location)
        val session = ReceiverSession(scope, connector)
        session.connect(transport)
        runBlocking { withTimeout(10_000) { while (!location.started) delay(10) } } // la sesión abre el transporte en segundo plano

        location.emit(fix(timeMs = t0))
        location.emit(fix(timeMs = t0 + 1_000))
        val snapshot = session.await { it.epoch != null }

        snapshot.gga.shouldNotBeNull().status shouldBe SolutionStatus.SINGLE
        snapshot.source shouldBe PositionSource.PHONE
        snapshot.recording shouldBe false
        session.startNtrip(NtripConfig("caster.es", 2101, "VRS3", "topo", ""))
        session.state.value.ntrip shouldBe NtripState.UnavailableForSource(PositionSource.PHONE)

        location.emit(fix(timeMs = t0 + 2_000, mock = true))
        location.emit(fix(timeMs = t0 + 3_000, mock = true))
        session.await { it.source == PositionSource.PHONE_MOCK }
    }

    @Test
    fun `S022 RF-8 a los 3 s sin ninguna posicion la sesion pasa a Sin datos y antes no`() {
        val now = AtomicLong(0)
        val session = ReceiverSession(scope, connector, noDataTimeoutMs = 2_000, watchdogPeriodMs = 10, clock = { now.get() })
        session.connect(PhoneGnssTransport("GNSS del móvil", FakeLocation()))
        Thread.sleep(100)

        now.set(2_500)
        Thread.sleep(200)
        (session.state.value.connection is ConnectionState.NoData) shouldBe false

        now.set(3_500)
        session.await { it.connection is ConnectionState.NoData }
    }
}
