package es.topophone.app.ui.collector

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.log.RecordingLog
import es.topophone.app.ui.receiver.FakePreferences
import es.topophone.app.ui.receiver.nmea
import es.topophone.core.model.point.AutoTrigger
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.PrecisionControl
import es.topophone.core.model.point.PrecisionFailure
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.AutoSummary
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.CollectorPreferences
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.receiver.platform.Alerts
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.transport.StreamTransport
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
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
 * El recolector avanzado (S017): el promediado de hasta 60 min con sus estadísticas y "Terminar", la pausa ante un corte
 * y el modo automático. Con la `ReceiverSession` real y las épocas de una en una, a 1 Hz.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CollectorViewModelAdvancedTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db)
    private val receiverPreferences = FakePreferences()
    private val collectorPreferences = FakeCollectorPreferences()
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(
        sessionScope,
        NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE },
        noDataTimeoutMs = 60_000, // las épocas se mandan a mano: bajo carga, 2 s sin ninguna dejarían la sesión en "Sin datos"
        watchdogPeriodMs = 1_000,
    )
    private val toApp = PipedOutputStream()
    private val log = RecordingLog()
    private var saved = 0
    private var lost = 0
    private var pausedAlerts = 0
    private var omittedAlerts = 0
    private var restartedAlerts = 0
    private val alerts = object : Alerts {
        override fun connectionLost() = Unit

        override fun correctionsLost() = Unit

        override fun pointSaved() {
            saved++
        }

        override fun solutionLost() {
            lost++
        }

        override fun tiltLost() = Unit

        override fun measurementPaused() {
            pausedAlerts++
        }

        override fun pointOmitted() {
            omittedAlerts++
        }

        override fun measurementRestarted() {
            restartedAlerts++
        }
    }

    private class FakeCollectorPreferences : CollectorPreferences {
        override val settings = MutableStateFlow(CollectorSettings())

        override suspend fun save(settings: CollectorSettings) {
            this.settings.value = settings
        }
    }

    /** Los recolectores de la prueba: un guardado en vuelo al cerrar la base filtraba su excepción al test siguiente. */
    private val viewModels = mutableListOf<CollectorViewModel>()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        runCatching { toApp.close() }
        session.disconnect()
        sessionScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun project(): String = runBlocking {
        (projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")) as SaveResult.Saved).id
    }

    /** Un recolector que promedia [seconds] s: espera a que sus ajustes lleguen, que si no "Medir" mediría en instantáneo. */
    private fun averagedViewModel(
        seconds: Int,
        precision: PrecisionControl = PrecisionControl(),
        projectId: String = project(),
        observed: Boolean = true,
    ): CollectorViewModel {
        collectorPreferences.settings.value = CollectorSettings(MeasurementMode.AVERAGED, averagingSeconds = seconds, precision = precision)
        val vm = viewModel(projectId, observed)
        await(vm) { it.settings.mode == MeasurementMode.AVERAGED && it.settings.averagingSeconds == seconds }
        return vm
    }

    /** "Medir", con reintento: `measure()` se sale en silencio mientras el proyecto no ha llegado de Room. */
    private fun startMeasuring(vm: CollectorViewModel) = runBlocking {
        withTimeout(10_000) {
            while (!vm.state.value.measuring) {
                vm.measure()
                kotlinx.coroutines.delay(50)
            }
        }
    }

    private fun viewModel(projectId: String, observed: Boolean = true): CollectorViewModel {
        val vm = CollectorViewModel(
            projects,
            points,
            collectorPreferences,
            receiverPreferences,
            session,
            alerts,
            { ProjectPositionCalculator() },
            MeasurementInProgress(),
            log,
        )
        viewModels += vm
        vm.statsIntervalMs = 0
        vm.start(projectId)
        if (observed) CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        await(vm) { it.loaded }
        // El proyecto llega de Room aparte del formulario; hasta entonces `measure()` se sale en silencio.
        runBlocking { withTimeout(10_000) { while (!vm.isProjectLoaded) kotlinx.coroutines.delay(10) } }
        return vm
    }

    /** Espera al estado que cumple [predicate]; si no llega, el fallo dice en qué estado se quedó. */
    private fun await(vm: CollectorViewModel, predicate: (CollectorUiState) -> Boolean): CollectorUiState = runBlocking {
        try {
            withTimeout(10_000) { vm.state.first(predicate) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError(
                "El recolector no llegó al estado esperado; se quedó en ${vm.state.value}; estadísticas ${vm.stats.value}; log ${log.messages}",
                e,
            )
        }
    }

    private fun awaitStats(vm: CollectorViewModel, predicate: (AverageStatsUi) -> Boolean): AverageStatsUi = runBlocking {
        try {
            withTimeout(10_000) { vm.stats.first { it != null && predicate(it) } }!!
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("Las estadísticas no llegaron; el recolector está en ${vm.state.value}; estadísticas ${vm.stats.value}", e)
        }
    }

    /** Una época a 1 Hz: GGA (con la calidad de [status]) y GST; [lat] en minutos con siete decimales (1 cm son 0,0000054). */
    private fun sendEpoch(second: Int, status: Int = 4, lat: String = "4025.0080000", baseLat: String? = null) {
        val t = 36_930 + second // 10:15:30 más [second] segundos
        val time = "%02d%02d%02d.00".format(t / 3_600, t / 60 % 60, t % 60)
        toApp.write(nmea("GNGGA,$time,$lat,N,00342.2228000,W,$status,24,0.6,650.123,M,51.234,M,1.0,0001").toByteArray())
        toApp.write(nmea("GNGST,$time,0.012,0.010,0.007,45.0,0.008,0.006,0.015").toByteArray())
        baseLat?.let { toApp.write(nmea("GNEBP,$it,N,00342.0000000,W,700.000,M").toByteArray()) }
        toApp.flush()
    }

    private fun receiving(vm: CollectorViewModel, ready: Boolean = true) {
        session.connect(StreamTransport("reach-RS3", PipedInputStream(toApp, 64 * 1024), ByteArrayOutputStream()))
        sendEpoch(0)
        sendEpoch(1)
        runBlocking { withTimeout(5_000) { session.state.first { it.connection is ConnectionState.Receiving && it.epoch != null } } }
        await(vm) { it.live != null && (!ready || it.blocker == null) }
    }

    private companion object {
        /** La hora UTC de la época 1 de `sendEpoch`: 10:15:31. */
        const val EPOCH_1_UTC_MS = 36_931_000
    }

    @Test
    fun `S017 RF-1 el tiempo se escribe en minutos y segundos y se guarda de 1 s a 60 min`() {
        val vm = viewModel(project())
        vm.state.value.averagingSecondsText shouldBe "0:05"

        vm.setAveragingSeconds("1:30")
        await(vm) { it.settings.averagingSeconds == 90 }.averagingSecondsValid shouldBe true
        vm.state.value.averagingSecondsText shouldBe "1:30"

        vm.setAveragingSeconds("60:00")
        await(vm) { it.settings.averagingSeconds == 3_600 }.averagingSecondsValid shouldBe true

        vm.setAveragingSeconds("45") // un entero son segundos
        await(vm) { it.settings.averagingSeconds == 45 }

        vm.setAveragingSeconds("60:01")
        await(vm) { it.averagingSecondsText == "60:01" }.averagingSecondsValid shouldBe false
        collectorPreferences.settings.value.averagingSeconds shouldBe 45
    }

    @Test
    fun `S017 RF-2 mientras se promedia se ve el tiempo reunido y las epocas usadas y descartadas`() {
        val vm = averagedViewModel(seconds = 10)
        receiving(vm)

        startMeasuring(vm)
        sendEpoch(2) // usada
        sendEpoch(3, status = 5) // FLOAT: descartada
        sendEpoch(4) // usada
        val progress = await(vm) { it.progress?.epochsUsed == 2 && it.progress?.epochsDiscarded == 1 }.progress.shouldNotBeNull()

        progress.elapsedS shouldBe (2.0 plusOrMinus 1e-9)
        progress.requiredS shouldBe (10.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S017 RF-3, RF-4 y RF-5 las estadisticas y el grafico salen en su propio flujo`() {
        val vm = averagedViewModel(seconds = 60)
        receiving(vm)
        vm.stats.value.shouldBeNull()

        startMeasuring(vm)
        sendEpoch(2, lat = "4025.0080000")
        sendEpoch(3, lat = "4025.0080054") // 1 cm al norte
        sendEpoch(4, lat = "4025.0080000")
        val stats = awaitStats(vm) { it.snapshot.used == 3 }

        stats.snapshot.timeS shouldBe (3.0 plusOrMinus 1e-9)
        stats.snapshot.deviationHorizontalM.shouldNotBeNull() shouldBe (0.0058 plusOrMinus 0.002)
        stats.snapshot.deviationVerticalM.shouldNotBeNull() shouldBe (0.0 plusOrMinus 1e-6)
        stats.snapshot.stabilityHorizontalM.shouldBeNull() // aún no ha pasado un minuto
        stats.dispersion.shouldNotBeNull().count shouldBe 3

        vm.cancel()
        runBlocking { withTimeout(5_000) { vm.stats.first { it == null } } }
    }

    @Test
    fun `S017 RF-6 Terminar guarda el punto con las epocas reunidas`() {
        val projectId = project()
        val vm = averagedViewModel(seconds = 600, projectId = projectId)
        receiving(vm)

        startMeasuring(vm)
        (2..5).forEach { sendEpoch(it) }
        await(vm) { it.progress?.epochsUsed == 4 }
        vm.finishNow()
        await(vm) { it.savedName == "1" }

        val measurement = runBlocking { points.points(projectId).first() }.single().measurement.shouldNotBeNull()
        measurement.quality.mode shouldBe MeasurementMode.AVERAGED
        measurement.quality.epochsUsed shouldBe 4
        measurement.rawNmea.lines().size shouldBe 8
        saved shouldBe 1
        vm.state.value.measuring shouldBe false
    }

    @Test
    fun `S017 RF-6 Terminar pasa por el control de precision y pide confirmar si no se cumple`() {
        val projectId = project()
        val vm = averagedViewModel(600, PrecisionControl(fixOnly = false, horizontalLimitM = 0.001), projectId)
        receiving(vm)

        startMeasuring(vm)
        sendEpoch(2)
        sendEpoch(3)
        await(vm) { it.progress?.epochsUsed == 2 }
        vm.finishNow()

        val pending = await(vm) { it.pending != null && !it.measuring }.pending.shouldNotBeNull()
        pending.failures shouldBe setOf(PrecisionFailure.HORIZONTAL)
        runBlocking { points.points(projectId).first() }.shouldBeEmpty()
        saved shouldBe 0
    }

    @Test
    fun `S017 RF-7 Terminar sin ninguna epoca usada no guarda nada y la medida sigue`() {
        val projectId = project()
        val vm = averagedViewModel(seconds = 600, projectId = projectId)
        receiving(vm)
        // La época 1 sigue pendiente y es FIX: se completa con la 2, antes de empezar, para que no cuente.
        sendEpoch(2, status = 5)
        runBlocking { withTimeout(5_000) { session.state.first { it.epoch?.utcMillisOfDay == EPOCH_1_UTC_MS } } }

        startMeasuring(vm)
        sendEpoch(3, status = 5) // FLOAT con "solo FIX": se descarta
        sendEpoch(4, status = 5)
        await(vm) { it.progress?.epochsDiscarded == 2 }
        vm.finishNow()

        val waiting = await(vm) { it.noEpochsYet }
        waiting.measuring shouldBe true
        runBlocking { points.points(projectId).first() }.shouldBeEmpty()
        saved shouldBe 0

        sendEpoch(5) // FIX: la medida sigue y el aviso se va
        sendEpoch(6)
        await(vm) { it.progress?.epochsUsed == 1 && !it.noEpochsYet }.measuring shouldBe true
    }

    private val baseA = "4025.0000000"
    private val baseB = "4025.0010000" // 1,85 m al norte de A

    /** La hora del móvil [afterMs] después de ahora: para el reloj de la medida, que en los tests no avanza solo. */
    private fun later(afterMs: Long) = System.currentTimeMillis() + afterMs

    /** Un promediado de 60 s con dos épocas usadas (la 1 y la 2), listo para que se corte. */
    private fun measuringTwoEpochs(projectId: String = project()): CollectorViewModel {
        val vm = averagedViewModel(seconds = 60, projectId = projectId)
        receiving(vm)
        startMeasuring(vm)
        sendEpoch(2)
        sendEpoch(3)
        await(vm) { it.progress?.epochsUsed == 2 }
        return vm
    }

    @Test
    fun `S017 RF-8 sin epocas en 2 s un promediado se pausa, avisa una vez y conserva lo reunido`() {
        val vm = measuringTwoEpochs()

        vm.tick(later(2_500))
        val state = await(vm) { it.paused == PauseState.PAUSED }
        vm.tick(later(3_000))

        state.measuring shouldBe true
        state.progress.shouldNotBeNull().epochsUsed shouldBe 2
        pausedAlerts shouldBe 1
        log.messages shouldContain "Medida de 1 en pausa: sin datos del receptor"
    }

    @Test
    fun `S017 RF-9 al volver los datos se reanuda sola y el tiempo del corte no cuenta`() {
        val vm = measuringTwoEpochs()
        vm.tick(later(2_500))
        await(vm) { it.paused == PauseState.PAUSED }

        sendEpoch(4)
        sendEpoch(5)
        val resumed = await(vm) { it.paused == null && it.progress?.epochsUsed == 4 }

        // Cuatro épocas de 1 s: lo que dura el corte no suma.
        resumed.progress.shouldNotBeNull().elapsedS shouldBe (4.0 plusOrMinus 1e-9)
        log.messages shouldContain "Medida de 1 reanudada: han vuelto los datos del receptor"
    }

    @Test
    fun `S017 RF-10 a los 5 min de corte se pregunta y Guardar con lo reunido guarda el punto`() {
        val projectId = project()
        val vm = measuringTwoEpochs(projectId)
        vm.tick(later(2_500))
        await(vm) { it.paused == PauseState.PAUSED }
        vm.tick(later(301_000))
        await(vm) { it.paused == PauseState.LONG_PAUSE }

        vm.finishNow()

        await(vm) { it.savedName == "1" }
        runBlocking { points.points(projectId).first() }.single().measurement.shouldNotBeNull().quality.epochsUsed shouldBe 2
        log.messages shouldContain "Corte de más de 5 min en la medida de 1: se pregunta qué hacer"
    }

    @Test
    fun `S017 RF-10 Descartar no guarda nada`() {
        val projectId = project()
        val vm = measuringTwoEpochs(projectId)
        vm.tick(later(2_500))
        vm.tick(later(301_000))
        await(vm) { it.paused == PauseState.LONG_PAUSE }

        vm.cancel()

        val state = await(vm) { !it.measuring }
        state.paused shouldBe null
        state.savedName shouldBe null
        runBlocking { points.points(projectId).first() }.shouldBeEmpty()
    }

    @Test
    fun `S017 RF-10 Seguir esperando deja la medida en pausa y al volver los datos la pregunta se cierra`() {
        val vm = measuringTwoEpochs()
        vm.tick(later(2_500))
        vm.tick(later(301_000))
        await(vm) { it.paused == PauseState.LONG_PAUSE }

        vm.keepWaiting()
        await(vm) { it.paused == PauseState.PAUSED }
        vm.tick(later(299_000)) // la pregunta vuelve a los 5 min, no antes
        vm.tick(later(301_000))
        await(vm) { it.paused == PauseState.LONG_PAUSE }

        sendEpoch(4)
        sendEpoch(5)
        await(vm) { it.paused == null && it.measuring }
    }

    @Test
    fun `S017 RF-11 la medida sigue aunque nadie mire el estado del recolector`() {
        val vm = averagedViewModel(seconds = 600, observed = false)
        receiving(vm)
        startMeasuring(vm)
        sendEpoch(2)
        sendEpoch(3)
        sendEpoch(4)

        // Sin ningún observador del estado, como con la app en segundo plano: la medida llega igual.
        runBlocking {
            withTimeout(10_000) { while (vm.state.value.progress?.epochsUsed != 3) kotlinx.coroutines.delay(20) }
        }
        vm.state.value.measuring shouldBe true
        vm.finishNow()
        await(vm) { it.savedName == "1" }
    }

    @Test
    fun `S017 RF-30 si la base cambia a mitad de un promediado se descarta lo promediado, se avisa y se dice cuanto`() {
        val vm = averagedViewModel(seconds = 60)
        receiving(vm)
        startMeasuring(vm)
        sendEpoch(2, baseLat = baseA)
        sendEpoch(3, baseLat = baseA)
        sendEpoch(4, baseLat = baseA)
        await(vm) { it.progress?.epochsUsed == 3 }
        sendEpoch(5, baseLat = baseB) // cambia la base
        sendEpoch(6, baseLat = baseB) // completa la época 5

        val state = await(vm) { it.restarted }

        // Cuatro épocas de 1 s: la 5, que ya trae la base nueva, es la que empieza de nuevo.
        state.restartedDiscardedS.shouldNotBeNull() shouldBe (4.0 plusOrMinus 1e-9)
        restartedAlerts shouldBe 1
        log.messages shouldContain "Medida de 1: se descartan 0:04 de promediado"
        log.messages shouldContain "Medida de 1 empezada de nuevo: la base ha cambiado"
    }

    @Test
    fun `S017 RF-12 el punto promediado guarda su tiempo y sus desviaciones tipicas y no es automatico`() {
        val projectId = project()
        val vm = averagedViewModel(seconds = 600, projectId = projectId)
        receiving(vm)
        startMeasuring(vm)
        sendEpoch(2, lat = "4025.0080000")
        sendEpoch(3, lat = "4025.0080054") // 1 cm al norte
        sendEpoch(4, lat = "4025.0080000")
        sendEpoch(5, lat = "4025.0080054")
        await(vm) { it.progress?.epochsUsed == 4 }
        vm.finishNow()
        await(vm) { it.savedName == "1" }

        val quality = runBlocking { points.points(projectId).first() }.single().measurement.shouldNotBeNull().quality
        quality.averagedTimeS.shouldNotBeNull() shouldBe (4.0 plusOrMinus 1e-9)
        quality.deviationHorizontalM.shouldNotBeNull() shouldBe (0.0058 plusOrMinus 0.002)
        quality.deviationVerticalM.shouldNotBeNull() shouldBe (0.0 plusOrMinus 1e-6)
        quality.auto shouldBe false
        log.messages shouldContain "Medida de 1: Terminar con las épocas reunidas"
    }

    @Test
    fun `S017 RF-12 una medida instantanea no lleva tiempo promediado ni desviaciones`() {
        val projectId = project()
        val vm = viewModel(projectId)
        receiving(vm)

        startMeasuring(vm)
        sendEpoch(2)
        await(vm) { it.savedName == "1" }

        val quality = runBlocking { points.points(projectId).first() }.single().measurement.shouldNotBeNull().quality
        quality.averagedTimeS.shouldBeNull()
        quality.deviationHorizontalM.shouldBeNull()
        quality.deviationVerticalM.shouldBeNull()
        quality.auto shouldBe false
    }

    @Test
    fun `S017 RF-12 un promediado con mas frases de las que caben en una fila se guarda con sus tramos`() {
        val projectId = project()
        val vm = averagedViewModel(seconds = 3_600, projectId = projectId)
        receiving(vm)
        startMeasuring(vm)
        (2..2_001).forEach { sendEpoch(it) }
        await(vm) { (it.progress?.epochsUsed ?: 0) >= 2_000 } // el estado junta cambios: no se ve cada época
        vm.finishNow()
        await(vm) { it.savedName == "1" }

        val point = runBlocking { points.points(projectId).first() }.single()
        val measurement = point.measurement.shouldNotBeNull()
        measurement.rawNmea shouldBe ""
        measurement.nmeaChunks shouldBe 2
        val text = runBlocking {
            (0 until measurement.nmeaChunks).map { points.nmeaChunk(point.id, it).shouldNotBeNull() }
        }.joinToString("")
        // Las 2.000 que se mandan y las que llegaron tarde de antes de medir: la GGA y la GST de cada una.
        val used = measurement.quality.epochsUsed
        used shouldBeGreaterThanOrEqual 2_000
        text.lines().size shouldBe 2 * used
        text.lines().first().startsWith("\$GNGGA,1015") shouldBe true
    }

    /** Un recolector con el modo automático encendido y [settings], con sus ajustes ya llegados. */
    private fun autoViewModel(settings: CollectorSettings, projectId: String = project()): CollectorViewModel {
        collectorPreferences.settings.value = settings
        val vm = viewModel(projectId)
        await(vm) { it.settings == settings }
        vm.setAutoEnabled(true)
        await(vm) { it.autoEnabled }
        return vm
    }

    /** "Iniciar", con reintento por lo mismo que "Medir" (S017/RF-19). */
    private fun startAuto(vm: CollectorViewModel) = runBlocking {
        withTimeout(10_000) {
            while (!vm.state.value.auto.running) {
                vm.startAuto()
                kotlinx.coroutines.delay(50)
            }
        }
    }

    /** La latitud de la época [second] con 5 cm más al norte que la anterior (1 cm son 0,0000054 minutos). */
    private fun walking(second: Int) = "4025.%07d".format(80_000 + 270 * second)

    @Test
    fun `S017 RF-19, RF-20 y RF-22 por tiempo mide un punto de inmediato y otro cada intervalo, con nombres seguidos`() {
        val projectId = project()
        val vm = autoViewModel(CollectorSettings(autoTrigger = AutoTrigger.TIME, autoIntervalS = 3), projectId)
        receiving(vm)

        startAuto(vm)
        (2..10).forEach { sendEpoch(it) }
        await(vm) { it.auto.saved >= 3 }

        val saved = runBlocking { points.points(projectId).first() }.sortedBy { it.name }
        saved shouldHaveAtLeastSize 3
        saved.take(3).map { it.name } shouldBe listOf("1", "2", "3")
        saved.all { it.measurement.shouldNotBeNull().quality.auto == true } shouldBe true
        this@CollectorViewModelAdvancedTest.saved shouldBe saved.size // el sonido de cada punto (S010/RF-19)
        log.messages shouldContain "Modo automático iniciado: cada 3 s"
    }

    @Test
    fun `S017 RF-21 por distancia mide un punto cuando el suelo se ha movido esa distancia del ultimo guardado`() {
        val projectId = project()
        val vm = autoViewModel(CollectorSettings(autoTrigger = AutoTrigger.DISTANCE, autoDistanceM = 0.1), projectId)
        receiving(vm)

        startAuto(vm)
        (2..14).forEach { sendEpoch(it, lat = walking(it)) }
        await(vm) { it.auto.saved >= 3 }

        val saved = runBlocking { points.points(projectId).first() }.sortedBy { it.name }
        saved shouldHaveAtLeastSize 3
        for ((previous, next) in saved.zipWithNext()) {
            val dn = checkNotNull(next.coordinates.northing) - checkNotNull(previous.coordinates.northing)
            val de = checkNotNull(next.coordinates.easting) - checkNotNull(previous.coordinates.easting)
            // 10 cm de distancia y un paso de 5 cm por época: el punto sale 10 cm o más después del anterior.
            kotlin.math.hypot(de, dn) shouldBeGreaterThanOrEqual 0.099
        }
    }

    @Test
    fun `S017 RF-24 y RF-31 un punto que no cumple el control de precision se omite, avisa una vez y queda en el log`() {
        val projectId = project()
        val settings = CollectorSettings(
            autoTrigger = AutoTrigger.TIME,
            autoIntervalS = 2,
            precision = PrecisionControl(horizontalLimitM = 0.001),
        )
        val vm = autoViewModel(settings, projectId)
        receiving(vm)

        startAuto(vm)
        (2..8).forEach { sendEpoch(it) }
        await(vm) { it.auto.omitted >= 3 }

        runBlocking { points.points(projectId).first() }.shouldBeEmpty()
        omittedAlerts shouldBe 1 // una sola vez hasta que se guarde un punto
        log.messages shouldContain "Punto omitido del modo automático: no cumple el control de precisión: HORIZONTAL"
        vm.state.value.auto.saved shouldBe 0
    }

    @Test
    fun `S017 RF-27 Parar detiene el modo, dice lo guardado y lo omitido y no mide mas`() {
        val projectId = project()
        val vm = autoViewModel(CollectorSettings(autoTrigger = AutoTrigger.TIME, autoIntervalS = 2), projectId)
        receiving(vm)
        startAuto(vm)
        (2..6).forEach { sendEpoch(it) }
        await(vm) { it.auto.saved >= 2 }

        vm.stopAuto()

        val stopped = await(vm) { !it.auto.running && it.auto.summary != null }
        val summary = stopped.auto.summary.shouldNotBeNull()
        summary.saved shouldBeGreaterThanOrEqual 2
        summary shouldBe AutoSummary(summary.saved, 0)
        stopped.measuring shouldBe false
        val count = runBlocking { points.points(projectId).first() }.size
        count shouldBe summary.saved
        (7..12).forEach { sendEpoch(it) }
        runBlocking { kotlinx.coroutines.delay(300) }
        runBlocking { points.points(projectId).first() }.size shouldBe count
        log.messages shouldContain "Modo automático parado: ${summary.saved} guardados y 0 omitidos"
    }

    @Test
    fun `S017 RF-28 un corte pausa el modo automatico, avisa, y al volver los datos sigue sin recuperar los puntos del corte`() {
        val projectId = project()
        val vm = autoViewModel(CollectorSettings(autoTrigger = AutoTrigger.TIME, autoIntervalS = 3), projectId)
        receiving(vm)
        startAuto(vm)
        sendEpoch(2)
        await(vm) { it.auto.saved >= 1 }

        vm.tick(later(2_500))
        await(vm) { it.auto.paused }
        vm.tick(later(3_000))

        pausedAlerts shouldBe 1
        log.messages shouldContain "Modo automático en pausa: sin datos del receptor"
        // Un corte largo en la hora de las épocas: 60 s después, las épocas vuelven.
        (60..63).forEach { sendEpoch(it) }
        await(vm) { !it.auto.paused && it.auto.running }
        log.messages shouldContain "Modo automático reanudado: han vuelto los datos del receptor"
        // Ni uno por cada intervalo del corte: el intervalo cuenta de nuevo desde que vuelven los datos.
        // El recuento sube al pasar el control y el guardado acaba un momento después.
        runBlocking { withTimeout(5_000) { points.points(projectId).first { it.size == vm.state.value.auto.saved } } }
        vm.state.value.auto.saved shouldBeLessThanOrEqual 3
    }

    @Test
    fun `S017 RF-32 con el modo automatico en marcha la medida y el disparo no cambian`() {
        val settings = CollectorSettings(autoTrigger = AutoTrigger.TIME, autoIntervalS = 3)
        val vm = autoViewModel(settings)
        receiving(vm)
        startAuto(vm)

        vm.setSettings(settings.copy(mode = MeasurementMode.AVERAGED, autoTrigger = AutoTrigger.DISTANCE, autoIntervalS = 10))
        vm.setAveragingSeconds("2:00")
        vm.setAutoInterval("1:00")
        vm.setAutoTrigger(AutoTrigger.DISTANCE)
        vm.setAutoEnabled(false)

        collectorPreferences.settings.value shouldBe settings
        vm.state.value.autoEnabled shouldBe true
    }

    @Test
    fun `S017 RF-32 el control de precision si se puede cambiar con el modo en marcha`() {
        val settings = CollectorSettings(autoTrigger = AutoTrigger.TIME, autoIntervalS = 3)
        val vm = autoViewModel(settings)
        receiving(vm)
        startAuto(vm)

        vm.setSettings(settings.copy(precision = PrecisionControl(fixOnly = false)))

        collectorPreferences.settings.value.precision.fixOnly shouldBe false
    }

    @Test
    fun `S017 RF-33 con el nombre del campo repetido no se inicia el modo automatico`() {
        val projectId = project()
        val vm = viewModel(projectId)
        receiving(vm)
        startMeasuring(vm)
        sendEpoch(2)
        await(vm) { it.savedName == "1" }
        vm.setName("1")
        await(vm) { it.blocker == MeasureBlocker.NAME_TAKEN }
        vm.setAutoEnabled(true)
        await(vm) { it.autoEnabled }

        vm.startAuto()

        runBlocking { kotlinx.coroutines.delay(200) }
        vm.state.value.auto.running shouldBe false
    }

    @Test
    fun `S017 RF-23 si el nombre siguiente ya existe el modo automatico usa el siguiente libre`() {
        val projectId = project()
        val vm = viewModel(projectId)
        receiving(vm)
        // Los puntos 1 y 3 a mano; el 2 lo pone el modo automático y el siguiente, el 3, ya existe.
        startMeasuring(vm)
        sendEpoch(2)
        await(vm) { it.savedName == "1" }
        vm.setName("3")
        startMeasuring(vm)
        sendEpoch(3)
        await(vm) { it.savedName == "3" }
        vm.setName("2")
        collectorPreferences.settings.value = CollectorSettings(autoTrigger = AutoTrigger.TIME, autoIntervalS = 2)
        await(vm) { it.settings.autoIntervalS == 2 && it.blocker == null }
        vm.setAutoEnabled(true)
        await(vm) { it.autoEnabled }

        startAuto(vm)
        (4..9).forEach { sendEpoch(it) }
        await(vm) { it.auto.saved >= 2 }

        val names = runBlocking { points.points(projectId).first() }.map { it.name }.toSet()
        names shouldContain "2"
        names shouldContain "4" // el 3 ya estaba
    }

    @Test
    fun `S017 RF-18 un promediado mas largo que el intervalo por tiempo no deja iniciar el modo automatico`() {
        val settings = CollectorSettings(MeasurementMode.AVERAGED, averagingSeconds = 10, autoTrigger = AutoTrigger.TIME, autoIntervalS = 5)
        val vm = autoViewModel(settings)
        receiving(vm, ready = false)

        await(vm) { it.blocker == MeasureBlocker.AUTO_INTERVAL_TOO_SHORT }
        vm.startAuto()

        runBlocking { kotlinx.coroutines.delay(200) }
        vm.state.value.auto.running shouldBe false
    }

    @Test
    fun `S017 RF-15 el modo automatico no se recuerda y un recolector nuevo abre en manual`() {
        val projectId = project()
        val vm = autoViewModel(CollectorSettings(autoTrigger = AutoTrigger.DISTANCE), projectId)

        vm.state.value.autoEnabled shouldBe true
        val reopened = viewModel(projectId)

        reopened.state.value.autoEnabled shouldBe false
        reopened.state.value.settings.autoTrigger shouldBe AutoTrigger.DISTANCE // el disparo, el intervalo y la distancia sí
    }
}
