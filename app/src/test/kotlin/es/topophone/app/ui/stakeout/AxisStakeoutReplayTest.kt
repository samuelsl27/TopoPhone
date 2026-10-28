package es.topophone.app.ui.stakeout

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.alignment.AlignmentTestFiles
import es.topophone.app.ui.receiver.repositoryRoot
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.point.AdmittedSolutions
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.StakeMode
import es.topophone.core.model.point.StakeoutSettings
import es.topophone.core.model.point.TypedCoordinates
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.AcrossMove
import es.topophone.core.survey.AlignmentGeometry
import es.topophone.core.survey.AxisGuidance
import es.topophone.core.survey.AxisStakeResult
import es.topophone.core.survey.AxisStakeTarget
import es.topophone.core.survey.ManualCoordinates
import es.topophone.core.survey.ManualResult
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.core.survey.VerticalProfileEval
import es.topophone.data.alignment.AlignmentImportResult
import es.topophone.data.alignment.AlignmentImporter
import es.topophone.data.alignment.RoomAlignmentRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.receiver.platform.Alerts
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.transport.StreamTransport
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
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
import org.junit.runner.RunWith

/**
 * La reproducción sintética `reproduccion-eje-a` (`tools/ejes/generar_ejes.py`, con lo esperado calculado aparte con las series de
 * Fresnel y pyproj) por la `ReceiverSession` y la guía reales: 43 épocas recorriendo el eje A de 10 en 10 m ondulando alrededor de
 * 3,5 m a la derecha, con el replanteo del PK 150 a 3,5 m a la derecha y 10 cm de espesor. Época a época: lo que hay que moverse
 * adelante o atrás en PK y hacia dentro o fuera del eje, la distancia y el desmonte o terraplén (S054/RF-6, RF-7; constitución nº 2).
 */
@RunWith(AndroidJUnit4::class)
class AxisStakeoutReplayTest {

    private val directory = File(repositoryRoot, "fixtures/ejes/replanteo")
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(
        sessionScope,
        NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE },
        noDataTimeoutMs = 60_000, // las épocas se mandan a mano: bajo carga, 2 s sin ninguna dejarían la sesión en "Sin datos"
        watchdogPeriodMs = 1_000,
    )
    private val toApp = PipedOutputStream()
    private val alerts = object : Alerts {
        override fun connectionLost() = Unit

        override fun correctionsLost() = Unit

        override fun pointSaved() = Unit

        override fun solutionLost() = Unit

        override fun tiltLost() = Unit
    }
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    @After
    fun tearDown() {
        runCatching { toApp.close() }
        session.disconnect()
        sessionScope.cancel()
        db.close()
    }

    /** Las frases del fichero agrupadas por época: cada grupo empieza en su GGA. */
    private fun epochs(): List<String> {
        val groups = mutableListOf<StringBuilder>()
        File(directory, "reproduccion-eje-a.nmea").readText(Charsets.US_ASCII).split("\r\n").filter { it.isNotEmpty() }.forEach { line ->
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

    private fun expected(): List<Row> {
        val lines = File(directory, "reproduccion-eje-a.csv").readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        val header = lines.first().split(',')
        return lines.drop(1).map { line -> Row(header.zip(line.split(',')).toMap()) }
    }

    @Test
    fun `S054 RF-6 y RF-7 la guia sigue la reproduccion del eje A epoca a epoca`() {
        val groups = epochs()
        val rows = expected()
        groups.size shouldBe rows.size

        val projectId = runBlocking {
            (RoomProjectRepository(db).create(ProjectDefaults().toDraft().copy(name = "Obra")) as SaveResult.Saved).id
        }
        val alignments = RoomAlignmentRepository(db)
        val axisId = runBlocking {
            val result = AlignmentImporter(alignments).importLandXml(
                projectId,
                ProjectCrs.ETRS89_UTM30,
                "ejes.xml",
                setOf(0),
                CadSourceCrs.Project,
                LinearUnit.METRE,
                { ByteArrayInputStream(AlignmentTestFiles.bytes("ejes.xml")) },
            )
            result.shouldBeInstanceOf<AlignmentImportResult.Done>().summary.imported.single { it.name == "Eje A" }.id
        }
        val alignment = runBlocking { alignments.alignment(axisId) }.shouldNotBeNull()
        val target = AxisStakeTarget.of(
            AlignmentGeometry(alignment),
            alignment.profile?.let { VerticalProfileEval(it) },
            150.0,
            3.5,
            0.1,
        ).shouldBeInstanceOf<AxisStakeResult.Target>()
        val project = Project(
            id = "p1",
            name = "Replanteo por PK",
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
        val calculator = ProjectPositionCalculator()
        val typed = TypedCoordinates(EntryForm.PROJECT_CRS, target.point.easting, target.point.northing, null, HeightKind.PROJECT)
        val coordinates = (
            ManualCoordinates(
                calculator,
            ).compute(typed, project.crs, project.heightSystem) as ManualResult.Computed
            ).coordinates
        val design = StakeTarget("axis:Eje A:150000", "Eje A", "", coordinates)
        val controller = StakeoutController(alerts, publishIntervalMs = 0)
        val settings = StakeoutSettings(StakeMode.BY_DISTANCE)
        val states = mutableListOf<GuidanceState>()
        val collecting = CoroutineScope(Dispatchers.Unconfined).launch {
            session.epochs.collect { received ->
                val context = StakeContext(
                    settings,
                    design,
                    AdmittedSolutions.FIX_ONLY,
                    null,
                    1.8,
                    project,
                    calculator,
                    designElevationM = target.elevationM,
                )
                controller.onEpoch(received.epoch, context)
                synchronized(states) { states += controller.state.value }
            }
        }
        session.connect(StreamTransport("RS3 sintético", PipedInputStream(toApp, 256 * 1024), ByteArrayOutputStream()))
        send(groups)
        // La última época queda abierta hasta la siguiente: se comparan todas las demás.
        val closed = groups.size - 1
        runBlocking { withTimeout(20_000) { while (synchronized(states) { states.size } < closed) delay(10) } }
        collecting.cancel()

        synchronized(states) { states.toList() }.take(closed).forEachIndexed { i, state ->
            val row = rows[i]
            val ui = state.shouldBeInstanceOf<GuidanceState.On>().ui
            val guidance = AxisGuidance.of(target, target.point.easting + ui.tipEastM, target.point.northing + ui.tipNorthM)

            guidance.alongM shouldBe (row["adelante"].toDouble() plusOrMinus 1e-3)
            guidance.acrossM shouldBe (row["derecha"].toDouble() plusOrMinus 1e-3)
            guidance.across shouldBe when (row["sentido"]) {
                "dentro" -> AcrossMove.INWARD
                "fuera" -> AcrossMove.OUTWARD
                else -> AcrossMove.NONE
            }
            ui.guidance.distanceM shouldBe (row["distancia"].toDouble() plusOrMinus 1e-3)
            // punta menos cota de diseño (la rasante en el PK 150 más 10 cm de espesor): positiva en desmonte
            ui.level.shouldNotBeNull().dzM shouldBe (row["d_cota"].toDouble() plusOrMinus 1e-3)
        }
        (closed >= 40) shouldBe true
    }
}
