package es.topophone.data.project

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.project.TerrainPlane
import es.topophone.data.db.TopoPhoneDatabase
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** El plano de terreno se guarda con el proyecto, sin redondear, y sobrevive a editarlo, duplicarlo y restaurarlo (S058/RF-1, RF-6). */
@RunWith(AndroidJUnit4::class)
class ProjectTerrainRepositoryTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val plane = TerrainPlane(40.416801234567891, -3.7037123456789, 0.99954151490660681)

    @After
    fun tearDown() = db.close()

    private fun create(name: String, terrain: TerrainPlane?): String = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = name, terrain = terrain)).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @Test
    fun `S058 RF-6 el plano se guarda sin redondear y se lee igual`() {
        val id = create("Obra", plane)

        val read = runBlocking { projects.project(id) }.shouldNotBeNull()

        read.terrain shouldBe plane
        db.openHelper.readableDatabase.query("SELECT terrain_origin_lat, terrain_origin_lon, terrain_factor FROM projects").use {
            it.moveToFirst() shouldBe true
            it.getDouble(0) shouldBe 40.416801234567891
            it.getDouble(1) shouldBe -3.7037123456789
            it.getDouble(2) shouldBe 0.99954151490660681
        }
    }

    @Test
    fun `S058 RF-1 un proyecto en cuadricula no tiene plano`() {
        val id = create("Obra", null)

        runBlocking { projects.project(id) }.shouldNotBeNull().terrain.shouldBeNull()
        db.openHelper.readableDatabase.query("SELECT terrain_origin_lat, terrain_factor FROM projects").use {
            it.moveToFirst() shouldBe true
            it.isNull(0) shouldBe true
            it.isNull(1) shouldBe true
        }
    }

    @Test
    fun `S058 RF-1 editar el proyecto pone, cambia y quita el plano`() {
        val id = create("Obra", null)

        runBlocking {
            projects.update(id, projects.project(id)!!.toDraft().copy(terrain = plane))
            projects.project(id)!!.terrain shouldBe plane
            projects.update(id, projects.project(id)!!.toDraft().copy(terrain = plane.copy(factor = 1.0001)))
            projects.project(id)!!.terrain shouldBe plane.copy(factor = 1.0001)
            projects.update(id, projects.project(id)!!.toDraft().copy(terrain = null))
            projects.project(id)!!.terrain.shouldBeNull()
        }
    }

    @Test
    fun `S058 RF-6 duplicar o restaurar de la papelera conserva el plano`() {
        val id = create("Obra", plane)

        runBlocking {
            val copy = (projects.duplicate(id, "copia") as SaveResult.Saved).id
            projects.project(copy)!!.terrain shouldBe plane
            projects.moveToTrash(id)
            projects.restore(id, "restaurado")
            projects.project(id)!!.terrain shouldBe plane
        }
    }

    @Test
    fun `S058 RF-1 un proyecto tiene el borrador con su plano`() {
        val id = create("Obra", plane)

        runBlocking { projects.project(id) }.shouldNotBeNull().toDraft().terrain shouldBe plane
    }
}
