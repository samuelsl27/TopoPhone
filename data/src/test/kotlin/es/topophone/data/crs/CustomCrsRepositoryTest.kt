package es.topophone.data.crs

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.project.CUSTOM_EPSG
import es.topophone.core.model.project.CrsDefinition
import es.topophone.core.model.project.CustomHeight
import es.topophone.data.db.TopoPhoneDatabase
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Mis SC: guardar, editar, renombrar, duplicar y borrar con nombres únicos (S035/RF-9, RF-10). */
@RunWith(AndroidJUnit4::class)
class CustomCrsRepositoryTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private var now = 1_000L
    private var counter = 0
    private val repository = RoomCustomCrsRepository(db, { now++ }, { "id-${counter++}" })

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

    private fun saved(result: CustomCrsSave) = result.shouldBeInstanceOf<CustomCrsSave.Saved>().crs

    @Test
    fun `S035 RF-10 un SC guardado vuelve igual, con su altura y sin redondear`() = runBlocking<Unit> {
        val crs = saved(repository.create(definition("Obra", -3.123456789012345), CustomHeight.ConstantGeoid(48.123456789012345)))

        repository.get(crs.id) shouldBe crs
        repository.all() shouldBe listOf(crs)
        repository.observeAll().first() shouldBe listOf(crs)
        crs.definition.parameters["longitudeOfOriginDeg"] shouldBe -3.123456789012345
    }

    @Test
    fun `S035 RF-5 las tres alturas se guardan y vuelven`() = runBlocking<Unit> {
        val a = saved(repository.create(definition("A"), CustomHeight.Ellipsoidal))
        val b = saved(repository.create(definition("B"), CustomHeight.Geoid("es_ign_egm08-rednap.tif")))
        val c = saved(repository.create(definition("C"), CustomHeight.ConstantGeoid(-12.5)))

        repository.get(a.id)!!.height shouldBe CustomHeight.Ellipsoidal
        repository.get(b.id)!!.height shouldBe CustomHeight.Geoid("es_ign_egm08-rednap.tif")
        repository.get(c.id)!!.height shouldBe CustomHeight.ConstantGeoid(-12.5)
    }

    @Test
    fun `S035 RF-10 el nombre no se repite sin distinguir mayusculas ni espacios de mas`() = runBlocking<Unit> {
        saved(repository.create(definition("Obra Norte"), CustomHeight.Ellipsoidal))

        repository.create(definition("obra norte"), CustomHeight.Ellipsoidal) shouldBe CustomCrsSave.NameTaken
        repository.create(definition("  OBRA   NORTE "), CustomHeight.Ellipsoidal) shouldBe CustomCrsSave.NameTaken
        repository.all().size shouldBe 1
    }

    @Test
    fun `S035 RF-10 un nombre vacio no se guarda y el nombre se guarda limpio`() = runBlocking<Unit> {
        repository.create(definition("   "), CustomHeight.Ellipsoidal) shouldBe CustomCrsSave.EmptyName
        saved(repository.create(definition("  Obra   sur "), CustomHeight.Ellipsoidal)).name shouldBe "Obra sur"
    }

    @Test
    fun `S035 RF-10 editar cambia la definicion y la fecha de modificacion y conserva la de creacion`() = runBlocking<Unit> {
        val crs = saved(repository.create(definition("Obra"), CustomHeight.Ellipsoidal))

        val edited = saved(repository.update(crs.id, definition("Obra", -2.0), CustomHeight.ConstantGeoid(3.0)))

        edited.definition.parameters["longitudeOfOriginDeg"] shouldBe -2.0
        edited.height shouldBe CustomHeight.ConstantGeoid(3.0)
        edited.createdAtMs shouldBe crs.createdAtMs
        (edited.modifiedAtMs > crs.modifiedAtMs) shouldBe true
        repository.all().size shouldBe 1
    }

    @Test
    fun `S035 RF-10 renombrar a un nombre de otro SC se rechaza y a su propio nombre con otras mayusculas se acepta`() = runBlocking<Unit> {
        val a = saved(repository.create(definition("Alfa"), CustomHeight.Ellipsoidal))
        saved(repository.create(definition("Beta"), CustomHeight.Ellipsoidal))

        repository.rename(a.id, "beta") shouldBe CustomCrsSave.NameTaken
        saved(repository.rename(a.id, "ALFA")).name shouldBe "ALFA"
        saved(repository.rename(a.id, "Gamma")).definition.name shouldBe "Gamma"
        repository.rename("no-existe", "X") shouldBe CustomCrsSave.NotFound
    }

    @Test
    fun `S035 RF-10 duplicar crea una copia con nombre propio, tantas veces como se pida`() = runBlocking<Unit> {
        val a = saved(repository.create(definition("Obra"), CustomHeight.Ellipsoidal))

        saved(repository.duplicate(a.id)).name shouldBe "Obra (copia)"
        saved(repository.duplicate(a.id)).name shouldBe "Obra (copia 2)"
        saved(repository.duplicate(a.id)).name shouldBe "Obra (copia 3)"
        repository.all().size shouldBe 4
        repository.duplicate("no-existe") shouldBe CustomCrsSave.NotFound
    }

    @Test
    fun `S035 RF-10 borrar lo quita de la lista`() = runBlocking<Unit> {
        val a = saved(repository.create(definition("Obra"), CustomHeight.Ellipsoidal))

        repository.delete(a.id)

        repository.get(a.id).shouldBeNull()
        repository.all() shouldBe emptyList()
    }

    @Test
    fun `S035 RF-10 la lista sale ordenada por nombre sin distinguir mayusculas`() = runBlocking<Unit> {
        saved(repository.create(definition("beta"), CustomHeight.Ellipsoidal))
        saved(repository.create(definition("Alfa"), CustomHeight.Ellipsoidal))
        saved(repository.create(definition("Gamma"), CustomHeight.Ellipsoidal))

        repository.all().map { it.name } shouldBe listOf("Alfa", "beta", "Gamma")
    }
}
