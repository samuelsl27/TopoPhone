package es.topophone.app.ui.baseshift

import es.topophone.app.ui.receiver.repositoryRoot
import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.model.point.AdmittedSolutions
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.BaseShiftApplier
import es.topophone.core.survey.BaseShiftCalculator
import es.topophone.core.survey.BaseShiftResult
import es.topophone.core.survey.KnownPoint
import es.topophone.core.survey.MeasurementUpdate
import es.topophone.core.survey.PointMeasurement
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.session.SessionEpoch
import es.topophone.receiver.transport.StreamTransport
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test

/**
 * La reproducción sintética `rs3-base-shift` (`tools/base_shift/generar_replay_base_shift.py`, con lo esperado calculado aparte con
 * pyproj) por la `ReceiverSession` real: se mide el vértice conocido K, se calcula el desplazamiento, se miden A y B con él, se
 * desactiva y se mide C sin él (S020/RF-2 a RF-4, RF-7, RF-11).
 */
class BaseShiftReplayTest {

    private val directory = File(repositoryRoot, "fixtures/nmea/sintetico")
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(
        sessionScope,
        NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE },
        noDataTimeoutMs = 60_000,
        watchdogPeriodMs = 1_000,
    )
    private val toApp = PipedOutputStream()
    private val calculator = ProjectPositionCalculator()
    private val crs = ProjectCrs.ETRS89_UTM30
    private val heights = HeightSystem.ELLIPSOIDAL

    @After
    fun tearDown() {
        runCatching { toApp.close() }
        session.disconnect()
        sessionScope.cancel()
    }

    private fun groups(): List<String> {
        val out = mutableListOf<StringBuilder>()
        File(directory, "rs3-base-shift.nmea").readText(Charsets.US_ASCII).split("\r\n").filter { it.isNotEmpty() }.forEach { line ->
            if (line.startsWith("\$GNGGA") || out.isEmpty()) out += StringBuilder()
            out.last().append(line).append("\r\n")
        }
        return out.map { it.toString() }
    }

    private fun utcMillis(group: String): Int {
        val time = group.substringAfter("\$GNGGA,").substringBefore(',')
        return ((time.substring(0, 2).toInt() * 3_600 + time.substring(2, 4).toInt() * 60 + time.substring(4, 6).toInt()) * 1_000) +
            time.substring(7, 9).toInt() * 10
    }

    private class Stop(val name: String, val cells: Map<String, Double>)

    private fun expected(): List<Stop> {
        val data = File(directory, "rs3-base-shift.desplazamiento.csv").readLines(Charsets.UTF_8).filter {
            it.isNotBlank() &&
                !it.startsWith("#")
        }
        val header = data.first().split(',')
        return data.drop(1).map { line ->
            val cells = line.split(',')
            Stop(cells[0], header.zip(cells).drop(2).associate { (k, v) -> k to v.toDouble() })
        }
    }

    /** Las épocas de la reproducción, tal como las ve la sesión (la última queda abierta hasta la siguiente y no se cuenta). */
    private fun playedEpochs(): List<SessionEpoch> {
        val groups = groups()
        val received = mutableListOf<SessionEpoch>()
        val collecting = CoroutineScope(Dispatchers.Unconfined).launch {
            session.epochs.collect { synchronized(received) { received += it } }
        }
        session.connect(StreamTransport("RS3 sintético", PipedInputStream(toApp, 256 * 1024), ByteArrayOutputStream()))
        groups.forEach { toApp.write(it.toByteArray(Charsets.US_ASCII)) }
        toApp.flush()
        runBlocking {
            withTimeout(10_000) { session.state.first { it.epoch?.utcMillisOfDay == utcMillis(groups[groups.size - 2]) } }
            withTimeout(10_000) { while (synchronized(received) { received.size } < groups.size - 1) delay(10) }
        }
        collecting.cancel()
        return synchronized(received) { received.toList() }
    }

    private fun measure(
        epochs: List<Epoch>,
        shift: es.topophone.core.model.point.BaseShiftVector?,
    ): es.topophone.core.survey.MeasurementResult {
        val measurement = PointMeasurement(
            MeasurementMode.INSTANT,
            1,
            AdmittedSolutions.FIX_ONLY,
            poleHeightM = 1.8,
            crs = crs,
            heightSystem = heights,
            epochIntervalS = 0.2,
            startedAtMs = 0L,
            calculator = calculator,
            shift = shift,
        )
        var now = 0L
        for (epoch in epochs) {
            now += 200
            val done = measurement.onEpoch(epoch, now, epoch.gst).filterIsInstance<MeasurementUpdate.Done>().firstOrNull()
            if (done != null) return done.result
        }
        error("La medida no terminó")
    }

    @Test
    fun `S020 RF-2 a RF-4 y RF-7 la reproduccion rs3-base-shift da el vector y los puntos desplazados esperados`() {
        val stops = expected()
        val epochs = playedEpochs().map { it.epoch }
        val perStop = EPOCHS_PER_STOP

        // K: el vértice conocido, medido sin desplazamiento; el vector sale de su primera época.
        val k = measure(epochs.subList(0, perStop), null)
        val known = stops[0].cells
        val computed = BaseShiftCalculator(es.topophone.core.survey.ManualCoordinates(calculator), calculator).compute(
            KnownPoint(known.getValue("conocido_este"), known.getValue("conocido_norte"), known.getValue("conocido_h")),
            GeodeticPosition(k.ground.latitudeDeg, k.ground.longitudeDeg, k.ground.ellipsoidalHeightM),
            crs,
            heights,
            acceptHorizontalOnly = false,
        ).shouldBeInstanceOf<BaseShiftResult.Computed>()

        computed.vector.dx shouldBe (known.getValue("dx") plusOrMinus 0.001)
        computed.vector.dy shouldBe (known.getValue("dy") plusOrMinus 0.001)
        computed.vector.dz shouldBe (known.getValue("dz") plusOrMinus 0.001)

        // A y B con el desplazamiento activo; C sin él, tras desactivarlo.
        stops.drop(1).forEachIndexed { index, stop ->
            val from = (index + 1) * perStop
            val result = measure(epochs.subList(from, minOf(from + perStop, epochs.size)), if (stop.name == "C") null else computed.vector)
            val position = calculator.fromGround(result.ground, crs, heights)
            position.easting.shouldBeNear(stop.cells.getValue("este_d"))
            position.northing.shouldBeNear(stop.cells.getValue("norte_d"))
            position.height.shouldBeNear(stop.cells.getValue("h_d"))
            if (stop.name != "C") {
                BaseShiftApplier.apply(result.unshiftedGround, computed.vector) shouldBe result.ground
            } else {
                result.ground shouldBe result.unshiftedGround
            }
        }
    }

    private companion object {
        /** Las épocas de cada parada de `rs3-base-shift` (tools/base_shift/generar_replay_base_shift.py). */
        const val EPOCHS_PER_STOP = 6
    }

    private fun Double?.shouldBeNear(expected: Double) {
        (this ?: Double.NaN) shouldBe (expected plusOrMinus 0.001)
    }
}
