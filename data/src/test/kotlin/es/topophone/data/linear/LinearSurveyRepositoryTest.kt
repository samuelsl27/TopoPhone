package es.topophone.data.linear

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.alignment.AlignmentElement
import es.topophone.core.model.alignment.AlignmentExtent
import es.topophone.core.model.alignment.AlignmentSource
import es.topophone.core.model.alignment.section.CrossSection
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.alignment.section.SectionPoint
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.log.LogLevel
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.survey.linear.LinearSurvey
import es.topophone.core.model.survey.linear.LinearSurveyKind
import es.topophone.core.model.survey.linear.SurveyFlag
import es.topophone.core.model.survey.linear.SurveyPoint
import es.topophone.data.alignment.AlignmentToSave
import es.topophone.data.alignment.RoomAlignmentRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.PointSaveResult
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.data.section.RoomSectionRepository
import es.topophone.data.section.SectionSaveResult
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Las tomas lineales: altas atómicas, exclusión, terminar, historial, reanudar y borrar sin tocar puntos (S052/RF-2, RF-4, RF-8, RF-9, RF-20, RF-22, RF-23). */
@RunWith(AndroidJUnit4::class)
class LinearSurveyRepositoryTest {

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
    private val sections = RoomSectionRepository(db, log, clock = { 9_000L }) { "s${++counter}" }
    private val points = RoomPointRepository(db)
    private var now = 10_000L
    private val surveys = RoomLinearSurveyRepository(db, alignments, sections, log, clock = { now })

    @After
    fun close() = db.close()

    private val project = runBlocking {
        RoomProjectRepository(db).create(ProjectDefaults().toDraft().copy(name = "Camino")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private fun axisToSave(name: String = "Eje tomado", source: AlignmentSource = AlignmentSource.FIELD) = AlignmentToSave(
        name,
        null,
        source,
        listOf(AlignmentElement.line(0.0, 420.0, 440000.0, 4474000.0, 1.0)),
        null,
        AlignmentExtent(440000.0, 440400.0, 4474000.0, 4474300.0),
        false,
    )

    private fun existingAxis(): String = runBlocking {
        alignments.add(project, listOf(axisToSave("Eje A", AlignmentSource.MANUAL)), 1L).ids.single()
    }

    private fun point(id: String) = Point.measured(
        id = id,
        projectId = project,
        name = id,
        description = "",
        measurement = Measurement(
            antenna = GeodeticPosition(40.416_801_234_567_89, -3.703_712_345_678_9, 701.357_123_456),
            poleHeightM = 1.8,
            quality = PointQuality("FIX", 24, 0.6, 1.0, 0.009, 0.014, MeasurementMode.INSTANT, 1, 0, fromReplay = false),
            rawNmea = "",
            outOfTolerance = emptySet(),
            measuredFromMs = 1,
            measuredToMs = 2,
            coordinates = PointCoordinates(GeodeticPosition(40.4168, -3.7037, 699.4), 440_290.4, 4_474_257.4, 648.3),
        ),
        createdAtMs = 3,
    )

    private fun saved(id: String) = runBlocking { points.create(point(id)).shouldBe(PointSaveResult.Saved) }

    private fun survey(
        id: String = "t1",
        kind: LinearSurveyKind = LinearSurveyKind.LONGITUDINAL,
        alignmentId: String? = null,
        chainage: Double? = null,
    ) = LinearSurvey(id, project, kind, alignmentId, "Toma", chainage = chainage, createdAtMs = 5)

    private fun member(id: String, ordinal: Int = 0) =
        SurveyPoint(id, ordinal, true, 12.345678901234567, -0.1234567890123, 0.00000000123, SurveyFlag.NONE)

    @Test
    fun `S052 RF-2 un punto de la toma se lee igual sin redondear y el orden lo da la toma`() = runBlocking<Unit> {
        saved("p1")
        saved("p2")
        surveys.open(survey())

        surveys.addPoint("t1", member("p1", ordinal = 99)) shouldBe SurveyAddResult.ADDED
        surveys.addPoint("t1", member("p2", ordinal = 99)) shouldBe SurveyAddResult.ADDED

        val read = surveys.points("t1").first()
        read.map { it.pointId to it.ordinal } shouldBe listOf("p1" to 0, "p2" to 1)
        read[0].chainageM!!.toRawBits() shouldBe 12.345678901234567.toRawBits()
        read[0].offsetM!!.toRawBits() shouldBe (-0.1234567890123).toRawBits()
        read[0].dHeightM!!.toRawBits() shouldBe 0.00000000123.toRawBits()
    }

    @Test
    fun `S052 RF-2 un punto no entra dos veces ni en una toma cerrada ni en una que no existe`() = runBlocking<Unit> {
        saved("p1")
        saved("p2")
        surveys.open(survey())
        surveys.addPoint("t1", member("p1")) shouldBe SurveyAddResult.ADDED

        surveys.addPoint("t1", member("p1")) shouldBe SurveyAddResult.ALREADY_IN
        surveys.addPoint("nada", member("p2")) shouldBe SurveyAddResult.NOT_FOUND
        surveys.finishLongitudinal("t1") shouldBe true
        surveys.addPoint("t1", member("p2")) shouldBe SurveyAddResult.CLOSED
        surveys.points("t1").first().size shouldBe 1
    }

    @Test
    fun `S052 RF-4 quitar un punto de la toma no lo borra del proyecto y se puede volver a incluir`() = runBlocking<Unit> {
        saved("p1")
        surveys.open(survey())
        surveys.addPoint("t1", member("p1"))

        surveys.setIncluded("t1", "p1", false) shouldBe true

        surveys.points("t1").first().single().included shouldBe false
        points.points(project).first().map { it.id } shouldBe listOf("p1")
        surveys.setIncluded("t1", "p1", true) shouldBe true
        surveys.points("t1").first().single().included shouldBe true
        surveys.setIncluded("t1", "otro", false) shouldBe false
    }

    @Test
    fun `S052 RF-9 una toma abierta con sus puntos sigue ahi para otro repositorio sobre la misma base`() = runBlocking<Unit> {
        saved("p1")
        surveys.open(survey())
        surveys.addPoint("t1", member("p1"))

        val reopened = RoomLinearSurveyRepository(db, alignments, sections, log)

        reopened.surveys(project).first().single().isOpen shouldBe true
        reopened.points("t1").first().single().pointId shouldBe "p1"
    }

    @Test
    fun `S052 RF-5 y RF-8 terminar una toma de eje crea el eje tomado y lo deja activo si no habia ninguno`() = runBlocking<Unit> {
        surveys.open(survey("e1", LinearSurveyKind.AXIS))

        val done = surveys.finishAxis("e1", axisToSave()).shouldBeInstanceOf<FinishAxisResult.Finished>()

        val axis = alignments.alignment(done.alignmentId).shouldNotBeNull()
        axis.source shouldBe AlignmentSource.FIELD
        done.activatedId shouldBe done.alignmentId
        surveys.survey("e1").shouldNotBeNull().let {
            it.alignmentId shouldBe done.alignmentId
            it.finishedAtMs shouldBe 10_000L
        }
        surveys.finishAxis("e1", axisToSave()) shouldBe FinishAxisResult.NotOpen
    }

    @Test
    fun `S052 RF-8 si ya hay un eje activo el eje tomado no lo desplaza`() = runBlocking<Unit> {
        val active = existingAxis()
        surveys.open(survey("e1", LinearSurveyKind.AXIS))

        val done = surveys.finishAxis("e1", axisToSave()).shouldBeInstanceOf<FinishAxisResult.Finished>()

        done.activatedId.shouldBeNull()
        alignments.activeAlignmentId(project).first() shouldBe active
    }

    @Test
    fun `S052 RF-8 borrar el eje o la toma no borra los puntos`() = runBlocking<Unit> {
        saved("p1")
        surveys.open(survey("e1", LinearSurveyKind.AXIS))
        surveys.addPoint("e1", member("p1"))
        val axisId = (surveys.finishAxis("e1", axisToSave()) as FinishAxisResult.Finished).alignmentId

        alignments.delete(axisId)

        surveys.survey("e1").shouldNotBeNull().alignmentId.shouldBeNull()
        points.points(project).first().size shouldBe 1
        surveys.delete("e1")
        surveys.survey("e1").shouldBeNull()
        points.points(project).first().size shouldBe 1
    }

    @Test
    fun `S052 RF-2 un punto que esta en la papelera no cuenta en la toma hasta que vuelve`() = runBlocking<Unit> {
        saved("p1")
        saved("p2")
        surveys.open(survey())
        surveys.addPoint("t1", member("p1"))
        surveys.addPoint("t1", member("p2"))

        points.moveToTrash(listOf("p1"))

        surveys.points("t1").first().map { it.pointId } shouldBe listOf("p2")
    }

    private fun ground(chainage: Double, height: Double) = CrossSection(
        SectionKind.GROUND,
        chainage,
        HeightKind.ABSOLUTE,
        listOf(SectionPoint("P1", -3.0, height), SectionPoint("P2", 0.0, height + 0.5), SectionPoint("P3", 4.0, height)),
    )

    @Test
    fun `S052 RF-20 guardar un perfil transversal lo deja como el vigente de S050 y cierra la toma`() = runBlocking<Unit> {
        val axis = existingAxis()
        surveys.open(survey("c1", LinearSurveyKind.CROSS, axis, chainage = 105.0))

        surveys.finishCross("c1", ground(105.0, 100.0)) shouldBe FinishCrossResult.Finished

        sections.sections(axis).first().map { it.section } shouldBe listOf(ground(105.0, 100.0))
        surveys.survey("c1").shouldNotBeNull().isOpen shouldBe false
        surveys.finishCross("c1", ground(105.0, 100.0)) shouldBe FinishCrossResult.NotOpen
    }

    @Test
    fun `S052 RF-20 un segundo perfil en el mismo PK es el vigente y el primero queda en el historial`() = runBlocking<Unit> {
        val axis = existingAxis()
        surveys.open(survey("c1", LinearSurveyKind.CROSS, axis, chainage = 105.0))
        surveys.finishCross("c1", ground(105.0, 100.0))
        now = 20_000L
        surveys.open(survey("c2", LinearSurveyKind.CROSS, axis, chainage = 105.0).copy(createdAtMs = 6))

        surveys.finishCross("c2", ground(105.0, 101.0)) shouldBe FinishCrossResult.Finished

        sections.sections(axis).first().map { it.section } shouldBe listOf(ground(105.0, 101.0))
        surveys.surveys(project).first().filter { it.kind == LinearSurveyKind.CROSS }.map { it.id to it.finishedAtMs } shouldBe
            listOf("c1" to 10_000L, "c2" to 20_000L)
    }

    @Test
    fun `S052 RF-20 si S050 rechaza el perfil no se guarda nada y la toma sigue abierta`() = runBlocking<Unit> {
        val axis = existingAxis()
        surveys.open(survey("c1", LinearSurveyKind.CROSS, axis, chainage = 9_999.0))

        val result = surveys.finishCross("c1", ground(9_999.0, 100.0)).shouldBeInstanceOf<FinishCrossResult.Rejected>()

        result.result.shouldBeInstanceOf<SectionSaveResult.Rejected>()
        sections.sections(axis).first() shouldBe emptyList()
        surveys.survey("c1").shouldNotBeNull().isOpen shouldBe true
    }

    @Test
    fun `S052 RF-23 el log lleva el hecho sin nombres ni coordenadas`() = runBlocking<Unit> {
        saved("p1")
        surveys.open(survey())
        surveys.addPoint("t1", member("p1"))
        surveys.setIncluded("t1", "p1", false)
        surveys.finishLongitudinal("t1")
        surveys.delete("t1")

        logged.filter {
            it.contains("toma") || it.contains("perfil") || it.contains("punto en una toma") || it.contains("de una toma")
        } shouldBe
            listOf("Abierta 1 toma", "Quitado 1 punto de una toma", "Terminado 1 perfil longitudinal", "Borrada 1 toma")
        logged.none { it.contains("p1") || it.contains("12.34") || it.contains("Toma") } shouldBe true
    }

    @Test
    fun `S052 RF-22 las tomas del proyecto salen por fecha con su tipo y sus numeros intactos`() = runBlocking<Unit> {
        surveys.open(
            survey("t2").copy(createdAtMs = 8, chainageFrom = 0.1, chainageTo = 99.9, intervalM = 5.0, guided = true, toleranceM = 0.3),
        )
        surveys.open(survey("t1").copy(createdAtMs = 7))

        val read = surveys.surveys(project).first()

        read.map { it.id } shouldBe listOf("t1", "t2")
        read[1].let {
            it.guided shouldBe true
            it.chainageFrom shouldBe 0.1
            it.chainageTo shouldBe 99.9
            it.intervalM shouldBe 5.0
            it.toleranceM shouldBe 0.3
        }
    }
}
