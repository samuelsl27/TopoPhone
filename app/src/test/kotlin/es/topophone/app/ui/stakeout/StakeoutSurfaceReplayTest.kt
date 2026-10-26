package es.topophone.app.ui.stakeout

import es.topophone.app.ui.receiver.repositoryRoot
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.point.ElevationSource
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.StakeoutSettings
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.surface.Surface
import es.topophone.core.model.surface.SurfaceExtent
import es.topophone.core.model.surface.SurfaceGuide
import es.topophone.core.model.surface.SurfaceHeight
import es.topophone.core.model.surface.SurfaceHeightSource
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.core.survey.TinDirectory
import es.topophone.core.survey.TinElevation
import es.topophone.core.survey.TinTiler
import es.topophone.receiver.platform.Alerts
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.transport.StreamTransport
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import javax.xml.parsers.DocumentBuilderFactory
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
import org.w3c.dom.Element
/**
 * La reproducción sintética `rs3-superficie` (`tools/nmea/generar_superficie_rs3.py`, con lo esperado calculado aparte desde la GGA y el
 * propio LandXML) por la `ReceiverSession` y la guía reales, con la superficie repartida en teselas como la importación: el recorrido
 * empieza fuera de la superficie, la cruza, atraviesa un hueco, pierde el FIX 4 s y sale por el otro lado. Época a época: la cota de la
 * superficie en la punta, «Fuera de la superficie», la diferencia de cota con el desplazamiento vertical, el nivel y sus avisos
 * (S042/RF-22 a RF-26).
 */
class StakeoutSurfaceReplayTest {

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

        override fun stakeoutApproach() = Unit

        override fun stakeoutArrived() = Unit

        override fun stakeoutLeft() = Unit

        override fun stakeoutLevelArrived() {
            synchronized(events) { events += "LEVEL_ARRIVED" }
        }

        override fun stakeoutLevelLeft() {
            synchronized(events) { events += "LEVEL_LEFT" }
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

    /** Manda las épocas en bloques de 20 y tras cada uno espera a que la sesión haya cerrado la anterior a la última. */
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

    private fun expected(): List<Row> {
        val data = File(directory, "rs3-superficie.nivel.csv").readLines(Charsets.UTF_8).filter { it.isNotBlank() && !it.startsWith("#") }
        val header = data.first().split(',')
        return data.drop(1).map { line -> Row(header.zip(line.split(',')).toMap()) }
    }

    /** El «terreno» del LandXML de ejemplo, leído aquí con el XML de la JVM y repartido en teselas como la importación. */
    private fun terrainSource(): SurfaceHeightSource {
        val xml = File(repositoryRoot, "fixtures/superficies/dos-superficies.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
        val surfaces = document.getElementsByTagName("Surface")
        val terrain = (0 until surfaces.length).map { surfaces.item(it) as Element }.first { it.getAttribute("name") == "terreno" }
        val points = terrain.getElementsByTagName("P")
        val position = HashMap<String, Int>()
        val e = DoubleArray(points.length)
        val n = DoubleArray(points.length)
        val z = DoubleArray(points.length)
        for (i in 0 until points.length) {
            val p = points.item(i) as Element
            val values = p.textContent.trim().split(Regex("\\s+")).map { it.toDouble() }
            position[p.getAttribute("id")] = i
            n[i] = values[0]
            e[i] = values[1]
            z[i] = values[2]
        }
        val faces = terrain.getElementsByTagName("F")
        val triangles = ArrayList<Int>()
        for (i in 0 until faces.length) {
            val f = faces.item(i) as Element
            if (f.getAttribute("i") != "1") f.textContent.trim().split(Regex("\\s+")).forEach { triangles += position.getValue(it) }
        }
        val cells = TinTiler.tile(e, n, z, triangles.toIntArray(), maxTriangles = 8)
        val index = TinDirectory(cells.map { it.region })
        return SurfaceHeightSource { east, north ->
            val found = index.candidates(east, north).asList().firstNotNullOfOrNull { TinElevation.at(cells[it].tile, east, north) }
            if (found == null) SurfaceHeight.Outside else SurfaceHeight.Value(found)
        }
    }

    private fun project() = Project(
        id = "p1",
        name = "Superficie",
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

    @Test
    fun `S042 RF-22 a RF-26 la guia sigue la reproduccion rs3-superficie epoca a epoca`() {
        val groups = epochs("rs3-superficie.nmea")
        val rows = expected()
        val surface = Surface(
            "s1", "p1", "terreno", "dos-superficies.xml", 1L, CadSourceCrs.Project, LinearUnit.METRE,
            SurfaceExtent(440_290.0, 440_412.0, 4_474_256.0, 4_474_360.0), 600.0, 608.7, 42, 58, 2,
        )
        val ground = GeodeticPosition(40.4168, -3.7038, 700.0)
        val target = StakeTarget("d1", "12", "", PointCoordinates(ground, 440_290.0, 4_474_257.0, null))
        val context = StakeContext(
            StakeoutSettings(),
            target,
            fixOnly = true,
            tiltMode = null,
            poleHeightM = 1.8,
            project = project(),
            calculator = ProjectPositionCalculator(),
            surface = SurfaceGuide(surface, terrainSource(), verticalOffsetM = 0.3),
            elevationSource = ElevationSource.SURFACE,
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
                // S019/RF-11: sin FIX, ni guía ni nivel.
                state.shouldBeInstanceOf<GuidanceState.Off>().reason shouldBe GuidanceOff.NO_FIX
                return@forEachIndexed
            }
            val ui = state.shouldBeInstanceOf<GuidanceState.On>().ui
            val surfaceUi = ui.surface.shouldNotBeNull()
            if (row["estado_sup"] == "FUERA") {
                // S042/RF-23: fuera del contorno o en el hueco no hay cota ni nivel, y la guía horizontal sigue.
                surfaceUi.height shouldBe SurfaceHeight.Outside
                ui.level.shouldBeNull()
                ui.levelUnavailable shouldBe LevelUnavailable.OUTSIDE_SURFACE
                return@forEachIndexed
            }
            surfaceUi.height.shouldBeInstanceOf<SurfaceHeight.Value>().metres shouldBe (row["cota_sup"].toDouble() plusOrMinus 1e-3)
            surfaceUi.designM.shouldNotBeNull() shouldBe (row["cota_sup"].toDouble() + 0.3 plusOrMinus 1e-3)
            val level = ui.level.shouldNotBeNull()
            level.dzM shouldBe (row["d_cota"].toDouble() plusOrMinus 1e-3)
            level.state.name shouldBe row["nivel"]
            level.toleranceM shouldBe 0.03
        }
        // Los avisos, en su orden: entrar en cota, sin FIX, entrar de nuevo, entrar tras el hueco y salir.
        val expectedEvents = rows.take(closed).flatMap { listOf(it["aviso_nivel"], it["aviso"]) }.filter { it.isNotEmpty() }
        synchronized(events) { events.toList() } shouldBe expectedEvents
        synchronized(events) { events.toList() } shouldBe listOf("LEVEL_ARRIVED", "PERDIDO", "LEVEL_ARRIVED", "LEVEL_ARRIVED", "LEVEL_LEFT")
    }
}
