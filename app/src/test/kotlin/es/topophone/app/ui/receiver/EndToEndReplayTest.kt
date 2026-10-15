package es.topophone.app.ui.receiver

import es.topophone.app.di.ReceiverModule
import es.topophone.app.receiver.ReceiverConnections
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.GroundPointCalculator
import es.topophone.core.survey.Unavailable
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.session.ReplayController
import es.topophone.receiver.transport.StreamTransport
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * De principio a fin: el fichero NMEA sintético entra por el transporte, pasa por la sesión y el
 * ViewModel reales (muestreo de producción, 200 ms) y se comparan las coordenadas que vería el
 * usuario con las que calculó pyproj (`tools/nmea/generar_sintetico.py`).
 * S002/RF-11 a RF-15, S002/RF-32 a RF-38; latencia de la spec (< 300 ms).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EndToEndReplayTest {

    private val directory = File(repositoryRoot, "fixtures/nmea/sintetico")
    private val nmeaBytes = File(directory, "estatico-fix.nmea").readBytes()
    private val expectedLines = File(directory, "estatico-fix.esperado.csv").readLines(Charsets.UTF_8)

    private data class Expected(val columns: Map<String, String>) {
        operator fun get(name: String): String = columns.getValue(name)

        fun double(name: String): Double? = get(name).takeIf { it.isNotEmpty() }?.toDouble()
    }

    private val expected: List<Expected> = run {
        val data = expectedLines.filter { it.isNotBlank() && !it.startsWith("#") }
        val header = data.first().split(',')
        data.drop(1).map { line -> Expected(header.zip(line.split(',')).toMap()) }
    }

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val toApp = PipedOutputStream()
    private val receiverInput = PipedInputStream(toApp, 64 * 1024)
    private val bluetooth = FakeBluetooth()
    private val session = ReceiverSession(sessionScope, NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE })

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        bluetooth.transports[RS3_DEVICE.address] = StreamTransport(RS3_DEVICE.name, receiverInput, ByteArrayOutputStream())
    }

    @After
    fun tearDown() {
        collectorScope.cancel()
        sessionScope.cancel()
        Dispatchers.resetMain()
    }

    /** Escribe como llegarían por Bluetooth: en trozos pequeños que cortan las frases. */
    private fun send(bytes: ByteArray) {
        bytes.asList().chunked(CHUNK).forEach {
            toApp.write(it.toByteArray())
            toApp.flush()
        }
    }

    private fun indexOf(needle: String, from: Int): Int {
        val target = needle.toByteArray(Charsets.US_ASCII)
        return (from..nmeaBytes.size - target.size).first { start -> target.indices.all { nmeaBytes[start + it] == target[it] } }
    }

    @Test
    fun `S002 RF-11 a RF-15 y RF-32 a RF-38 la sesion sintetica se ve como la calculo pyproj y en menos de 300 ms`() {
        expectedLines.first { it.startsWith("# jalon_m=") } shouldContain "jalon_m=1.8;"
        val corruptUtc = expectedLines.first { it.startsWith("# jalon_m=") }.substringAfter("hora_corrupta=").trim()
        val viewModel = ReceiverViewModel(
            session = session,
            bluetooth = bluetooth,
            preferences = FakePreferences(),
            calculator = { CALCULATOR },
            network = { true },
            alerts = {},
            sessionFiles = { null },
            clock = ReceiverModule.monotonicClock(),
            samplePeriodMs = ReceiverModule.uiSamplePeriodMs(),
            replay = ReplayController(session) { emptyList() },
            connections = ReceiverConnections(session, bluetooth, FakePreferences(), CoroutineScope(Dispatchers.Default)),
        )
        val seenUtc = ConcurrentLinkedQueue<String>()
        collectorScope.launch { viewModel.uiState.collect { state -> state.snapshot.gga?.let { seenUtc += it.utcTime } } }
        viewModel.connect(RS3_DEVICE.address)

        // Cada época: lo anterior a su GGA (basura, otras frases), la GGA, y lo que sigue (GST...).
        val starts = expected.map { indexOf("$" + it["frase"] + "," + it["hora"] + ",", 0) }
        send(nmeaBytes.copyOfRange(0, starts.first()))
        val latencies = expected.mapIndexed { index, row ->
            val start = starts[index]
            val ggaEnd = indexOf("\n", start) + 1
            val next = starts.getOrElse(index + 1) { nmeaBytes.size }
            send(nmeaBytes.copyOfRange(start, ggaEnd))
            val sentAt = System.nanoTime()
            awaitState(viewModel) { it.snapshot.gga?.utcTime == row["hora"] }
            val latencyMs = (System.nanoTime() - sentAt) / 1_000_000
            send(nmeaBytes.copyOfRange(ggaEnd, next))
            val state = awaitState(viewModel) { it.snapshot.gga?.utcTime == row["hora"] && it.snapshot.gst?.utcTime == row["hora"] }
            check(row, state)
            latencyMs
        }

        println("Latencia GGA → coordenadas en el ViewModel (ms): $latencies")
        // Reloj real en una JVM compartida con otros tests: la mediana mide la latencia típica (< 300 ms)
        // y el máximo, más holgado, detecta bloqueos sin fallar por un pico de carga del ordenador.
        latencies.sorted()[latencies.size / 2] shouldBeLessThan 300L
        latencies.max() shouldBeLessThan 1_000L
        seenUtc shouldContain expected.last()["hora"]
        seenUtc.toList() shouldNotContain corruptUtc // S002/RF-11: suma de control errónea
        seenUtc.toList() shouldNotContain "101531.50" // S002/RF-11: frase cortada
    }

    private fun awaitState(viewModel: ReceiverViewModel, predicate: (ReceiverUiState) -> Boolean): ReceiverUiState = runBlocking {
        withTimeout(5_000) { viewModel.uiState.first(predicate) }
    }

    private fun check(row: Expected, state: ReceiverUiState) {
        val utc = row["hora"]
        val gga = state.snapshot.gga.shouldNotBeNull()
        withClue(utc) {
            gga.status shouldBe SolutionStatus.valueOf(row["estado"]) // S002/RF-12
            gga.satellites shouldBe row["satelites"].toInt() // S002/RF-13
            gga.hdop shouldBe row.double("hdop")
            gga.correctionAgeS shouldBe row.double("edad")
            val gst = state.snapshot.gst.shouldNotBeNull()
            gst.horizontalM.nearly(row.double("sigma_h"), 1e-12) // S002/RF-14
            gst.verticalM.nearly(row.double("sigma_v"), 1e-12) // S002/RF-15
            val latitude = row.double("latitud")
            if (latitude == null) {
                state.groundPoint.shouldBeNull()
                return@withClue
            }
            val point = state.groundPoint.shouldNotBeNull()
            point.latitudeDeg shouldBe (latitude plusOrMinus 1e-12)
            point.longitudeDeg shouldBe (row.double("longitud")!! plusOrMinus 1e-12)
            point.ellipsoidalHeightM.nearly(row.double("h"), MM) // S002/RF-32, S002/RF-33
            val east = row.double("este")
            if (east == null) {
                point.utm.shouldBeNull()
                point.unavailable shouldContain Unavailable.OUTSIDE_UTM_ZONES
            } else {
                val utm = point.utm.shouldNotBeNull()
                utm.zone shouldBe row["huso"].toInt() // S002/RF-36
                utm.easting shouldBe (east plusOrMinus MM)
                utm.northing shouldBe (row.double("norte")!! plusOrMinus MM)
            }
            val orthometric = row.double("H")
            if (orthometric == null) {
                point.orthometricHeightM.shouldBeNull()
                point.unavailable shouldContain Unavailable.OUTSIDE_GEOID
            } else {
                point.orthometricHeightM.nearly(orthometric, MM) // S002/RF-38
            }
        }
    }

    private fun Double?.nearly(expected: Double?, tolerance: Double) {
        if (expected == null) {
            this.shouldBeNull()
        } else {
            this.shouldNotBeNull() shouldBe (expected plusOrMinus tolerance)
        }
    }

    private fun <T> withClue(clue: String, block: () -> T): T = io.kotest.assertions.withClue("época $clue", block)

    private companion object {
        const val CHUNK = 7
        const val MM = 0.001
        val CALCULATOR = GroundPointCalculator()
    }
}
