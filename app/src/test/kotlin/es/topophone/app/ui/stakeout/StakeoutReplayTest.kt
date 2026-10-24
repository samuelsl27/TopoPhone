package es.topophone.app.ui.stakeout

import es.topophone.app.ui.receiver.repositoryRoot
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.HeadingChoice
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
import es.topophone.core.survey.HeadingResult
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.receiver.platform.Alerts
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.transport.StreamTransport
import io.kotest.matchers.collections.shouldHaveSize
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
 * La reproducción sintética `rs3-replanteo` (`tools/nmea/generar_replanteo_rs3.py`, con lo esperado calculado aparte desde la
 * GGA) por la `ReceiverSession` y la guía reales: 40 m andando hacia el diseño con 10 s sin FIX, una parada dentro de la
 * tolerancia y una salida. Época a época: la distancia, las diferencias, la zona, los avisos, el rumbo de marcha y la guía
 * apagada (S018/RF-6, RF-9, RF-20, RF-21, RF-22).
 */
class StakeoutReplayTest {

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

    /**
     * Manda las épocas en bloques de 20, y tras cada uno espera a que la sesión haya cerrado la anterior a la última: el flujo de
     * épocas guarda 64 y nada se pierde. La última de todas queda abierta hasta la siguiente.
     */
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

    private fun expected(): Pair<List<Row>, Triple<Double, Double, Double>> {
        val lines = File(directory, "rs3-replanteo.guia.csv").readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        val design = lines.first { it.startsWith("# diseno:") }.removePrefix("# diseno: ").substringBefore(';').split(' ')
            .associate { it.substringBefore('=') to it.substringAfter('=').toDouble() }
        val data = lines.filter { !it.startsWith("#") }
        val header = data.first().split(',')
        val rows = data.drop(1).map { line -> Row(header.zip(line.split(',')).toMap()) }
        return rows to Triple(design.getValue("este"), design.getValue("norte"), design.getValue("h"))
    }

    @Test
    fun `S018 RF-6 RF-9 RF-20 RF-21 y RF-22 la guia sigue la reproduccion rs3-replanteo epoca a epoca`() {
        val groups = epochs("rs3-replanteo.nmea")
        val (rows, design) = expected()
        val project = Project(
            id = "p1",
            name = "Replanteo",
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
            heightSystem = HeightSystem.ELLIPSOIDAL, // la cota esperada es la elipsoidal del suelo
        )
        // Solo importan el Este, el Norte y la cota del diseño: la guía de un SC proyectado no usa las geográficas.
        val ground = GeodeticPosition(40.4168, -3.7038, design.third)
        val target = StakeTarget("d1", "12", "", PointCoordinates(ground, design.first, design.second, design.third))
        val context = StakeContext(
            StakeoutSettings(StakeMode.BY_DIRECTION, HeadingChoice.WALKING),
            target,
            fixOnly = true,
            tiltMode = null,
            poleHeightM = 1.8,
            project = project,
            calculator = ProjectPositionCalculator(),
        )
        val controller = StakeoutController(alerts, publishIntervalMs = 0)
        val states = mutableListOf<GuidanceState>()
        val collecting = CoroutineScope(Dispatchers.Unconfined).launch {
            session.epochs.collect { received ->
                controller.onEpoch(received.epoch, context)
                synchronized(states) { states += controller.state.value }
            }
        }
        session.connect(StreamTransport("RS3 sintético", PipedInputStream(toApp, 256 * 1024), ByteArrayOutputStream()))
        send(groups)
        // La última época queda abierta hasta la siguiente: se comparan todas las demás.
        val closed = groups.size - 1
        runBlocking { withTimeout(10_000) { while (synchronized(states) { states.size } < closed) delay(10) } }
        collecting.cancel()

        synchronized(states) { states.toList() }.take(closed).forEachIndexed { i, state ->
            val row = rows[i]
            if (row["guia"] == "NO_FIX") {
                state.shouldBeInstanceOf<GuidanceState.Off>().reason shouldBe GuidanceOff.NO_FIX
                return@forEachIndexed
            }
            val ui = state.shouldBeInstanceOf<GuidanceState.On>().ui
            ui.solution shouldBe SolutionStatus.FIX
            ui.guidance.distanceM shouldBe (row["distancia_m"].toDouble() plusOrMinus 1e-4)
            ui.guidance.dEastM shouldBe (row["d_este_m"].toDouble() plusOrMinus 1e-4)
            ui.guidance.dNorthM shouldBe (row["d_norte_m"].toDouble() plusOrMinus 1e-4)
            ui.guidance.dHeightM.shouldNotBeNull() shouldBe (row["d_cota_m"].toDouble() plusOrMinus 1e-4)
            ui.zone.name shouldBe row["zona"]
            val heading = ui.heading
            if (row["rumbo_marcha_grados"].isEmpty()) {
                heading.shouldBeInstanceOf<HeadingResult.Unavailable>()
                ui.guidance.aheadM.shouldBeNull()
            } else {
                val available = heading.shouldBeInstanceOf<HeadingResult.Available>()
                Math.toDegrees(available.rad) shouldBe (row["rumbo_marcha_grados"].toDouble() plusOrMinus 1e-3)
                available.ageS shouldBe (row["rumbo_edad_s"].toDouble() plusOrMinus 1e-6)
            }
        }
        // Los avisos, en su orden: sin FIX, aproximación, llegada y salida. Una época sola cerrada de más no añade ninguno.
        synchronized(events) { events.toList() } shouldBe rows.take(closed).map { it["aviso"] }.filter { it.isNotEmpty() }
        synchronized(events) { events.toList() } shouldHaveSize 4
    }
}
