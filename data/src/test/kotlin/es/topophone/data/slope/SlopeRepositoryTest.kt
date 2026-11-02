package es.topophone.data.slope

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.alignment.AlignmentElement
import es.topophone.core.model.alignment.AlignmentExtent
import es.topophone.core.model.alignment.AlignmentSource
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.slope.ManualEdge
import es.topophone.core.model.alignment.slope.SlopeIssue
import es.topophone.core.model.alignment.slope.SlopeRule
import es.topophone.core.model.alignment.slope.SlopeSide
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.log.LogLevel
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.alignment.AlignmentToSave
import es.topophone.data.alignment.RoomAlignmentRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Guardar y borrar los taludes de un eje (S051/RF-3, RF-4, RNF-T6). */
@RunWith(AndroidJUnit4::class)
class SlopeRepositoryTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val logged = mutableListOf<String>()
    private val log = object : AppLog {
        override fun record(level: LogLevel, category: LogCategory, message: String) {
            logged += message
        }
    }
    private var counter = 0
    private val alignments = RoomAlignmentRepository(db, log) { "a${++counter}" }
    private val slopes = RoomSlopeRepository(db, log, clock = { 9_000L }) { "r${++counter}" }

    @After
    fun close() = db.close()

    private val project = runBlocking {
        RoomProjectRepository(db).create(ProjectDefaults().toDraft().copy(name = "Camino")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private val axis = runBlocking {
        alignments.add(
            project,
            listOf(
                AlignmentToSave(
                    "Eje A",
                    null,
                    AlignmentSource.MANUAL,
                    listOf(AlignmentElement.line(0.0, 420.0, 440000.0, 4474000.0, 1.0)),
                    null,
                    AlignmentExtent(440000.0, 440400.0, 4474000.0, 4474300.0),
                    false,
                ),
            ),
            1L,
        ).ids.single()
    }

    private fun rule(side: SlopeSide = SlopeSide.RIGHT, from: Double = 0.0, to: Double = 100.0, manual: ManualEdge? = null) =
        SlopeRule(side, from, to, 1.5000000000000002, 2.0, if (manual == null) "CD" else null, manual)

    @Test
    fun `S051 RF-4 un talud se guarda y se lee igual sin redondear con su fecha`() = runBlocking<Unit> {
        val saved = slopes.add(axis, rule()).shouldBeInstanceOf<SlopeSaveResult.Saved>()

        val read = slopes.rules(axis).first().single()

        read.rule shouldBe rule()
        read.rule.cutRatio.toRawBits() shouldBe 1.5000000000000002.toRawBits()
        read.createdAtMs shouldBe 9_000L
        read.id shouldBe saved.id
    }

    @Test
    fun `S051 RF-2 el borde manual se guarda y vuelve con su tipo de cota`() = runBlocking<Unit> {
        val manual = ManualEdge(-3.5, 99.5, HeightKind.ABSOLUTE)

        slopes.add(axis, rule(side = SlopeSide.LEFT, manual = manual).copy(edgeCode = null, manualEdge = manual.copy(offsetM = -3.5)))

        slopes.rules(axis).first().single().rule.manualEdge shouldBe manual
    }

    @Test
    fun `S051 RF-3 un talud que no vale no se guarda y dice el motivo`() = runBlocking<Unit> {
        slopes.add(axis, rule(from = 0.0, to = 100.0))

        slopes.add(axis, rule(from = 50.0, to = 150.0)) shouldBe
            SlopeSaveResult.Rejected(SlopeIssue.OverlappingRanges(SlopeSide.RIGHT, 50.0, 150.0))
        slopes.add(axis, rule(side = SlopeSide.LEFT, from = 50.0, to = 150.0)).shouldBeInstanceOf<SlopeSaveResult.Saved>()
        slopes.add(axis, rule(from = 100.0, to = 999.0)) shouldBe SlopeSaveResult.Rejected(SlopeIssue.ChainageOutsideAxis(999.0))
        slopes.rules(axis).first().size shouldBe 2
    }

    @Test
    fun `S051 RF-4 borrar un talud quita solo ese`() = runBlocking<Unit> {
        val first = slopes.add(axis, rule(from = 0.0, to = 100.0)).shouldBeInstanceOf<SlopeSaveResult.Saved>().id
        slopes.add(axis, rule(from = 100.0, to = 200.0))

        slopes.delete(first)

        slopes.rules(axis).first().map { it.rule.fromChainage } shouldBe listOf(100.0)
    }

    @Test
    fun `S051 RF-4 borrar el eje borra sus taludes`() = runBlocking<Unit> {
        slopes.add(axis, rule())

        alignments.delete(axis)

        slopes.rules(axis).first() shouldBe emptyList()
    }

    @Test
    fun `S051 RF-4 el log anota altas y bajas sin coordenadas ni nombres`() = runBlocking<Unit> {
        val id = slopes.add(axis, rule()).shouldBeInstanceOf<SlopeSaveResult.Saved>().id
        slopes.delete(id)

        logged.any { it.contains("talud") } shouldBe true
        logged.any { it.contains("Eje A") || it.contains("440000") } shouldBe false
    }

    @Test
    fun `S051 RF-4 un eje que no existe se indica y no se guarda nada`() = runBlocking<Unit> {
        slopes.add("no-existe", rule()) shouldBe SlopeSaveResult.AxisNotFound
    }
}
