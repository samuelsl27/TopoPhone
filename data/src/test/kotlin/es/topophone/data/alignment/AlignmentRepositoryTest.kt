package es.topophone.data.alignment

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.alignment.AlignmentElement
import es.topophone.core.model.alignment.AlignmentExtent
import es.topophone.core.model.alignment.AlignmentSource
import es.topophone.core.model.alignment.VerticalCurve
import es.topophone.core.model.alignment.VerticalProfile
import es.topophone.core.model.alignment.VerticalVertex
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.log.LogLevel
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Guardar, activar, renombrar, ocultar y borrar los ejes (S049/RF-13 a RF-16, RF-19, RNF-T6). */
@RunWith(AndroidJUnit4::class)
class AlignmentRepositoryTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val logged = mutableListOf<String>()
    private val log = object : AppLog {
        override fun record(level: LogLevel, category: LogCategory, message: String) {
            logged += message
        }
    }
    private var counter = 0
    private val alignments = RoomAlignmentRepository(db, log) { "a${++counter}" }

    @After
    fun close() = db.close()

    private fun newProject(name: String) = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = name)).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private val project = newProject("Camino de la Dehesa")

    private fun item(name: String, withProfile: Boolean = true) = AlignmentToSave(
        name = name,
        fileName = "ejes.xml",
        source = AlignmentSource.LANDXML,
        elements = listOf(
            AlignmentElement.line(1000.5, 120.0, 440000.123456789, 4474000.000000001, 1.0471975511965976),
            AlignmentElement.arc(1120.5, 80.0, 440103.9230484541, 4474060.0, 1.0471975511965976, -0.0066666666666666),
        ),
        profile = if (withProfile) {
            VerticalProfile(
                listOf(
                    VerticalVertex(1000.5, 50.0),
                    VerticalVertex(1100.0, 52.0, VerticalCurve.Parabola(40.0)),
                    VerticalVertex(1200.5, 49.0),
                ),
            )
        } else {
            null
        },
        extent = AlignmentExtent(440000.0, 440200.0, 4474000.0, 4474100.0),
        approximated = false,
    )

    @Test
    fun `S049 RF-13 un eje se guarda y se lee igual sin redondear`() = runBlocking<Unit> {
        val added = alignments.add(project, listOf(item("Eje A")), importedAtMs = 5_000L)

        val read = alignments.alignment(added.ids.single()).shouldNotBeNull()

        read.name shouldBe "Eje A"
        read.fileName shouldBe "ejes.xml"
        read.importedAtMs shouldBe 5_000L
        read.source shouldBe AlignmentSource.LANDXML
        read.elements shouldBe item("Eje A").elements
        read.elements[0].startNorthing.toRawBits() shouldBe 4474000.000000001.toRawBits()
        read.profile shouldBe item("Eje A").profile
        read.startChainage shouldBe 1000.5
        read.lengthM shouldBe 200.0
        read.visible shouldBe true
        read.extent shouldBe AlignmentExtent(440000.0, 440200.0, 4474000.0, 4474100.0)
    }

    @Test
    fun `S049 RF-14 el primero que se importa queda activo y los demas no`() = runBlocking<Unit> {
        val first = alignments.add(project, listOf(item("Eje A"), item("Eje B")), 1L)
        val second = alignments.add(project, listOf(item("Eje C")), 2L)

        first.activatedId shouldBe first.ids.first()
        second.activatedId.shouldBeNull()
        alignments.activeAlignmentId(project).first() shouldBe first.ids.first()
        alignments.summaries(project).first().map { it.name } shouldBe listOf("Eje A", "Eje B", "Eje C")
    }

    @Test
    fun `S049 RF-14 se puede cambiar el eje activo y solo a uno del mismo proyecto`() = runBlocking<Unit> {
        val ids = alignments.add(project, listOf(item("Eje A"), item("Eje B")), 1L).ids
        val other = newProject("Otro proyecto")
        val foreign = alignments.add(other, listOf(item("Ajeno")), 1L).ids.single()

        alignments.activate(project, ids[1]) shouldBe true
        alignments.activeAlignmentId(project).first() shouldBe ids[1]
        alignments.activate(project, foreign) shouldBe false
        alignments.activeAlignmentId(project).first() shouldBe ids[1]
        alignments.activate(project, null) shouldBe true
        alignments.activeAlignmentId(project).first().shouldBeNull()
    }

    @Test
    fun `S049 RF-14 renombrar un eje cambia su nombre y uno en blanco se rechaza`() = runBlocking<Unit> {
        val id = alignments.add(project, listOf(item("Eje A")), 1L).ids.single()

        alignments.rename(id, "  Carretera  ") shouldBe true
        alignments.rename(id, "   ") shouldBe false
        alignments.rename("no-existe", "X") shouldBe false

        alignments.alignment(id)!!.name shouldBe "Carretera"
    }

    @Test
    fun `S049 RF-19 mostrar u ocultar un eje se guarda`() = runBlocking<Unit> {
        val id = alignments.add(project, listOf(item("Eje A")), 1L).ids.single()

        alignments.setVisible(id, false)

        alignments.summaries(project).first().single().visible shouldBe false
        alignments.setVisible(id, true)
        alignments.summaries(project).first().single().visible shouldBe true
    }

    @Test
    fun `S049 RF-15 borrar el eje activo lo quita entero y el proyecto queda sin activo`() = runBlocking<Unit> {
        val ids = alignments.add(project, listOf(item("Eje A"), item("Eje B")), 1L).ids

        alignments.delete(ids[0])

        alignments.alignment(ids[0]).shouldBeNull()
        alignments.summaries(project).first().map { it.id } shouldBe listOf(ids[1])
        alignments.activeAlignmentId(project).first().shouldBeNull()
    }

    @Test
    fun `S049 RF-15 borrar un eje no cambia los puntos del proyecto`() = runBlocking<Unit> {
        val id = alignments.add(project, listOf(item("Eje A")), 1L).ids.single()
        val before = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM points").use {
            it.moveToFirst()
            it.getInt(0)
        }

        alignments.delete(id)

        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM points").use {
            it.moveToFirst()
            it.getInt(0) shouldBe before
        }
    }

    @Test
    fun `S049 RF-9 la rasante se puede poner y quitar de un eje`() = runBlocking<Unit> {
        val id = alignments.add(project, listOf(item("Eje A", withProfile = false)), 1L).ids.single()
        alignments.summaries(project).first().single().hasProfile shouldBe false

        alignments.setProfile(id, VerticalProfile(listOf(VerticalVertex(1000.5, 1.0), VerticalVertex(1200.5, 2.0)))) shouldBe true
        alignments.summaries(project).first().single().hasProfile shouldBe true
        alignments.alignment(id)!!.profile!!.vertices.size shouldBe 2
        alignments.setProfile(id, null) shouldBe true
        alignments.alignment(id)!!.profile.shouldBeNull()
        alignments.setProfile("no-existe", null) shouldBe false
    }

    @Test
    fun `S049 RF-16 el log anota cada importacion y cada borrado sin nombres`() = runBlocking<Unit> {
        val ids = alignments.add(project, listOf(item("Secreto A"), item("Secreto B")), 1L).ids

        alignments.delete(ids.first())

        logged shouldBe listOf("Importados 2 ejes", "Eje borrado")
    }

    @Test
    fun `S049 RF-15 borrar el proyecto borra sus ejes`() = runBlocking<Unit> {
        alignments.add(project, listOf(item("Eje A")), 1L)

        db.openHelper.writableDatabase.execSQL("DELETE FROM projects WHERE id = ?", arrayOf<Any>(project))

        alignments.summaries(project).first() shouldBe emptyList()
    }
}
