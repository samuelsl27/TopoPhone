package es.topophone.app.ui.receiver

import es.topophone.app.di.ReceiverModule
import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.gnss.nmea.ReplaySpeed
import es.topophone.core.gnss.nmea.TiltState
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.GroundPointCalculator
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ImportResult
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.session.ReplayController
import es.topophone.receiver.session.SessionLogStore
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.time.LocalDate
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * De principio a fin con todas las frases del RS3: el fichero sintético se importa, se reproduce a ×5
 * por la sesión y el ViewModel reales y cada época se compara con lo esperado
 * (`tools/nmea/generar_rs3_completo.py`, coordenadas con pyproj).
 * S003/RF-1 a RF-11, S003/RF-33, S003/RF-36, S003/RF-38.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FullRs3ReplayTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val directory = File(repositoryRoot, "fixtures/nmea/sintetico")
    private val lines = File(directory, "rs3-completo.epocas.csv").readLines(Charsets.UTF_8)
    private val expected: List<Map<String, String>> = run {
        val data = lines.filter { it.isNotBlank() && !it.startsWith("#") }
        val header = data.first().split(',')
        data.drop(1).map { header.zip(it.split(',')).toMap() }
    }

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val collectors = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val session = ReceiverSession(sessionScope, NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE })
    private val store by lazy { SessionLogStore(folder.root, freeSpaceBytes = { Long.MAX_VALUE }) }
    private val replay by lazy { ReplayController(session) { store.sessions() } }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        collectors.cancel()
        sessionScope.cancel()
        Dispatchers.resetMain()
    }

    private fun millisOf(hour: String): Int {
        val h = hour.substring(0, 2).toInt()
        val m = hour.substring(2, 4).toInt()
        val s = hour.substring(4, 6).toInt()
        val c = hour.substring(7, 9).toInt()
        return ((h * 60 + m) * 60 + s) * 1000 + c * 10
    }

    @Test
    fun `S003 RF-1 a RF-11 y RF-36 la sesion completa del RS3 da las epocas esperadas a su ritmo`() {
        lines.first { it.startsWith("# jalon_m=") }.startsWith("# jalon_m=1.8;") shouldBe true
        val bytes = File(directory, "rs3-completo.nmea").readBytes()
        // S003/RF-33: se importa como cualquier fichero NMEA.
        val imported = store.import("rs3-completo.nmea", bytes.inputStream(), bytes.size.toLong())
        val id = imported.shouldBeInstanceOf<ImportResult.Imported>().id
        val viewModel = ReceiverViewModel(
            session = session,
            bluetooth = FakeBluetooth(),
            preferences = FakePreferences(),
            calculator = { GroundPointCalculator() },
            network = { true },
            alerts = {},
            sessionFiles = { null },
            clock = ReceiverModule.monotonicClock(),
            samplePeriodMs = ReceiverModule.uiSamplePeriodMs(),
            replay = replay,
        )
        val epochs = Collections.synchronizedList(mutableListOf<Epoch>())
        val replayNames = Collections.synchronizedSet(mutableSetOf<String>())
        collectors.launch { session.state.mapNotNull { it.epoch }.distinctUntilChanged().collect { epochs += it } }
        collectors.launch { viewModel.uiState.collect { state -> state.snapshot.replay?.let { replayNames += it.name } } }

        val started = System.nanoTime()
        replay.play(id, ReplaySpeed.X5) shouldBe true
        runBlocking { withTimeout(30_000) { session.state.first { it.connection is ConnectionState.ReplayFinished } } }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        replayNames shouldBe setOf("rs3-completo.nmea")
        val calculator = GroundPointCalculator()
        epochs.map { it.utcMillisOfDay } shouldBe expected.map { millisOf(it.getValue("hora")) } // S003/RF-8 a RF-10
        epochs.zip(expected).forEach { (epoch, row) ->
            io.kotest.assertions.withClue(row.getValue("hora")) {
                epoch.date shouldBe LocalDate.parse(row.getValue("fecha")) // S003/RF-11
                epoch.satellitesUsed shouldBe row.getValue("satelites_usados").toIntOrNull() // S003/RF-1
                epoch.satellitesInView shouldBe row.getValue("satelites_en_vista").toIntOrNull() // S003/RF-2
                epoch.tilt.shouldNotBeNull().state shouldBe TiltState.valueOf(row.getValue("inclinacion")) // S003/RF-6
                val point = calculator.compute(epoch.gga.shouldNotBeNull(), 1.8).shouldNotBeNull()
                point.ellipsoidalHeightM.shouldNotBeNull() shouldBe (row.getValue("h").toDouble() plusOrMinus MM)
                point.utm.shouldNotBeNull().easting shouldBe (row.getValue("este").toDouble() plusOrMinus MM)
                point.utm.shouldNotBeNull().northing shouldBe (row.getValue("norte").toDouble() plusOrMinus MM)
                point.orthometricHeightM.shouldNotBeNull() shouldBe (row.getValue("H").toDouble() plusOrMinus MM)
            }
        }
        // 5,8 s + el hueco de 15 s acortado a 10 s (S003/RF-38) + 1,8 s, a ×5 (S003/RF-36): unos 3,5 s.
        elapsedMs shouldBeGreaterThan 3_300L
        elapsedMs shouldBeLessThan 5_000L
    }

    private companion object {
        const val MM = 0.001
    }
}
