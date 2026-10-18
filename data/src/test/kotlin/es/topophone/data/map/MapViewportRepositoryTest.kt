package es.topophone.data.map

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.map.MapViewport
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.ProjectRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Último encuadre del mapa de cada proyecto (S009/RF-21). */
@RunWith(AndroidJUnit4::class)
class MapViewportRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TopoPhoneDatabase::class.java).build()
    private var now = 1_000L
    private val projects = RoomProjectRepository(db, clock = { now })
    private val viewports = RoomMapViewportRepository(db)

    @After
    fun close() = db.close()

    private fun newProject(name: String): String = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = name)).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private val madrid = MapViewport(latitudeDeg = 40.416_680_123, longitudeDeg = -3.703_710_456, zoom = 17.53)

    @Test
    fun `S009 RF-21 cada proyecto recuerda su ultimo encuadre sin redondear`() = runBlocking<Unit> {
        val calle = newProject("Calle Mayor 12")
        val muralla = newProject("Muralla")

        viewports.viewport(calle).shouldBeNull()
        viewports.save(calle, madrid)
        viewports.save(muralla, madrid.copy(zoom = 12.0))

        viewports.viewport(calle) shouldBe madrid
        viewports.viewport(muralla) shouldBe madrid.copy(zoom = 12.0)
    }

    @Test
    fun `S009 RF-21 guardar otra vez sustituye el encuadre anterior`() = runBlocking<Unit> {
        val calle = newProject("Calle Mayor 12")

        viewports.save(calle, madrid)
        viewports.save(calle, madrid.copy(latitudeDeg = 40.5))

        viewports.viewport(calle) shouldBe madrid.copy(latitudeDeg = 40.5)
    }

    @Test
    fun `S009 RF-21 el encuadre se borra con el proyecto al borrarlo para siempre o vaciar la papelera`() = runBlocking<Unit> {
        val forever = newProject("Para siempre")
        val trashed = newProject("Papelera")
        viewports.save(forever, madrid)
        viewports.save(trashed, madrid)

        projects.deleteForever(forever)
        projects.moveToTrash(trashed)
        now += (ProjectRepository.TRASH_DAYS + 1) * 24L * 60 * 60 * 1000
        projects.purgeTrash()

        viewports.viewport(forever).shouldBeNull()
        viewports.viewport(trashed).shouldBeNull()
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM map_viewports").use {
            it.moveToFirst()
            it.getInt(0) shouldBe 0
        }
    }
}
