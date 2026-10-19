package es.topophone.receiver.session

import es.topophone.core.gnss.nmea.Constellation
import es.topophone.core.gnss.nmea.Dop
import es.topophone.core.gnss.nmea.NmeaChecksum
import es.topophone.core.gnss.signal.Band
import es.topophone.core.gnss.signal.SatelliteId
import es.topophone.core.gnss.signal.SignalKey
import es.topophone.core.model.point.BaseSource
import es.topophone.core.ntrip.NtripConfig
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.survey.BaseState
import es.topophone.receiver.transport.ReceiverTransport
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test

/** La base, la SNR de la base y la vista del cielo en el estado de la sesión (S015/RF-3, RF-8, RF-11, RF-12, RF-17). */
class ReceiverSessionBaseTest {

    private fun nmea(body: String) = "$" + body + "*" + "%02X".format(NmeaChecksum.compute(body)) + "\r\n"

    private fun gga(time: String) = nmea("GNGGA,$time,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001")

    private fun ebp(latitudeMinutes: String) = nmea("GNEBP,40$latitudeMinutes,N,00342.0000000,W,700.000,M")

    private class Link : ReceiverTransport {
        private val toApp = PipedOutputStream()
        override val description = "reach-RS3"
        override fun open() = Unit
        override val input: InputStream = PipedInputStream(toApp, 64 * 1024)
        override val output: OutputStream = ByteArrayOutputStream()
        fun send(vararg lines: String) = toApp.apply {
            lines.forEach { write(it.toByteArray()) }
            flush()
        }
        override fun close() {
            runCatching { toApp.close() }
            runCatching { input.close() }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val config = NtripConfig("caster.es", 2101, "AVL13M", "anonimo", "anonimo", sendGga = false)
    private val link = Link()
    private val blocks = Channel<ByteArray>(Channel.UNLIMITED)
    private val connector = NtripConnector { _, _, onConnected, onCorrections ->
        onConnected()
        for (block in blocks) onCorrections(block)
        awaitCancellation()
    }
    private val rtcm: ByteArray by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
        File(root, "fixtures/rtcm/rgapa-AVL13M.rtcm3").readBytes()
    }

    @After
    fun tearDown() = scope.cancel()

    private fun ReceiverSession.await(predicate: (ReceiverSnapshot) -> Boolean): ReceiverSnapshot =
        runBlocking { withTimeout(5_000) { state.first(predicate) } }

    private fun session(): ReceiverSession {
        val session = ReceiverSession(scope, connector)
        session.connect(link)
        link.send(gga("101530.00"))
        session.await { it.connection is ConnectionState.Receiving }
        return session
    }

    @Test
    fun `S015 RF-12 las correcciones reales dan la base de la estacion 6 y la SNR de la base`() {
        val session = session()
        session.startNtrip(config)

        rtcm.toList().chunked(700).forEach { blocks.trySend(it.toByteArray()) }

        val base = session.await { it.base.position != null && it.base.snr.size > 100 }.base
        val position = base.position.shouldNotBeNull()
        position.source shouldBe BaseSource.CORRECTIONS
        position.stationId shouldBe 6
        position.latitudeDeg shouldBe (43.5578666032 plusOrMinus 1e-9)
        position.longitudeDeg shouldBe (-5.9130704113 plusOrMinus 1e-9)
        base.snrAvailable shouldBe true
        base.snr shouldContainKey SignalKey(SatelliteId(Constellation.GPS, 5), Band.L1)
        base.snr shouldContainKey SignalKey(SatelliteId(Constellation.BEIDOU, 6), Band.B2A)
    }

    @Test
    fun `S015 RF-11 una EBP del receptor pasa a ser la fuente de la base`() {
        val session = session()
        session.startNtrip(config)
        rtcm.toList().chunked(700).forEach { blocks.trySend(it.toByteArray()) }
        session.await { it.base.position?.source == BaseSource.CORRECTIONS }

        link.send(ebp("25.0000000"), gga("101531.00"))

        val position = session.await { it.base.position?.source == BaseSource.RECEIVER }.base.position!!
        position.latitudeDeg shouldBe (40.4166666667 plusOrMinus 1e-9)
        position.ellipsoidalHeightM shouldBe 700.0
    }

    @Test
    fun `S015 RF-17 cada epoca sale con la base que habia al emitirla`() {
        val session = session()
        val changeAtEpoch = CopyOnWriteArrayList<Int>()
        val ready = Channel<Unit>(1)
        scope.launch {
            session.epochs.onSubscription { ready.send(Unit) }.collect { (epoch, base) ->
                if (epoch.basePosition != null) changeAtEpoch += base.change?.count ?: 0
            }
        }
        runBlocking { ready.receive() }

        link.send(ebp("25.0000000"), gga("101531.00"))
        link.send(ebp("25.0010000"), gga("101532.00")) // 1,85 m más al norte

        runBlocking { withTimeout(5_000) { while (changeAtEpoch.size < 2) kotlinx.coroutines.delay(10) } }
        changeAtEpoch shouldBe listOf(0, 1)
    }

    @Test
    fun `S015 RF-3 la vista del cielo se conserva en las epocas sin GSA ni GSV`() {
        val session = session()

        link.send(
            nmea("GPGSA,A,3,05,12,,,,,,,,,,,1.2,0.5,1.0,1"),
            nmea("GPGSV,1,1,02,05,40,185,35,12,15,084,42,1"),
            gga("101531.00"),
        )
        val sky = session.await { it.sky != null }.sky!!
        link.send(gga("101531.20"), gga("101531.40"))
        session.await { it.epoch?.utcMillisOfDay == 36_931_400 } // 10:15:31,40

        session.state.value.sky shouldBeSameInstanceAs sky
        sky.dop shouldBe Dop(1.2, 0.5, 1.0)
        sky.used shouldBe mapOf(Constellation.GPS to setOf(5, 12))
    }

    @Test
    fun `S015 RF-17 desconectar olvida la base y la vista del cielo`() {
        val session = session()
        link.send(ebp("25.0000000"), gga("101531.00"))
        session.await { it.base.position != null }

        session.disconnect()

        session.state.value.base shouldBe BaseState.NONE
        session.state.value.sky.shouldBeNull()
    }
}
