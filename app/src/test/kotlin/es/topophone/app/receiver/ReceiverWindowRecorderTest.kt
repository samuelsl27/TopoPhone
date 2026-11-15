package es.topophone.app.receiver

import es.topophone.app.ui.receiver.nmea
import es.topophone.core.gnss.nmea.ReplaySpeed
import es.topophone.core.gnss.scenario.ScenarioOrigin
import es.topophone.core.gnss.scenario.ScenarioPlayer
import es.topophone.core.gnss.scenario.Scenarios
import es.topophone.core.model.point.PositionSource
import es.topophone.core.ntrip.NtripConfig
import es.topophone.core.ntrip.NtripConnector
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ImportResult
import es.topophone.receiver.session.NtripState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.session.ReplayController
import es.topophone.receiver.session.SessionLogStore
import es.topophone.receiver.transport.StreamTransport
import es.topophone.receiver.transport.VirtualReceiverTransport
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThan as longGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** La ventana de 10 s: se llena con las épocas de la sesión, olvida lo viejo y se vacía al desconectar (S043/RF-5). */
class ReceiverWindowRecorderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var now = 100_000L
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recorderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Lo que el caster falso envía: cada elemento del canal es un bloque de correcciones. */
    private val blocks = Channel<ByteArray>(Channel.UNLIMITED)
    private val session = ReceiverSession(
        sessionScope,
        NtripConnector { _, _, onConnected, onCorrections ->
            onConnected()
            for (block in blocks) onCorrections(block)
            awaitCancellation()
        },
        noDataTimeoutMs = 600_000,
        watchdogPeriodMs = 1_000,
        clock = { now },
    )
    private val recorder = ReceiverWindowRecorder(session, recorderScope, clock = { now }, photoPeriodMs = 50)
    private val toApp = PipedOutputStream()

    @After
    fun tearDown() {
        runCatching { toApp.close() }
        session.disconnect()
        recorderScope.cancel()
        sessionScope.cancel()
    }

    private fun connect() {
        session.connect(StreamTransport("reach-RS3", PipedInputStream(toApp, 64 * 1024), ByteArrayOutputStream()))
    }

    private fun sendEpoch(second: Int) {
        val time = "1015%02d.00".format(30 + second)
        toApp.write(nmea("GNGGA,$time,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001").toByteArray())
        toApp.write(nmea("GNGST,$time,0.012,0.010,0.007,45.0,0.008,0.006,0.015").toByteArray())
        toApp.flush()
    }

    private fun awaitEpochs(count: Int) = runBlocking {
        withTimeout(10_000) {
            while (recorder.snapshot().epochs.size < count) kotlinx.coroutines.delay(10)
        }
    }

    @Test
    fun `S043 RF-5 la ventana se llena con las epocas que llegan`() {
        connect()
        for (second in 0..3) {
            now += 1_000
            sendEpoch(second)
        }

        awaitEpochs(3)

        recorder.snapshot().epochs.size shouldBeGreaterThan 2
        recorder.ntripOpen shouldBe false
    }

    @Test
    fun `S043 RF-5 lo que pasa de 10 segundos se olvida`() {
        connect()
        for (second in 0..2) {
            now += 1_000
            sendEpoch(second)
        }
        awaitEpochs(2)

        now += 20_000

        recorder.snapshot().epochs.shouldBeEmpty()
    }

    @Test
    fun `S043 RF-5 al desconectar la ventana se vacia`() {
        connect()
        now += 1_000
        sendEpoch(0)
        sendEpoch(1)
        awaitEpochs(1)

        session.disconnect()
        runBlocking { withTimeout(10_000) { session.state.first { it.connection is ConnectionState.Disconnected } } }

        runBlocking {
            withTimeout(10_000) {
                while (recorder.snapshot().epochs.isNotEmpty()) kotlinx.coroutines.delay(10)
            }
        }
    }

    @Test
    fun `S043 RF-5 con un NTRIP falso la ventana cuenta los mensajes RTCM que llegan y los olvida al cortar`() {
        val rtcm = File(repositoryRoot(), "fixtures/rtcm/rgapa-AVL13M.rtcm3").readBytes()
        connect()
        now += 1_000
        sendEpoch(0)
        sendEpoch(1)
        awaitEpochs(1)

        session.startNtrip(NtripConfig("caster.es", 2101, "AVL13M", "anonimo", "anonimo", sendGga = false))
        blocks.trySend(rtcm.copyOfRange(0, 3_000))
        runBlocking { withTimeout(10_000) { session.state.first { it.ntrip is NtripState.Streaming } } }
        now += 1_500
        blocks.trySend(rtcm.copyOfRange(3_000, 9_000))
        runBlocking { withTimeout(10_000) { while (recorder.snapshot().rtcmDelta.isNullOrEmpty()) kotlinx.coroutines.delay(20) } }

        recorder.ntripOpen shouldBe true
        recorder.snapshot().rtcmDelta!!.values.sum() longGreaterThan 0L

        session.stopNtrip()
        runBlocking { withTimeout(10_000) { while (recorder.snapshot().rtcmDelta != null) kotlinx.coroutines.delay(20) } }
        recorder.ntripOpen shouldBe false
    }

    @Test
    fun `S043 RF-5 con una reproduccion de un fichero real la ventana tambien se llena`() {
        val bytes = File(repositoryRoot(), "fixtures/nmea/sintetico/rs3-completo.nmea").readBytes()
        val store = SessionLogStore(folder.root, freeSpaceBytes = { Long.MAX_VALUE })
        val id = store.import("rs3-completo.nmea", bytes.inputStream(), bytes.size.toLong()).shouldBeInstanceOf<ImportResult.Imported>().id
        val replay = ReplayController(session) { store.sessions() }

        replay.play(id, ReplaySpeed.X5) shouldBe true
        runBlocking { withTimeout(30_000) { while (recorder.snapshot().epochs.isEmpty()) kotlinx.coroutines.delay(10) } }

        recorder.snapshot().epochs.isNotEmpty() shouldBe true
        session.state.value.source shouldBe PositionSource.REPLAY
    }

    @Test
    fun `S043 RF-5 con el receptor virtual la ventana tambien se llena y se sabe que es simulado`() {
        val player = ScenarioPlayer(Scenarios.stableFix, ScenarioOrigin(40.41668, -3.70371, 701.357), 1_794_736_800_000L)

        session.connect(VirtualReceiverTransport("Receptor virtual", player, ReplaySpeed.X5))
        runBlocking { withTimeout(30_000) { while (recorder.snapshot().epochs.size < 3) kotlinx.coroutines.delay(10) } }

        recorder.snapshot().epochs.size shouldBeGreaterThan 2
        session.state.value.source shouldBe PositionSource.VIRTUAL
    }

    private fun repositoryRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
}
