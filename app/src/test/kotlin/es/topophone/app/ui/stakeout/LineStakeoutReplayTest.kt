package es.topophone.app.ui.stakeout

import es.topophone.app.ui.receiver.repositoryRoot
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.LineStakeoutMode
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.StakeMode
import es.topophone.core.model.point.StakeoutSettings
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.LineLocator
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.core.survey.StakeLine
import es.topophone.receiver.platform.Alerts
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.transport.StreamTransport
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
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
 * La reproducción sintética `rs3-linea` (`tools/nmea/generar_replanteo_linea_rs3.py`, con lo esperado calculado aparte desde la
 * GGA) por la `ReceiverSession` y la guía reales: 90 m caminando paralelo a una línea de tres tramos a 1,5 m a la derecha y luego
 * a 2,0 m a la izquierda, con 4 s sin FIX y el final pasado. Época a época: el PK, la distancia lateral, el error lateral, la
 * posición respecto a la línea, la distancia al objetivo, la zona y los avisos (S041/RF-10, RF-11, RF-13, RF-14, RF-15, RF-26).
 */
class LineStakeoutReplayTest {

    private val directory = File(repositoryRoot, "fixtures/nmea/sintetico")
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(
        sessionScope,
        NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE },
        noDataTimeoutMs = 60_000, // las épocas se mandan a mano: bajo carga, 2 s sin ninguna dejarían la sesión en "Sin datos"
        watchdogPeriodMs = 1_000,
    )
    private val toApp = PipedOutputStream()
    private val events = mutableListOf<String>()
    private val alerts = object : Alerts {
        override fun connectionLost() = Unit

        override fun correctionsLost() = Unit

        override fun pointSaved() = Unit

        override fun solutionLost() {
            synchronized(events) { events += "PERDIDO" }
        }

        override fun tiltLost() = Unit

        override fun stakeoutApproach() {
            synchronized(events) { events += "APPROACH" }
        }

        override fun stakeoutArrived() {
            synchronized(events) { events += "ARRIVED" }
        }

        override fun stakeoutLeft() {
            synchronized(events) { events += "LEFT" }
        }
    }

    @After
    fun tearDown() {
        runCatching { toApp.close() }
        session.disconnect()
        sessionScope.cancel()
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

    private fun utcMillis(group: String): Int {
        val time = group.substringAfter("\$GNGGA,").substringBefore(',')
        return ((time.substring(0, 2).toInt() * 3_600 + time.substring(2, 4).toInt() * 60 + time.substring(4, 6).toInt()) * 1_000) +
            time.substring(7, 9).toInt() * 10
    }

    /** Manda las épocas en bloques de 20 y espera a que la sesión haya cerrado la anterior a la última (el flujo guarda 64). */
    private fun send(groups: List<String>) {
        var index = 0
        while (index < groups.size) {
            val end = minOf(index + 20, groups.size)
            (index until end).forEach { toApp.write(groups[it].toByteArray(Charsets.US_ASCII)) }
            toApp.flush()
            if (end >= 2) {
                runBlocking { withTimeout(10_000) { session.state.first { it.epoch?.utcMillisOfDay == utcMillis(groups[end - 2]) } } }
            }
            index = end
        }
    }

    private class Row(val cells: Map<String, String>) {
        operator fun get(column: String): String = cells.getValue(column)
    }

    private fun expected(): Pair<List<Row>, List<Pair<Double, Double>>> {
        val lines = File(directory, "rs3-linea.guia.csv").readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        val vertices = lines.first { it.startsWith("# linea:") }.removePrefix("# linea: ").split(' ')
            .map { pair -> pair.substringBefore(',').toDouble() to pair.substringAfter(',').toDouble() }
        val data = lines.filter { !it.startsWith("#") }
        val header = data.first().split(',')
        return data.drop(1).map { line -> Row(header.zip(line.split(',')).toMap()) } to vertices
    }

    @Test
    fun `S041 RF-10 RF-11 RF-13 RF-14 RF-15 y RF-26 la guia sigue la reproduccion rs3-linea epoca a epoca`() {
        val groups = epochs("rs3-linea.nmea")
        val (rows, vertices) = expected()
        val project = Project(
            id = "p1",
            name = "Replanteo de una línea",
            author = "",
            description = "",
            crs = ProjectCrs.ETRS89_UTM30,
            linearUnit = LinearUnit.METRE,
            angularUnit = AngularUnit.DECIMAL_DEGREES,
            correctionsDatum = CorrectionsDatum.ETRS89,
            folderId = null,
            createdAtMs = 1_000,
            modifiedAtMs = 1_000,
            openedAtMs = null,
            deletedAtMs = null,
            heightSystem = HeightSystem.ELLIPSOIDAL,
        )
        val line = StakeLine.create(
            DoubleArray(vertices.size) { vertices[it].first },
            DoubleArray(vertices.size) { vertices[it].second },
            null,
        ).shouldNotBeNull()
        val locator = LineLocator(line)
        // Solo importan el nombre y el origen del objetivo: el objetivo de cada época lo da la línea.
        val ground = GeodeticPosition(40.4168, -3.7038, 697.0)
        val target = StakeTarget("cad:l1:1:0", "Eje", "", PointCoordinates(ground, vertices[0].first, vertices[0].second, null))
        val calculator = ProjectPositionCalculator()
        val controller = StakeoutController(alerts, publishIntervalMs = 0)
        val settings = StakeoutSettings(StakeMode.BY_DISTANCE)
        val states = mutableListOf<GuidanceState>()
        var index = 0
        val collecting = CoroutineScope(Dispatchers.Unconfined).launch {
            session.epochs.collect { received ->
                // El desplazamiento que se pide va cambiando como en la reproducción: el que dice la fila de esta época.
                val offset = rows[index.coerceAtMost(rows.size - 1)]["desplazamiento_m"].toDouble()
                val guide = LineGuide(line, locator, LineStakeoutMode.ALONG, offset)
                val context = StakeContext(settings, target, true, null, 1.8, project, calculator, null, guide)
                controller.onEpoch(received.epoch, context)
                index++
                synchronized(states) { states += controller.state.value }
            }
        }
        session.connect(StreamTransport("RS3 sintético", PipedInputStream(toApp, 256 * 1024), ByteArrayOutputStream()))
        send(groups)
        // La última época queda abierta hasta la siguiente: se comparan todas las demás.
        val closed = groups.size - 1
        runBlocking { withTimeout(20_000) { while (synchronized(states) { states.size } < closed) delay(10) } }
        collecting.cancel()

        val positions = mapOf(
            "EN_LINEA" to es.topophone.core.survey.RangePosition.WITHIN,
            "ANTES_INICIO" to es.topophone.core.survey.RangePosition.BEFORE,
            "DESPUES_FINAL" to es.topophone.core.survey.RangePosition.AFTER,
        )
        synchronized(states) { states.toList() }.take(closed).forEachIndexed { i, state ->
            val row = rows[i]
            if (row["guia"] == "NO_FIX") {
                state.shouldBeInstanceOf<GuidanceState.Off>().reason shouldBe GuidanceOff.NO_FIX
                return@forEachIndexed
            }
            val ui = state.shouldBeInstanceOf<GuidanceState.On>().ui
            val objective = ui.line.shouldNotBeNull()
            ui.solution shouldBe SolutionStatus.FIX
            objective.position shouldBe positions.getValue(row["posicion"])
            objective.chainageM shouldBe (row["pk_m"].toDouble() plusOrMinus 2e-3)
            ui.guidance.distanceM shouldBe (row["distancia_m"].toDouble() plusOrMinus 2e-3)
            ui.zone.name shouldBe row["zona"]
            if (row["lateral_m"].isEmpty()) {
                objective.lateralErrorM.shouldBeNull()
            } else {
                objective.location.lateralM shouldBe (row["lateral_m"].toDouble() plusOrMinus 2e-3)
                objective.lateralErrorM.shouldNotBeNull() shouldBe (row["error_lateral_m"].toDouble() plusOrMinus 2e-3)
            }
        }
        // Los avisos, en su orden: sin FIX, aproximación, llegada y salida.
        val got = synchronized(events) { events.toList() }
        got shouldBe rows.take(closed).map { it["aviso"] }.filter { it.isNotEmpty() }
        got shouldHaveAtLeastSize 4
    }
}
