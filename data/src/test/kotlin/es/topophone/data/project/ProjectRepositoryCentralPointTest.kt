package es.topophone.data.project

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CentralPoint
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDraft
import es.topophone.data.db.TopoPhoneDatabase
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** El punto central en el repositorio de proyectos (S006/RF-32, RF-35, RF-38). */
@RunWith(AndroidJUnit4::class)
class ProjectRepositoryCentralPointTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TopoPhoneDatabase::class.java).build()
    private var nextId = 0
    private val repository = RoomProjectRepository(db, clock = { 1_792_080_000_000L }, newId = { "id-${++nextId}" })
    private val central = CentralPoint(39.46991234567891, -0.37631234567891)

    @After
    fun close() = db.close()

    private fun draft(name: String = "Calle Mayor 12", centralPoint: CentralPoint? = null) = ProjectDraft(
        name = name,
        author = "",
        description = "",
        crs = ProjectCrs.ETRS89_UTM30,
        linearUnit = LinearUnit.METRE,
        angularUnit = AngularUnit.DMS,
        correctionsDatum = CorrectionsDatum.ETRS89,
        centralPoint = centralPoint,
    )

    private suspend fun create(centralPoint: CentralPoint?) =
        repository.create(draft(centralPoint = centralPoint)).shouldBeInstanceOf<SaveResult.Saved>().id

    @Test
    fun `S006 RF-32 un proyecto nuevo no tiene punto central`() = runBlocking<Unit> {
        repository.project(create(null))!!.centralPoint.shouldBeNull()
    }

    @Test
    fun `S006 RF-32 el punto central se guarda sin redondear`() = runBlocking<Unit> {
        repository.project(create(central))!!.centralPoint shouldBe central
    }

    @Test
    fun `S006 RF-32 el punto central se fija y se cambia al editar`() = runBlocking<Unit> {
        val id = create(null)
        repository.update(id, draft(centralPoint = central))
        repository.project(id)!!.centralPoint shouldBe central

        val other = CentralPoint(41.65, -0.88)
        repository.update(id, draft(centralPoint = other))
        repository.project(id)!!.centralPoint shouldBe other
    }

    @Test
    fun `S006 RF-35 al borrar el punto central el proyecto queda sin el`() = runBlocking<Unit> {
        val id = create(central)

        repository.update(id, draft(centralPoint = null))

        repository.project(id)!!.centralPoint.shouldBeNull()
    }

    @Test
    fun `S006 RF-38 la copia de un proyecto conserva el punto central`() = runBlocking<Unit> {
        val id = create(central)

        val copy = repository.duplicate(id, "copia").shouldBeInstanceOf<SaveResult.Saved>().id

        repository.project(copy)!!.centralPoint shouldBe central
        repository.project(id)!!.centralPoint shouldBe central
    }

    @Test
    fun `S006 RF-38 restaurar de la papelera conserva el punto central`() = runBlocking<Unit> {
        val id = create(central)

        repository.moveToTrash(id)
        repository.restore(id, "restaurado")

        repository.project(id)!!.centralPoint shouldBe central
    }
}
