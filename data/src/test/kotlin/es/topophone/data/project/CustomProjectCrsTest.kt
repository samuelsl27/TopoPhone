package es.topophone.data.project

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.project.CUSTOM_EPSG
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.CrsDefinition
import es.topophone.core.model.project.CustomHeight
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.project.isCustom
import es.topophone.data.crs.CustomCrsSave
import es.topophone.data.crs.RoomCustomCrsRepository
import es.topophone.data.db.TopoPhoneDatabase
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Un proyecto con un SC personalizado guarda su copia de la definición y su altura, y no cambia si el SC de Mis SC se edita o se borra (S035/RF-5, RF-11). */
@RunWith(AndroidJUnit4::class)
class CustomProjectCrsTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val mine = RoomCustomCrsRepository(db)

    @After
    fun tearDown() = db.close()

    private fun definition(name: String, meridian: Double = -3.0) = CrsDefinition(
        epsg = CUSTOM_EPSG,
        name = name,
        projected = true,
        datumCode = 0,
        datumName = "Personalizado",
        ellipsoidSemiMajorM = 6378137.0,
        ellipsoidInverseFlattening = 298.257222101,
        primeMeridianDeg = 0.0,
        method = "tm",
        parameters = mapOf(
            "latitudeOfOriginDeg" to 0.0,
            "longitudeOfOriginDeg" to meridian,
            "scaleFactor" to 0.9996,
            "falseEasting" to 500000.0,
            "falseNorthing" to 0.0,
        ),
        unitMetres = 1.0,
        area = null,
        frame = "ETRS89",
        transformationName = "No datum shift",
        transformationAccuracyM = null,
        transformationArea = null,
        transformationSteps = "null",
    )

    private fun create(name: String, crs: LibraryCrs): String = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = name, crs = crs)).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @Test
    fun `S035 RF-5 la ondulacion constante se guarda con el proyecto sin redondear y se lee igual`() {
        val crs = LibraryCrs(definition("Obra"), CorrectionsDatum.ETRS89, null, 48.123456789012345)
        val id = create("Obra", crs)

        val read = runBlocking { projects.project(id) }.shouldNotBeNull().crs

        read shouldBe crs
        read.shouldBeInstanceOf<LibraryCrs>().geoidConstantM shouldBe 48.123456789012345
        db.openHelper.readableDatabase.query("SELECT geoid_constant_m, geoid_grid FROM projects").use { c ->
            c.moveToFirst() shouldBe true
            c.getDouble(0) shouldBe 48.123456789012345
            c.isNull(1) shouldBe true
        }
    }

    @Test
    fun `S035 RF-5 un SC con geoide o elipsoidal no guarda ondulacion constante`() {
        val id = create("Obra", LibraryCrs(definition("Obra"), CorrectionsDatum.ETRS89, "egm.tif"))

        runBlocking { projects.project(id) }.shouldNotBeNull().crs.shouldBeInstanceOf<LibraryCrs>().geoidConstantM shouldBe null
        db.openHelper.readableDatabase.query("SELECT geoid_constant_m FROM projects").use { c ->
            c.moveToFirst() shouldBe true
            c.isNull(0) shouldBe true
        }
    }

    @Test
    fun `S035 RF-11 editar o borrar el SC de Mis SC no cambia el SC del proyecto, que conserva su copia`() = runBlocking<Unit> {
        val saved = (mine.create(definition("Obra norte"), CustomHeight.ConstantGeoid(3.0)) as CustomCrsSave.Saved).crs
        val crs = saved.toProjectCrs(CorrectionsDatum.ETRS89)
        val id = create("Obra", crs)

        mine.update(saved.id, definition("Obra norte", meridian = -2.0), CustomHeight.Ellipsoidal)

        val afterEdit = projects.project(id).shouldNotBeNull().crs.shouldBeInstanceOf<LibraryCrs>()
        afterEdit shouldBe crs
        afterEdit.definition.parameters["longitudeOfOriginDeg"] shouldBe -3.0
        afterEdit.geoidConstantM shouldBe 3.0

        mine.delete(saved.id)

        projects.project(id).shouldNotBeNull().crs shouldBe crs
        projects.project(id)!!.crs.shouldBeInstanceOf<LibraryCrs>().definition.isCustom shouldBe true
    }

    @Test
    fun `S035 RF-11 duplicar o restaurar de la papelera un proyecto conserva el SC personalizado con su altura`() = runBlocking<Unit> {
        val crs = LibraryCrs(definition("Obra"), CorrectionsDatum.ETRS89, null, 12.5)
        val id = create("Obra", crs)

        val copy = (projects.duplicate(id, "copia") as SaveResult.Saved).id
        projects.moveToTrash(id)
        projects.restore(id, "restaurado")

        projects.project(copy)!!.crs shouldBe crs
        projects.project(id)!!.crs shouldBe crs
    }
}
