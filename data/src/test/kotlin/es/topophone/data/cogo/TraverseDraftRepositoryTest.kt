package es.topophone.data.cogo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.cogo.DraftPoint
import es.topophone.core.model.cogo.LegKind
import es.topophone.core.model.cogo.TraverseDraft
import es.topophone.core.model.cogo.TraverseLeg
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** El borrador de la poligonal: uno por proyecto, se sustituye al guardar y se borra con el proyecto (S038/RF-26, RF-29). */
@RunWith(AndroidJUnit4::class)
class TraverseDraftRepositoryTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private var now = 1_000L
    private val repository = RoomCogoRepository(db, clock = { now })

    @After
    fun close() = db.close()

    private fun newProject(name: String) = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = name)).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private val first = newProject("Calle Mayor 12")

    private fun draft(projectId: String = first, legs: List<TraverseLeg> = listOf(TraverseLeg(LegKind.AZIMUTH, 1.5, 20.0, null))) =
        TraverseDraft(projectId, DraftPoint.OfProject("p1", "P1"), DraftPoint.Typed(440_000.123456789012, 4_474_000.5), null, legs, 0L)

    private fun read(projectId: String = first) = runBlocking { repository.draft(projectId).first() }

    @Test
    fun `S038 RF-26 un proyecto sin borrador da null`() {
        read().shouldBeNull()
    }

    @Test
    fun `S038 RF-26 el borrador guardado vuelve igual, con la hora del guardado`() {
        runBlocking { repository.saveDraft(draft()) }

        read() shouldBe draft().copy(updatedAtMs = 1_000L)
    }

    @Test
    fun `S038 RF-26 guardar de nuevo sustituye el borrador y hay uno solo por proyecto`() {
        runBlocking { repository.saveDraft(draft()) }
        now = 2_000L
        val longer = draft(legs = listOf(TraverseLeg(LegKind.AZIMUTH, 1.5, 20.0, null), TraverseLeg(LegKind.ANGLE_RIGHT, null, 5.0, 0.1)))

        runBlocking { repository.saveDraft(longer) }

        read() shouldBe longer.copy(updatedAtMs = 2_000L)
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM traverse_drafts").use {
            it.moveToFirst()
            it.getInt(0) shouldBe 1
        }
    }

    @Test
    fun `S038 RF-26 cada proyecto tiene el suyo`() {
        val other = newProject("Calle Menor 3")
        runBlocking {
            repository.saveDraft(draft(first))
            repository.saveDraft(draft(other, legs = emptyList()))
        }

        read(first)!!.legs.size shouldBe 1
        read(other)!!.legs.size shouldBe 0
    }

    @Test
    fun `S038 RF-26 guardar un borrador vacio borra el del proyecto`() {
        runBlocking { repository.saveDraft(draft()) }

        runBlocking { repository.saveDraft(TraverseDraft(first, null, null, null, emptyList(), 0L)) }

        read().shouldBeNull()
    }

    @Test
    fun `S038 RF-27 descartar el borrador lo borra`() {
        runBlocking { repository.saveDraft(draft()) }

        runBlocking { repository.clearDraft(first) }

        read().shouldBeNull()
    }

    @Test
    fun `S038 RF-29 el borrador se elimina con el proyecto`() {
        runBlocking { repository.saveDraft(draft()) }

        db.openHelper.writableDatabase.execSQL("DELETE FROM projects WHERE id = ?", arrayOf<Any>(first))

        read().shouldBeNull()
    }

    @Test
    fun `S038 RF-26 un borrador ilegible se lee como ausente`() {
        runBlocking { repository.saveDraft(draft()) }
        db.openHelper.writableDatabase.execSQL("UPDATE traverse_drafts SET data = 'basura'")

        read().shouldBeNull()
    }
}
