package es.topophone.data.wms

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Las tablas de la biblioteca WMS y de las capas de los proyectos (S036/RF-4, RF-24, RF-26). */
@RunWith(AndroidJUnit4::class)
class WmsEntitiesTest {

    private lateinit var db: TopoPhoneDatabase
    private lateinit var projectId: String
    private val dao get() = db.wms()

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java).build()
        projectId = runBlocking { RoomProjectRepository(db).create(ProjectDefaults().toDraft().copy(name = "Obra")) }
            .shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @After
    fun close() = db.close()

    private fun service(id: String = "s1", address: String = "https://x.es/wms") = WmsServiceEntity(
        id = id,
        name = "Servicio $id",
        address = address,
        addressKey = address,
        title = "Título",
        version = "1.3.0",
        attribution = "© X",
        presetKey = null,
        requestMode = "TILES",
        mapFormats = "image/png|image/jpeg",
        infoFormats = "text/plain",
        createdAt = 1_000,
        updatedAt = 2_000,
    )

    private fun layer(serviceId: String, ordinal: Int, name: String? = "capa-$ordinal") = WmsLayerEntity(
        serviceId = serviceId,
        ordinal = ordinal,
        depth = if (ordinal == 0) 0 else 1,
        name = name,
        title = "Capa $ordinal",
        summary = "Resumen",
        transparent = true,
        queryable = ordinal % 2 == 0,
        mercatorCode = "EPSG:3857",
    )

    private fun projectLayer(id: String, serviceId: String = "s1", name: String = "capa-1", position: Int = 0) =
        ProjectWmsLayerEntity(id, projectId, serviceId, name, position, visible = true, opacity = 100, addedAt = 3_000)

    private fun count(table: String) = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
        it.moveToFirst()
        it.getInt(0)
    }

    @Test
    fun `S036 RF-4 un servicio se guarda con todos sus campos`() = runBlocking<Unit> {
        dao.insertService(service())

        dao.services().first() shouldBe listOf(service())
    }

    @Test
    fun `S036 RF-4 las capas leidas se guardan en el orden del documento con su profundidad`() = runBlocking<Unit> {
        dao.insertService(service())
        dao.insertLayers(listOf(layer("s1", 2), layer("s1", 0, name = null), layer("s1", 1)))

        val layers = dao.layersOf("s1")

        layers.map { it.ordinal } shouldBe listOf(0, 1, 2)
        layers.map { it.name } shouldBe listOf(null, "capa-1", "capa-2")
        layers.map { it.depth } shouldBe listOf(0, 1, 1)
        layers.map { it.mercatorCode } shouldBe List(3) { "EPSG:3857" }
    }

    @Test
    fun `S036 RF-15 dos servicios no pueden tener la misma clave de direccion`() = runBlocking<Unit> {
        dao.insertService(service("s1", "https://x.es/wms"))

        shouldThrow<SQLiteConstraintException> { dao.insertService(service("s2", "https://x.es/wms")) }
    }

    @Test
    fun `S036 RF-7 al borrar un servicio se borran sus capas leidas y las de los proyectos`() = runBlocking<Unit> {
        dao.insertService(service("s1", "https://x.es/a"))
        dao.insertService(service("s2", "https://x.es/b"))
        dao.insertLayers(listOf(layer("s1", 0), layer("s1", 1), layer("s2", 0)))
        dao.insertProjectLayer(projectLayer("p1", "s1"))
        dao.insertProjectLayer(projectLayer("p2", "s2", "capa-0"))

        db.openHelper.writableDatabase.execSQL("DELETE FROM wms_services WHERE id = 's1'")

        count("wms_layers") shouldBe 1
        dao.projectLayers(projectId).first().map { it.id } shouldBe listOf("p2")
    }

    @Test
    fun `S036 RF-24 las capas de un proyecto se guardan por posicion con visibilidad y opacidad`() = runBlocking<Unit> {
        dao.insertService(service())
        dao.insertProjectLayer(projectLayer("p2", name = "capa-2", position = 1).copy(visible = false, opacity = 35))
        dao.insertProjectLayer(projectLayer("p1", name = "capa-1", position = 0))

        val layers = dao.projectLayers(projectId).first()

        layers.map { it.id } shouldBe listOf("p1", "p2")
        layers.map { it.visible } shouldBe listOf(true, false)
        layers.map { it.opacity } shouldBe listOf(100, 35)
    }

    @Test
    fun `S036 RF-24 al borrar el proyecto se borran sus capas pero no el servicio`() = runBlocking<Unit> {
        dao.insertService(service())
        dao.insertLayers(listOf(layer("s1", 0)))
        dao.insertProjectLayer(projectLayer("p1"))

        db.openHelper.writableDatabase.execSQL("DELETE FROM projects WHERE id = '$projectId'")

        count("project_wms_layers") shouldBe 0
        count("wms_services") shouldBe 1
        count("wms_layers") shouldBe 1
    }

    @Test
    fun `S036 RF-26 una capa no se repite en un proyecto`() = runBlocking<Unit> {
        dao.insertService(service())
        dao.insertProjectLayer(projectLayer("p1", name = "capa-1"))

        shouldThrow<SQLiteConstraintException> { dao.insertProjectLayer(projectLayer("p2", name = "capa-1", position = 1)) }
    }

    @Test
    fun `S036 RF-18 la misma capa se puede usar en dos proyectos`() = runBlocking<Unit> {
        dao.insertService(service())
        val other = runBlocking { RoomProjectRepository(db).create(ProjectDefaults().toDraft().copy(name = "Otra")) }
            .shouldBeInstanceOf<SaveResult.Saved>().id
        dao.insertProjectLayer(projectLayer("p1"))
        dao.insertProjectLayer(ProjectWmsLayerEntity("p2", other, "s1", "capa-1", 0, visible = true, opacity = 100, addedAt = 3_000))

        count("project_wms_layers") shouldBe 2
    }

    @Test
    fun `S036 RF-4 un servicio sin capas ni proyectos se guarda y se lista`() = runBlocking<Unit> {
        dao.insertService(service("s1", "https://x.es/a").copy(name = "b"))
        dao.insertService(service("s2", "https://x.es/b").copy(name = "A"))

        dao.services().first().map { it.name } shouldBe listOf("A", "b") // por nombre, sin distinguir mayúsculas
    }

    @Test
    fun `S036 RF-4 las capas con la clave de un servicio que no existe se rechazan`() = runBlocking<Unit> {
        shouldThrow<SQLiteConstraintException> { dao.insertLayers(listOf(layer("no-existe", 0))) }
        shouldThrow<SQLiteConstraintException> { dao.insertProjectLayer(projectLayer("p1", serviceId = "no-existe")) }
    }
}
