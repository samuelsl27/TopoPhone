package es.topophone.data.crs

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.crs.CustomCrsJson
import es.topophone.core.formats.crs.CustomCrsReadResult
import es.topophone.core.model.project.CUSTOM_EPSG
import es.topophone.core.model.project.CrsDefinition
import es.topophone.core.model.project.CustomCrs
import es.topophone.core.model.project.CustomHeight
import es.topophone.data.db.TopoPhoneDatabase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Importar un SC leído de un JSON a Mis SC: nuevo, igual, y con el nombre repetido (S035/RF-13, RF-15). */
@RunWith(AndroidJUnit4::class)
class ImportCustomCrsTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private var now = 1_000L
    private var counter = 0
    private val repository = RoomCustomCrsRepository(db, { now++ }, { "id-${counter++}" })

    @After
    fun tearDown() = db.close()

    private fun crs(name: String, meridian: Double = -3.0, height: CustomHeight = CustomHeight.Ellipsoidal) = CustomCrs(
        "",
        name,
        CrsDefinition(
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
        ),
        height,
    )

    @Test
    fun `S035 RF-13 un SC con un nombre nuevo se anade`() = runBlocking<Unit> {
        val result = repository.import(crs("Obra"))

        val added = result.shouldBeInstanceOf<CustomCrsImport.Added>().crs
        repository.all() shouldBe listOf(added)
    }

    @Test
    fun `S035 RF-15 con el mismo nombre y la misma definicion no se pregunta ni se duplica`() = runBlocking<Unit> {
        repository.import(crs("Obra"))

        val again = repository.import(crs("OBRA"))

        again.shouldBeInstanceOf<CustomCrsImport.Unchanged>()
        repository.all().size shouldBe 1
    }

    @Test
    fun `S035 RF-15 con el mismo nombre y otra definicion hay que decidir y no se toca nada`() = runBlocking<Unit> {
        val first = repository.import(crs("Obra")).shouldBeInstanceOf<CustomCrsImport.Added>().crs

        val result = repository.import(crs("Obra", meridian = -2.0))

        result.shouldBeInstanceOf<CustomCrsImport.Conflict>().existing shouldBe first
        repository.all() shouldBe listOf(first)
    }

    @Test
    fun `S035 RF-15 una altura distinta tambien es otra definicion`() = runBlocking<Unit> {
        repository.import(crs("Obra"))

        repository.import(crs("Obra", height = CustomHeight.ConstantGeoid(3.0))).shouldBeInstanceOf<CustomCrsImport.Conflict>()
    }

    @Test
    fun `S035 RF-15 reemplazar sustituye la definicion y conserva el identificador y la fecha de creacion`() = runBlocking<Unit> {
        val first = repository.import(crs("Obra")).shouldBeInstanceOf<CustomCrsImport.Added>().crs

        val replaced = repository.import(crs("Obra", meridian = -2.0), CustomCrsConflictChoice.REPLACE)
            .shouldBeInstanceOf<CustomCrsImport.Replaced>().crs

        replaced.id shouldBe first.id
        replaced.createdAtMs shouldBe first.createdAtMs
        replaced.definition.parameters["longitudeOfOriginDeg"] shouldBe -2.0
        repository.all() shouldBe listOf(replaced)
    }

    @Test
    fun `S035 RF-15 conservar ambos anade el nuevo con otro nombre, las veces que haga falta`() = runBlocking<Unit> {
        val first = repository.import(crs("Obra")).shouldBeInstanceOf<CustomCrsImport.Added>().crs

        val second = repository.import(crs("Obra", meridian = -2.0), CustomCrsConflictChoice.KEEP_BOTH)
            .shouldBeInstanceOf<CustomCrsImport.Added>().crs
        val third = repository.import(crs("Obra", meridian = -1.0), CustomCrsConflictChoice.KEEP_BOTH)
            .shouldBeInstanceOf<CustomCrsImport.Added>().crs

        second.name shouldBe "Obra (2)"
        third.name shouldBe "Obra (3)"
        repository.get(first.id) shouldBe first
        repository.all().size shouldBe 3
    }

    @Test
    fun `S035 RF-15 omitir no cambia nada`() = runBlocking<Unit> {
        val first = repository.import(crs("Obra")).shouldBeInstanceOf<CustomCrsImport.Added>().crs

        repository.import(crs("Obra", meridian = -2.0), CustomCrsConflictChoice.SKIP) shouldBe CustomCrsImport.Skipped

        repository.all() shouldBe listOf(first)
    }

    @Test
    fun `S035 RF-13 un SC exportado a JSON y leido de nuevo se importa igual`() = runBlocking<Unit> {
        val original = crs("Obra", meridian = -3.123456789012345, height = CustomHeight.ConstantGeoid(48.5))

        val read = CustomCrsJson.read(CustomCrsJson.write(original)).shouldBeInstanceOf<CustomCrsReadResult.Ok>().crs
        val added = repository.import(read).shouldBeInstanceOf<CustomCrsImport.Added>().crs

        added.definition shouldBe original.definition
        added.height shouldBe original.height
    }
}
