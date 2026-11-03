package es.topophone.app.ui.linear

import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.ProjectPosition
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.transport.StreamTransport
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

/**
 * Pasa una reproducción NMEA por la `ReceiverSession` real y da la posición del suelo de cada época en el sistema del proyecto (ETRS89
 * UTM 30 con alturas elipsoidales), con el jalón de 1,8 m y la antena del RS3 de siempre (S052, reproducciones). Es lo que el móvil ve de
 * cada época antes de proyectarla sobre el eje o la sección.
 */
class ReplayHarness {
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(
        sessionScope,
        NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE },
        noDataTimeoutMs = 60_000, // las épocas se mandan a mano
        watchdogPeriodMs = 1_000,
    )
    private val toApp = PipedOutputStream()
    private val calculator = ProjectPositionCalculator()

    fun close() {
        runCatching { toApp.close() }
        session.disconnect()
        sessionScope.cancel()
    }

    /** Las frases del fichero agrupadas por época: cada grupo empieza en su GGA. */
    private fun epochs(file: File): List<String> {
        val groups = mutableListOf<StringBuilder>()
        file.readText(Charsets.US_ASCII).split("\r\n").filter { it.isNotEmpty() }.forEach { line ->
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
     * Las posiciones de todas las épocas menos la última, que la sesión deja abierta hasta la siguiente. [expected] es cuántas épocas
     * tiene el fichero, para comprobar que no falta ninguna.
     */
    fun positions(nmea: File, expected: Int): List<ProjectPosition?> {
        val groups = epochs(nmea)
        check(groups.size == expected) { "El fichero tiene ${groups.size} épocas y se esperaban $expected" }
        val positions = mutableListOf<ProjectPosition?>()
        val collecting = CoroutineScope(Dispatchers.Unconfined).launch {
            session.epochs.collect { received ->
                val fix = received.epoch.gga
                val position = fix?.let { calculator.compute(it, POLE_HEIGHT_M, ProjectCrs.ETRS89_UTM30, HeightSystem.ELLIPSOIDAL) }
                synchronized(positions) { positions += position }
            }
        }
        session.connect(StreamTransport("RS3 sintético", PipedInputStream(toApp, 256 * 1024), ByteArrayOutputStream()))
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
        runBlocking { withTimeout(20_000) { while (synchronized(positions) { positions.size } < groups.size - 1) delay(10) } }
        collecting.cancel()
        return synchronized(positions) { positions.take(groups.size - 1) }
    }

    private companion object {
        const val POLE_HEIGHT_M = 1.8
    }
}
