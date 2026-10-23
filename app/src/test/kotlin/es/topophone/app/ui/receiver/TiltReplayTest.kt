package es.topophone.app.ui.receiver

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.log.RecordingLog
import es.topophone.app.receiver.MemoryTiltPreferences
import es.topophone.app.receiver.ReceiverConnections
import es.topophone.app.receiver.currentTilt
import es.topophone.app.ui.collector.CollectorUiState
import es.topophone.app.ui.collector.CollectorViewModel
import es.topophone.core.geodesy.Ecef
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.TiltMode
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.GroundPointCalculator
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.core.survey.WizardState
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.CollectorPreferences
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.data.receiver.ReceiverTiltSettings
import es.topophone.data.receiver.TiltPreferences
import es.topophone.receiver.platform.Alerts
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.session.ReplayController
import es.topophone.receiver.transport.StreamTransport
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.runner.RunWith

/**
 * Las reproducciones sintéticas con la inclinación por la sesión y los ViewModel reales (`tools/nmea/generar_inclinacion.py`,
 * punta con pyproj): el asistente en los dos modos, la punta en Coordenadas y un promediado con avisos del ETC.
 * S016/RF-7, RF-8, RF-13, RF-16, RF-19.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class TiltReplayTest {

    private val directory = File(repositoryRoot, "fixtures/nmea/sintetico")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val collectors = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    // Sin "Sin datos" a los 2 s: las épocas se mandan a mano y la última se queda abierta hasta la siguiente.
    private val session = ReceiverSession(scope, NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE }, noDataTimeoutMs = 60_000)
    private val toApp = PipedOutputStream()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        runCatching { toApp.close() }
        collectors.cancel()
        session.disconnect()
        scope.cancel()
        Dispatchers.resetMain()
    }

    /** Las frases del fichero agrupadas por época: cada grupo empieza en su GGA. */
    private fun epochs(name: String): List<String> {
        val text = File(directory, name).readText(Charsets.US_ASCII)
        val groups = mutableListOf<StringBuilder>()
        text.split("\r\n").filter { it.isNotEmpty() }.forEach { line ->
            if (line.startsWith("\$GNGGA") || groups.isEmpty()) groups += StringBuilder()
            groups.last().append(line).append("\r\n")
        }
        return groups.map { it.toString() }
    }

    private fun connect() = session.connect(StreamTransport("RS3 sintético", PipedInputStream(toApp, 256 * 1024), ByteArrayOutputStream()))

    private fun send(groups: List<String>) {
        groups.forEach { toApp.write(it.toByteArray(Charsets.US_ASCII)) }
        toApp.flush()
    }

    private fun wizardProposes(name: String): WizardState.Proposed {
        val tilt = currentTilt(scope, MemoryTiltPreferences(), replaying = true)
        runBlocking { withTimeout(5_000) { tilt.state.first { it.key == TiltPreferences.REPLAY } } }
        val vm = TiltViewModel(tilt, RecordingLog(), session, FakePreferences())
        collectors.launch { vm.state.collect {} }
        val groups = epochs(name)
        vm.openWizard()
        connect()
        send(groups.take(3)) // dos épocas completas: ya no falta nada
        runBlocking { withTimeout(5_000) { vm.state.first { it.wizard == WizardState.Ready(emptySet()) } } }
        vm.startTest()
        send(groups.drop(3))
        return runBlocking { withTimeout(10_000) { vm.state.first { it.wizard is WizardState.Proposed } } }.wizard
            .shouldBeInstanceOf<WizardState.Proposed>()
    }

    @Test
    fun `S016 RF-8 con el receptor sin compensar propone la app`() {
        val proposed = wizardProposes("rs3-asistente-app.nmea")

        proposed.mode shouldBe TiltMode.APP
        proposed.movedM shouldBeLessThan proposed.expectedM * 1.25
    }

    @Test
    fun `S016 RF-7 con el receptor que compensa propone el receptor`() {
        val proposed = wizardProposes("rs3-asistente-receptor.nmea")

        proposed.mode shouldBe TiltMode.RECEIVER
        proposed.movedM shouldBeLessThan 0.05
    }

    /** Lo esperado de cada época de `rs3-inclinacion.nmea`, por su hora. */
    private val expected: Map<String, Map<String, String>> by lazy {
        val data = File(directory, "rs3-inclinacion.punta.csv").readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() && !it.startsWith("#") }
        val header = data.first().split(',')
        data.drop(1).map { header.zip(it.split(',')).toMap() }.associateBy { it.getValue("hora") }
    }

    private fun hour(utcMillis: Int): String {
        val s = utcMillis / 1000
        return "%02d%02d%02d.%02d".format(s / 3600, s / 60 % 60, s % 60, utcMillis % 1000 / 10)
    }

    @Test
    fun `S016 RF-13 y RF-16 la punta en Coordenadas es la de pyproj`() {
        val tilt = currentTilt(scope, appMode(), address = RS3)
        runBlocking { withTimeout(5_000) { tilt.state.first { it.key == RS3 } } }
        val vm = ReceiverViewModel(
            session = session,
            bluetooth = FakeBluetooth(),
            preferences = FakePreferences(),
            calculator = { GroundPointCalculator() },
            network = { true },
            alerts = NoAlerts,
            clock = { System.nanoTime() / 1_000_000 },
            samplePeriodMs = 0,
            replay = ReplayController(session) { emptyList() },
            connections = ReceiverConnections(session, FakeBluetooth(), FakePreferences(), scope),
            tilt = tilt,
        )
        collectors.launch { vm.uiState.collect {} }
        val groups = epochs("rs3-inclinacion.nmea")
        val rows = expected.values.toList()
        val errors = mutableListOf<Double>()
        connect()
        send(groups.take(1))
        // Una época cada vez: la siguiente GGA cierra la anterior y se espera a verla en Coordenadas.
        rows.dropLast(1).forEachIndexed { i, row ->
            send(listOf(groups[i + 1]))
            val state = runBlocking {
                withTimeout(5_000) { vm.uiState.first { it.snapshot.epoch?.let { e -> hour(e.utcMillisOfDay) } == row.getValue("hora") } }
            }
            if (row.getValue("estado") == "COMPENSATING" && !state.tipUncompensated) {
                val ground = state.groundPoint.shouldNotBeNull()
                val tip = Ecef.fromGeodetic(row.getValue("latitud_punta").toDouble(), row.getValue("longitud_punta").toDouble(), 0.0)
                errors += Ecef.fromGeodetic(ground.latitudeDeg, ground.longitudeDeg, 0.0).distanceTo(tip)
            }
        }

        errors.size shouldBeGreaterThan 40
        errors.max() shouldBeLessThan 0.001
    }

    @Test
    fun `S016 RF-19 un promediado descarta las epocas con aviso y vuelve a la punta`() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val projects = RoomProjectRepository(db)
            val points = RoomPointRepository(db)
            val projectId = runBlocking {
                (projects.create(ProjectDefaults().toDraft().copy(name = "Inclinación")) as SaveResult.Saved).id
            }
            val tilt = currentTilt(scope, appMode(), address = RS3)
            runBlocking { withTimeout(5_000) { tilt.state.first { it.key == RS3 } } }
            var tiltLost = 0
            val alerts = object : Alerts {
                override fun connectionLost() = Unit

                override fun correctionsLost() = Unit

                override fun pointSaved() = Unit

                override fun solutionLost() = Unit

                override fun tiltLost() {
                    tiltLost++
                }
            }
            val preferences = object : CollectorPreferences {
                override val settings = MutableStateFlow(CollectorSettings(MeasurementMode.AVERAGED, averagingSeconds = 6))

                override suspend fun save(settings: CollectorSettings) = Unit
            }
            val vm = CollectorViewModel(
                projects = projects,
                points = points,
                collectorPreferences = preferences,
                receiverPreferences = FakePreferences(),
                session = session,
                alerts = alerts,
                calculator = { ProjectPositionCalculator() },
                tilt = tilt,
            )
            vm.start(projectId)
            collectors.launch { vm.state.collect {} }
            val await = { predicate: (CollectorUiState) -> Boolean ->
                runBlocking { withTimeout(10_000) { vm.state.first(predicate) } }
            }
            val groups = epochs("rs3-inclinacion.nmea")
            connect()
            // Hasta la segunda época compensando: la primera queda completa y ya se puede medir (S016/RF-18).
            send(groups.take(22))
            await { it.loaded && it.live != null && it.blocker == null }

            // El proyecto llega de Room aparte del formulario: se pulsa "Medir" hasta que la medida empiece.
            runBlocking {
                withTimeout(5_000) {
                    while (!vm.state.value.measuring) {
                        vm.measure()
                        kotlinx.coroutines.delay(20)
                    }
                }
            }
            send(groups.drop(22))

            await { it.savedName != null }
            val point = runBlocking { points.points(projectId).first { it.isNotEmpty() } }.single()
            val measurement = point.measurement.shouldNotBeNull()
            measurement.quality.epochsDiscarded shouldBeGreaterThanOrEqual 5 // los 5 de "movimiento rápido"
            measurement.quality.epochsUsed shouldBe 30
            tiltLost shouldBeGreaterThan 0
            // La media de las puntas de un jalón que se balancea es la punta fija (1 mm).
            val tip = Ecef.fromGeodetic(40.4168, -3.7038, 699.423)
            val ground = measurement.coordinates.ground
            val measured = Ecef.fromGeodetic(ground.latitudeDeg, ground.longitudeDeg, ground.ellipsoidalHeightM.shouldNotBeNull())
            measured.distanceTo(tip) shouldBeLessThan 0.001
        } finally {
            db.close()
        }
    }

    private fun appMode() = MemoryTiltPreferences(mapOf(RS3 to ReceiverTiltSettings(TiltMode.APP, verified = true)))

    private companion object {
        const val RS3 = "00:11:22:33:44:55"
    }
}
