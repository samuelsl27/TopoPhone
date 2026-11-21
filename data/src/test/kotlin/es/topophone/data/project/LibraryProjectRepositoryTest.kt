package es.topophone.data.project

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.project.SpainCrs
import es.topophone.data.db.TopoPhoneDatabase
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Un proyecto con un SC de la biblioteca guarda su definición completa y sigue igual al editarlo, duplicarlo o restaurarlo (S034/RF-13, RF-25). */
@RunWith(AndroidJUnit4::class)
class LibraryProjectRepositoryTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)

    private val library: LibraryCrs = when (val result = CrsCatalog.bundled.build(2154, CorrectionsDatum.ETRS89, GeoPoint(48.85, 2.35))) {
        is CrsBuildResult.Ready -> LibraryCrs(result.definition, CorrectionsDatum.ETRS89, "fr_ign_RAF20.tif")
        is CrsBuildResult.NeedsGrids -> LibraryCrs(result.definition, CorrectionsDatum.ETRS89, "fr_ign_RAF20.tif")
        else -> error("$result")
    }

    @After
    fun tearDown() = db.close()

    private fun create(name: String, crs: ProjectCrs = library): String = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = name, crs = crs)).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @Test
    fun `S034 RF-13 el SC de la biblioteca se guarda con su definicion y su geoide y se lee igual`() {
        val id = create("Paris")

        val project = runBlocking { projects.project(id) }.shouldNotBeNull()

        project.crs shouldBe library
        project.crs.shouldBeInstanceOf<LibraryCrs>().geoidGrid shouldBe "fr_ign_RAF20.tif"
        db.openHelper.readableDatabase.query("SELECT crs, crs_definition, geoid_grid FROM projects").use { c ->
            c.moveToFirst() shouldBe true
            c.getString(0) shouldBe "LIBRARY:2154"
            c.getString(1) shouldBe library.definition.toText()
            c.getString(2) shouldBe "fr_ign_RAF20.tif"
        }
    }

    @Test
    fun `S034 RF-25 los proyectos de Espana no guardan definicion y siguen leyendose como siempre`() {
        val id = create("Madrid", SpainCrs.ED50_UTM30)

        runBlocking { projects.project(id) }.shouldNotBeNull().crs shouldBe SpainCrs.ED50_UTM30
        db.openHelper.readableDatabase.query("SELECT crs_definition, geoid_grid FROM projects").use { c ->
            c.moveToFirst() shouldBe true
            c.isNull(0) shouldBe true
            c.isNull(1) shouldBe true
        }
    }

    @Test
    fun `S034 RF-13 cambiar el SC de la biblioteca a uno de Espana quita la definicion y al reves la pone`() {
        val id = create("Obra")

        runBlocking {
            projects.update(id, projects.project(id)!!.toDraft().copy(crs = SpainCrs.ETRS89_UTM30))
            projects.project(id)!!.crs shouldBe SpainCrs.ETRS89_UTM30
            projects.update(id, projects.project(id)!!.toDraft().copy(crs = library))
            projects.project(id)!!.crs shouldBe library
        }
    }

    @Test
    fun `S034 RF-13 duplicar o restaurar de la papelera conserva el SC de la biblioteca`() {
        val id = create("Paris")

        runBlocking {
            val copy = (projects.duplicate(id, "copia") as SaveResult.Saved).id
            projects.project(copy)!!.crs shouldBe library
            projects.moveToTrash(id)
            projects.restore(id, "restaurado")
            projects.project(id)!!.crs shouldBe library
        }
    }

    @Test
    fun `S034 RF-13 una definicion que no se puede leer no impide abrir el proyecto`() {
        val id = create("Paris")
        db.openHelper.writableDatabase.execSQL("UPDATE projects SET crs_definition = 'basura'")

        runBlocking { projects.project(id) }.shouldNotBeNull().crs shouldBe SpainCrs.ETRS89_UTM30
    }
}
