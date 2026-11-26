package es.topophone.data.surface

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.surface.SurfaceExtent
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

/** Replantear una superficie que no es la activa la deja activa y visible (S062/RF-6). */
@RunWith(AndroidJUnit4::class)
class SurfaceStakeStoreTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val repository = RoomSurfaceRepository(db)
    private val project = runBlocking {
        RoomProjectRepository(db).create(ProjectDefaults().toDraft().copy(name = "Obra")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @After
    fun close() = db.close()

    private fun write(id: String) = runBlocking {
        repository.begin(SurfaceDraft(id, project, id, "$id.xml", 1L, CadSourceCrs.Project, LinearUnit.METRE))
        repository.finish(id, SurfaceSummary(SurfaceExtent(0.0, 1.0, 0.0, 1.0), 600.0, 601.0, 3, 1, 0))
    }

    @Test
    fun `S062 RF-6 una superficie oculta y no activa pasa a ser la activa y a verse`() = runBlocking<Unit> {
        write("s1")
        write("s2")
        repository.setVisible("s2", false)
        repository.activeSurfaceId(project).first() shouldBe "s1"

        val surface = SurfaceStakeStore.of(repository).prepare(project, "s2").shouldNotBeNull()

        surface.id shouldBe "s2"
        repository.activeSurfaceId(project).first() shouldBe "s2"
        repository.surfaces(project).first().single { it.id == "s2" }.visible shouldBe true
    }

    @Test
    fun `S062 RF-6 una superficie que no existe no se replantea`() = runBlocking<Unit> {
        SurfaceStakeStore.of(repository).prepare(project, "no-existe").shouldBeNull()
    }
}
