package es.topophone.app.ui.slope

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.alignment.AlignmentTestFiles
import es.topophone.app.ui.receiver.repositoryRoot
import es.topophone.app.ui.stakeout.GuidanceState
import es.topophone.app.ui.stakeout.StakeContext
import es.topophone.app.ui.stakeout.StakeTarget
import es.topophone.app.ui.stakeout.StakeoutController
import es.topophone.core.model.alignment.slope.SlopeKind
import es.topophone.core.model.alignment.slope.SlopeRule
import es.topophone.core.model.alignment.slope.SlopeSide
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
import es.topophone.core.survey.AlignmentGeometry
import es.topophone.core.survey.AxisPoint
import es.topophone.core.survey.ManualCoordinates
import es.topophone.core.survey.ManualResult
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.core.survey.SlopeEdgeResult
import es.topophone.core.survey.SlopeLive
import es.topophone.core.survey.SlopeLiveValue
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
 * La reproducción sintética `reproduccion-talud` (`tools/ejes/generar_taludes.py`, con lo esperado calculado aparte en el plano): 30 épocas
 * que suben por la sección del PK 105 de la derecha del eje C, un desmonte 1,5 : 1 con el borde a 3,5 m y cota 100, por la `ReceiverSession`
 * y la guía reales. Época a época y a 1 mm: la diferencia de cota con el talud, lo que hay que moverse, el error en PK y cuándo se da por
 * encontrado el punto (S051/RF-10 a RF-13; constitución nº 2).
 */
@RunWith(AndroidJUnit4::class)
class SlopeStakeReplayTest {

    private val directory = File(repositoryRoot, "fixtures/ejes/taludes")
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(
        sessionScope,
        NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE },
        noDataTimeoutMs = 60_000, // las épocas se mandan a mano
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
        File(directory, "reproduccion-talud.nmea").readText(Charsets.US_ASCII).split("\r\n").filter { it.isNotEmpty() }.forEach { line ->
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

    private fun expected(): List<Map<String, String>> {
        val lines = File(directory, "reproduccion-talud.csv").readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        val header = lines.first().split(',')
        return lines.drop(1).map { line -> header.zip(line.split(',')).toMap() }
    }

    @Test
    fun `S051 RF-10 a RF-13 la guia sigue la reproduccion que sube por la seccion epoca a epoca`() {
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
                setOf(2),
                CadSourceCrs.Project,
                LinearUnit.METRE,
                { ByteArrayInputStream(AlignmentTestFiles.bytes("ejes.xml")) },
            )
            result.shouldBeInstanceOf<AlignmentImportResult.Done>().summary.imported.single { it.name == "Eje C" }.id
        }
        val geometry = AlignmentGeometry(runBlocking { alignments.alignment(axisId) }.shouldNotBeNull())
        val project = Project(
            id = "p1",
            name = "Replanteo de taludes",
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

        // El talud de diseño del PK 105 de la derecha y el punto de talud que guía la reproducción (terreno a 1 m sobre el borde).
        val rule = SlopeRule(SlopeSide.RIGHT, 0.0, 200.0, 1.5, 2.0, "BD")
        val edgePoint = AxisPoint(105.0, 3.5, 441_105.0, 4_473_996.5, 441_105.0, 4_474_000.0, 0.0, 0.0)
        val edge = SlopeEdgeResult.Edge(rule, 105.0, 3.5, 100.0, edgePoint)
        val catchEasting = 441_105.0
        val catchNorthing = 4_473_995.0
        val catchHeight = 101.0

        val calculator = ProjectPositionCalculator()
        val typed = TypedCoordinates(EntryForm.PROJECT_CRS, catchEasting, catchNorthing, null, HeightKind.PROJECT)
        val coordinates = (
            ManualCoordinates(
                calculator,
            ).compute(typed, project.crs, project.heightSystem) as ManualResult.Computed
            ).coordinates
        val design = StakeTarget("slope:Eje C:105000:RIGHT", "Eje C", "", coordinates)
        val controller = StakeoutController(alerts, publishIntervalMs = 0)
        val settings = StakeoutSettings(StakeMode.BY_DISTANCE)
        val values = mutableListOf<SlopeLiveValue?>()
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
                    designElevationM = catchHeight,
                )
                controller.onEpoch(received.epoch, context)
                val ui = (controller.state.value as GuidanceState.On).ui
                // Lo mismo que hace el replanteo de taludes: la punta es el objetivo más lo que dice la guía.
                val live = SlopeLive.of(
                    edge,
                    SlopeKind.CUT,
                    catchEasting + ui.tipEastM,
                    catchNorthing + ui.tipNorthM,
                    catchHeight + checkNotNull(ui.guidance.dHeightM),
                    geometry,
                    settings.validToleranceM,
                    settings.validToleranceVM,
                )
                synchronized(values) { values += live }
            }
        }
        session.connect(StreamTransport("RS3 sintético", PipedInputStream(toApp, 256 * 1024), ByteArrayOutputStream()))
        send(groups)
        // La última época queda abierta hasta la siguiente: se comparan todas las demás.
        val closed = groups.size - 1
        runBlocking { withTimeout(20_000) { while (synchronized(values) { values.size } < closed) delay(10) } }
        collecting.cancel()

        synchronized(values) { values.toList() }.take(closed).forEachIndexed { i, value ->
            val row = rows[i]
            val live = value.shouldNotBeNull()

            live.dHeightM shouldBe (row.getValue("d_cota").toDouble() plusOrMinus 1e-3)
            live.moveM shouldBe (row.getValue("mover").toDouble() plusOrMinus 1e-3)
            live.dChainageM shouldBe (row.getValue("d_pk").toDouble() plusOrMinus 1e-3)
            live.onLine shouldBe row.getValue("en_linea").toBoolean()
            live.found shouldBe row.getValue("encontrado").toBoolean()
            live.kind shouldBe SlopeKind.CUT
        }
        (closed >= 25) shouldBe true
    }
}
