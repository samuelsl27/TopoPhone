package es.topophone.app.ui.collector

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.log.RecordingLog
import es.topophone.app.ui.receiver.FakePreferences
import es.topophone.app.ui.receiver.repositoryRoot
import es.topophone.core.model.point.AutoTrigger
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.CollectorPreferences
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.receiver.platform.Alerts
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.transport.StreamTransport
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.math.hypot
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
 * Las reproducciones sintéticas de S017 por la `ReceiverSession` y el `CollectorViewModel` reales (`tools/nmea/generar_avanzado.py`,
 * con lo esperado calculado aparte con pyproj): un promediado de 10 min con un corte de 6 s, los cortes corto y largo, y una
 * traza andando con el modo automático por distancia y por tiempo. S017/RF-8 a RF-10, RF-12, RF-19 a RF-21.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AdvancedReplayTest {

    private val directory = File(repositoryRoot, "fixtures/nmea/sintetico")
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db)
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(
        sessionScope,
        NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE },
        noDataTimeoutMs = 60_000, // las épocas se mandan a mano: bajo carga, 2 s sin ninguna dejarían la sesión en "Sin datos"
        watchdogPeriodMs = 1_000,
    )
    private val toApp = PipedOutputStream()
    private val log = RecordingLog()
    private val collectorPreferences = object : CollectorPreferences {
        override val settings = MutableStateFlow(CollectorSettings())

        override suspend fun save(settings: CollectorSettings) {
            this.settings.value = settings
        }
    }
    private var omittedAlerts = 0
    private val alerts = object : Alerts {
        override fun connectionLost() = Unit

        override fun correctionsLost() = Unit

        override fun pointSaved() = Unit

        override fun solutionLost() = Unit

        override fun tiltLost() = Unit

        override fun pointOmitted() {
            omittedAlerts++
        }
    }
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

    /** Las frases del fichero agrupadas por época: cada grupo empieza en su GGA. */
    private fun epochs(name: String): List<String> {
        val groups = mutableListOf<StringBuilder>()
        File(directory, name).readText(Charsets.US_ASCII).split("\r\n").filter { it.isNotEmpty() }.forEach { line ->
            if (line.startsWith("\$GNGGA") || groups.isEmpty()) groups += StringBuilder()
            groups.last().append(line).append("\r\n")
        }
        return groups.map { it.toString() }
    }

    /** Las filas de datos de un CSV esperado, con las columnas por su nombre. */
    private fun expected(name: String): List<Map<String, String>> {
        val data = File(directory, name).readLines(Charsets.UTF_8).filter { it.isNotBlank() && !it.startsWith("#") }
        val header = data.first().split(',')
        return data.drop(1).map { header.zip(it.split(',')).toMap() }
    }

    private fun utcMillis(group: String): Int {
        val time = group.substringAfter("\$GNGGA,").substringBefore(',')
        return ((time.substring(0, 2).toInt() * 3_600 + time.substring(2, 4).toInt() * 60 + time.substring(4, 6).toInt()) * 1_000) +
            time.substring(7, 9).toInt() * 10
    }

    /**
     * Manda las épocas [from] a [to] (sin incluir) en bloques de 20, y tras cada uno espera a que la sesión haya cerrado la
     * anterior a la última: el flujo de épocas guarda 64 y nada se pierde. La última de todas queda abierta hasta la siguiente.
     */
    private fun send(groups: List<String>, from: Int, to: Int) {
        var index = from
        while (index < to) {
            val end = minOf(index + 20, to)
            (index until end).forEach { toApp.write(groups[it].toByteArray(Charsets.US_ASCII)) }
            toApp.flush()
            if (end >= 2) awaitSessionEpoch(utcMillis(groups[end - 2]))
            index = end
        }
    }

    private fun awaitSessionEpoch(utcMs: Int) = runBlocking {
        withTimeout(10_000) { session.state.first { it.epoch?.utcMillisOfDay == utcMs } }
    }

    private fun project(): String = runBlocking {
        (projects.create(ProjectDefaults().toDraft().copy(name = "Reproducción")) as SaveResult.Saved).id
    }

    private fun viewModel(settings: CollectorSettings, projectId: String): CollectorViewModel {
        collectorPreferences.settings.value = settings
        val vm = CollectorViewModel(
            projects,
            points,
            collectorPreferences,
            FakePreferences(),
            session,
            alerts,
            { ProjectPositionCalculator() },
            MeasurementInProgress(),
            log,
        )
        viewModels += vm
        vm.statsIntervalMs = 0
        vm.start(projectId)
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        await(vm) { it.loaded && it.settings.mode == settings.mode && it.settings.averagingSeconds == settings.averagingSeconds }
        runBlocking { withTimeout(10_000) { while (!vm.isProjectLoaded) kotlinx.coroutines.delay(10) } }
        return vm
    }

    private fun await(vm: CollectorViewModel, predicate: (CollectorUiState) -> Boolean): CollectorUiState = runBlocking {
        try {
            withTimeout(10_000) { vm.state.first(predicate) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("El recolector no llegó al estado esperado; se quedó en ${vm.state.value}; log ${log.messages}", e)
        }
    }

    private fun awaitStats(vm: CollectorViewModel, used: Int): AverageStatsUi = runBlocking {
        try {
            withTimeout(10_000) { vm.stats.first { it != null && it.snapshot.used == used }!! }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("Las estadísticas no llegaron a $used épocas; ${vm.stats.value}; ${vm.state.value}", e)
        }
    }

    /**
     * Conecta, manda las primeras [WARMUP] épocas (la última queda abierta) y espera a que "Medir" o "Iniciar" estén listos:
     * el recolector toma el intervalo del receptor de las épocas que ya ha visto.
     */
    private fun connect(vm: CollectorViewModel, groups: List<String>) {
        session.connect(StreamTransport("RS3 sintético", PipedInputStream(toApp, 256 * 1024), ByteArrayOutputStream()))
        send(groups, 0, WARMUP)
        await(vm) { it.live != null && it.blocker == null }
        // El recolector va por detrás de la sesión: hasta que ha visto la última época cerrada, la ventana del promediado no es fija.
        runBlocking { withTimeout(10_000) { while (vm.lastEpochUtcMs != utcMillis(groups[WARMUP - 2])) kotlinx.coroutines.delay(5) } }
    }

    private fun startMeasuring(vm: CollectorViewModel) = runBlocking {
        withTimeout(10_000) {
            while (!vm.state.value.measuring) {
                vm.measure()
                kotlinx.coroutines.delay(50)
            }
        }
    }

    private fun startAuto(vm: CollectorViewModel) = runBlocking {
        withTimeout(10_000) {
            while (!vm.state.value.auto.running) {
                vm.startAuto()
                kotlinx.coroutines.delay(50)
            }
        }
    }

    private companion object {
        const val WARMUP = 10
    }

    private fun later(afterMs: Long) = System.currentTimeMillis() + afterMs

    /** Las épocas mandadas menos las usadas al empezar a medir y mandar una más: la que estaba abierta al empezar. */
    private fun measureOffset(vm: CollectorViewModel, groups: List<String>): Int {
        startMeasuring(vm)
        send(groups, WARMUP, WARMUP + 1)
        sent = WARMUP + 1
        return WARMUP + 1 - runBlocking { withTimeout(10_000) { vm.stats.first { it != null && it.snapshot.used >= 1 }!!.snapshot.used } }
    }

    /** Manda hasta que quedan [n] épocas usadas y compara con la fila esperada de [n] (constitución nº 2: 0,1 mm). */
    private fun sendAndCheck(groups: List<String>, vm: CollectorViewModel, rows: Map<Int, Map<String, String>>, n: Int, offset: Int) {
        send(groups, sent, n + offset)
        sent = n + offset
        val stats = awaitStats(vm, n).snapshot
        val row = rows.getValue(n)
        stats.timeS shouldBe (row.getValue("tiempo_s").toDouble() plusOrMinus 1e-6)
        stats.meanEast shouldBe (row.getValue("media_e").toDouble() plusOrMinus 1e-4)
        stats.meanNorth shouldBe (row.getValue("media_n").toDouble() plusOrMinus 1e-4)
        stats.deviationHorizontalM.shouldNotBeNull() shouldBe (row.getValue("dev_h").toDouble() plusOrMinus 1e-4)
        stats.deviationVerticalM.shouldNotBeNull() shouldBe (row.getValue("dev_v").toDouble() plusOrMinus 1e-4)
    }

    /** Cuántos grupos de frases se han mandado ya. */
    private var sent = WARMUP + 1

    @Test
    fun `S017 RF-12 un promediado de 10 min con un corte de 6 s da la media y las desviaciones de pyproj`() {
        val groups = epochs("rs3-promediado-largo.nmea")
        val rows = expected("rs3-promediado-largo.medias.csv").associateBy { it.getValue("n").toInt() }
        val projectId = project()
        val vm = viewModel(CollectorSettings(MeasurementMode.AVERAGED, averagingSeconds = 3_600), projectId)
        connect(vm, groups)
        val offset = measureOffset(vm, groups)

        listOf(10, 600, 601, 3000).forEach { sendAndCheck(groups, vm, rows, it, offset) }
        // El corte de 6 s: sin épocas, pausa; al volver los datos sigue, sin contar el tiempo del corte.
        vm.tick(later(2_500))
        await(vm) { it.paused == PauseState.PAUSED }
        awaitStats(vm, 3000).snapshot.timeS shouldBe (300.0 plusOrMinus 1e-6)
        listOf(3001, 5939).forEach { sendAndCheck(groups, vm, rows, it, offset) }
        await(vm) { it.paused == null && it.measuring }

        vm.finishNow()
        await(vm) { it.savedName != null }.measuring shouldBe false
        val measurement = runBlocking { points.points(projectId).first { it.isNotEmpty() } }.single().measurement.shouldNotBeNull()
        val last = rows.getValue(5939)
        measurement.quality.epochsUsed shouldBe 5939
        measurement.quality.averagedTimeS.shouldNotBeNull() shouldBe (593.9 plusOrMinus 1e-6)
        measurement.quality.deviationHorizontalM.shouldNotBeNull() shouldBe (last.getValue("dev_h").toDouble() plusOrMinus 1e-4)
        measurement.quality.deviationVerticalM.shouldNotBeNull() shouldBe (last.getValue("dev_v").toDouble() plusOrMinus 1e-4)
        measurement.coordinates.easting.shouldNotBeNull() shouldBe (last.getValue("media_e").toDouble() plusOrMinus 1e-4)
        measurement.coordinates.northing.shouldNotBeNull() shouldBe (last.getValue("media_n").toDouble() plusOrMinus 1e-4)
        // Las frases de todas las épocas usadas, en tramos fuera de la fila del punto (S017/RF-12).
        measurement.nmeaChunks shouldBeGreaterThanOrEqual 1
        omittedAlerts shouldBe 0
    }

    @Test
    fun `S017 RF-8, RF-9 y RF-10 el corte corto reanuda solo y el de 6 min pregunta y al volver los datos se cierra`() {
        val groups = epochs("rs3-corte.nmea")
        expected("rs3-corte.tramos.csv").map { it.getValue("desenlace") } shouldBe listOf("inicio", "pausa-y-reanuda", "pausa-larga")
        val projectId = project()
        val vm = viewModel(CollectorSettings(MeasurementMode.AVERAGED, averagingSeconds = 3_600), projectId)
        connect(vm, groups)
        val offset = measureOffset(vm, groups)
        send(groups, sent, 100)
        awaitStats(vm, 100 - offset)

        // Corte corto (4 s): pausa, y al volver los datos reanuda sola sin descartar nada ni contar el corte.
        vm.tick(later(2_500))
        await(vm) { it.paused == PauseState.PAUSED }
        send(groups, 100, 200)
        awaitStats(vm, 200 - offset).snapshot.timeS shouldBe ((200 - offset) * 0.2 plusOrMinus 1e-6)
        await(vm) { it.paused == null && it.measuring }

        // Corte largo (6 min): a los 5 min pregunta; al volver los datos la pregunta se cierra y sigue.
        vm.tick(later(2_500))
        await(vm) { it.paused == PauseState.PAUSED }
        vm.tick(later(301_000))
        await(vm) { it.paused == PauseState.LONG_PAUSE }
        send(groups, 200, groups.size)
        awaitStats(vm, groups.size - offset)
        await(vm) { it.paused == null && it.measuring }

        vm.finishNow()
        await(vm) { it.savedName != null }
        val measurement = runBlocking { points.points(projectId).first { it.isNotEmpty() } }.single().measurement.shouldNotBeNull()
        measurement.quality.epochsUsed shouldBe groups.size - offset
        measurement.quality.averagedTimeS.shouldNotBeNull() shouldBe ((groups.size - offset) * 0.2 plusOrMinus 1e-6)
    }

    /** Los puntos guardados de la traza con su Este y Norte, por orden. */
    private fun savedPoints(projectId: String, count: Int): List<Pair<Double, Double>> = runBlocking {
        withTimeout(10_000) { points.points(projectId).first { it.size >= count } }
    }.sortedBy { it.measurement?.measuredToMs }.map {
        val c = it.measurement.shouldNotBeNull().coordinates
        c.easting.shouldNotBeNull() to c.northing.shouldNotBeNull()
    }

    /** El índice de la época esperada más cercana a ([east], [north]) y a qué distancia queda. */
    private fun nearest(rows: List<Map<String, String>>, east: Double, north: Double): Pair<Int, Double> =
        rows.indices.map { it to hypot(rows[it].getValue("este").toDouble() - east, rows[it].getValue("norte").toDouble() - north) }
            .minBy { it.second }

    @Test
    fun `S017 RF-19 y RF-21 por distancia cada punto cae a 10 m o mas del anterior y a la distancia esperada`() {
        val groups = epochs("rs3-traza.nmea")
        val rows = expected("rs3-traza.disparos.csv")
        val projectId = project()
        val vm = viewModel(CollectorSettings(autoTrigger = AutoTrigger.DISTANCE, autoDistanceM = 10.0), projectId)
        vm.setAutoEnabled(true)
        await(vm) { it.autoEnabled }
        connect(vm, groups)
        startAuto(vm)
        send(groups, WARMUP, groups.size)
        await(vm) { it.auto.saved >= 10 }
        vm.stopAuto()
        val summary = await(vm) { !it.auto.running }.auto.summary.shouldNotBeNull()

        val saved = savedPoints(projectId, summary.saved)
        saved.size shouldBe summary.saved
        // Cada punto es el suelo de una época de la traza (a 2 mm de la esperada con pyproj).
        val indices = saved.map { nearest(rows, it.first, it.second) }
        indices.forEach { it.second shouldBeLessThan 0.002 }
        val at = indices.map { it.first }
        // El primero, de inmediato (RF-19): el de la primera época o la siguiente.
        at.first() shouldBeLessThanOrEqual WARMUP + 1
        // Cada uno a 10 m o más, en planta, del anterior guardado, y en la primera época que lo cumple o la siguiente (RF-21);
        // tras la pérdida de FIX se omite el punto (precisión) y se mide en la primera época con FIX.
        fun distance(i: Int, j: Int) = hypot(
            rows[j].getValue("este").toDouble() - rows[i].getValue("este").toDouble(),
            rows[j].getValue("norte").toDouble() - rows[i].getValue("norte").toDouble(),
        )
        at.zipWithNext().forEach { (i, j) ->
            distance(i, j) shouldBeGreaterThanOrEqual 10.0 - 0.002
            val firstWithFix = (i + 1 until rows.size).first { distance(i, it) >= 10.0 && rows[it].getValue("estado") == "FIX" }
            (j - firstWithFix) shouldBeLessThanOrEqual 1
            (j - firstWithFix) shouldBeGreaterThanOrEqual 0
        }
        // Un punto cada 10 m a lo largo de 144 m: entre 12 y 15.
        saved.size shouldBeGreaterThanOrEqual 12
        saved.size shouldBeLessThanOrEqual 15
        // La pérdida de FIX omite sin guardar y avisa una sola vez hasta que vuelve a cumplir (RF-24).
        summary.omitted shouldBeGreaterThanOrEqual 1
        omittedAlerts shouldBe 1
    }

    @Test
    fun `S017 RF-20 por tiempo mide un punto cada 30 s de reloj de las epocas aunque la reproduccion vaya mas deprisa`() {
        val groups = epochs("rs3-traza.nmea")
        val rows = expected("rs3-traza.disparos.csv")
        val projectId = project()
        val vm = viewModel(CollectorSettings(autoTrigger = AutoTrigger.TIME, autoIntervalS = 30), projectId)
        vm.setAutoEnabled(true)
        await(vm) { it.autoEnabled }
        connect(vm, groups)
        startAuto(vm)
        send(groups, WARMUP, groups.size)
        await(vm) { it.auto.saved >= 4 }
        vm.stopAuto()
        await(vm) { !it.auto.running }

        val saved = savedPoints(projectId, 4)
        saved shouldHaveSize 4
        // El primero, de inmediato (RF-19); luego uno cada 30 s de la hora de las épocas, 150 épocas a 5 Hz, desde el anterior (RF-20).
        val at = saved.map { nearest(rows, it.first, it.second) }.onEach { it.second shouldBeLessThan 0.002 }.map { it.first }
        at.first() shouldBeLessThanOrEqual WARMUP + 1
        at.zipWithNext().forEach { (i, j) -> kotlin.math.abs(j - i - 150) shouldBeLessThanOrEqual 1 }
    }
}
