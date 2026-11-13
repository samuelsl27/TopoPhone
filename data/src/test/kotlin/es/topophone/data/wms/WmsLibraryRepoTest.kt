package es.topophone.data.wms

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.wms.WmsCapabilities
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.wms.WmsAddress
import es.topophone.core.model.wms.WmsAddressResult
import es.topophone.core.model.wms.WmsLayerInfo
import es.topophone.core.model.wms.WmsRequestMode
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** La biblioteca de servicios WMS: guardar con sus capas, no duplicar, renombrar, borrar y actualizar (S036/RF-3 a RF-7, RF-15, RF-27). */
@RunWith(AndroidJUnit4::class)
class WmsLibraryRepoTest {

    private lateinit var db: TopoPhoneDatabase
    private lateinit var library: RoomWmsLibraryRepository
    private lateinit var projects: RoomProjectRepository
    private var clock = 1_000L
    private var ids = 0

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java).build()
        projects = RoomProjectRepository(db)
        library = RoomWmsLibraryRepository(db, clock = { clock++ }, newId = { "s${++ids}" })
    }

    @After
    fun close() = db.close()

    private fun address(text: String) = (WmsAddress.parse(text) as WmsAddressResult.Valid).address

    private fun caps(
        vararg names: String?,
        title: String = "Servicio de ejemplo",
        version: String = "1.3.0",
        attribution: String = "© Ejemplo",
        map: List<String> = listOf("image/png", "image/jpeg"),
        info: List<String> = listOf("text/plain"),
    ) = WmsCapabilities(
        version,
        title,
        attribution,
        map,
        info,
        names.mapIndexed { i, name ->
            WmsLayerInfo(i, if (i == 0) 0 else 1, name, "Capa $i", "Resumen $i", true, i % 2 == 0, if (name == null) null else "EPSG:3857")
        },
    )

    private fun project(name: String): String = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = name)).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private fun added(result: WmsAddResult) = result.shouldBeInstanceOf<WmsAddResult.Added>().service

    private fun use(projectId: String, serviceId: String, layer: String, position: Int = 0) = runBlocking {
        val row = ProjectWmsLayerEntity("$projectId-$serviceId-$layer", projectId, serviceId, layer, position, true, 100, 5_000)
        db.wms().insertProjectLayer(row)
    }

    @Test
    fun `S036 RF-4 un servicio se guarda con sus capas y se lee sin conexion`() = runBlocking<Unit> {
        val service = added(library.add(address("https://x.es/wms"), caps(null, "a", "b"), WmsRequestMode.TILES))

        service.title shouldBe "Servicio de ejemplo"
        service.name shouldBe "Servicio de ejemplo"
        service.version shouldBe "1.3.0"
        service.attribution shouldBe "© Ejemplo"
        service.mapFormats shouldBe listOf("image/png", "image/jpeg")
        service.infoFormats shouldBe listOf("text/plain")
        service.requestMode shouldBe WmsRequestMode.TILES
        library.layers(service.id).map { it.name } shouldBe listOf(null, "a", "b")
        library.layers(service.id).map { it.depth } shouldBe listOf(0, 1, 1)
        library.layers(service.id).map { it.queryable } shouldBe listOf(true, false, true)
        library.layers(service.id).map { it.abstract } shouldBe listOf("Resumen 0", "Resumen 1", "Resumen 2")
    }

    @Test
    fun `S036 RF-4 un servicio sin formatos de consulta guarda listas vacias`() = runBlocking<Unit> {
        val service = added(library.add(address("https://x.es/wms"), caps("a", info = emptyList()), WmsRequestMode.TILES))

        library.service(service.id)?.infoFormats shouldBe emptyList()
    }

    @Test
    fun `S036 RF-14 un servicio de un preset guarda su clave y su nombre`() = runBlocking<Unit> {
        val service = added(
            library.add(address("https://x.es/wms"), caps("a"), WmsRequestMode.IMAGE, presetKey = "catastro-wms", name = "Catastro"),
        )

        service.presetKey shouldBe "catastro-wms"
        service.name shouldBe "Catastro"
        service.requestMode shouldBe WmsRequestMode.IMAGE
    }

    @Test
    fun `S036 RF-15 una direccion equivalente no se duplica y devuelve la que hay`() = runBlocking<Unit> {
        val first = added(library.add(address("https://X.es:443/wms?map=a&b=1"), caps("a"), WmsRequestMode.TILES))

        val again = library.add(address("x.es/wms?b=1&map=a&SERVICE=WMS"), caps("a", "b"), WmsRequestMode.IMAGE)

        again.shouldBeInstanceOf<WmsAddResult.AlreadyThere>().service.id shouldBe first.id
        library.services().first().size shouldBe 1
        library.layers(first.id).size shouldBe 1
    }

    @Test
    fun `S036 RF-15 se encuentra por direccion el servicio guardado`() = runBlocking<Unit> {
        val saved = added(library.add(address("https://x.es/wms?map=a"), caps("a"), WmsRequestMode.TILES))

        library.findByAddress(address("HTTPS://x.es/wms?map=a"))?.id shouldBe saved.id
        library.findByAddress(address("https://x.es/wms?map=b")) shouldBe null
    }

    @Test
    fun `S036 RF-5 la direccion guardada vuelve a ser la misma al leerla`() = runBlocking<Unit> {
        val original = address("https://x.es/cgi-bin/mapserv?map=/datos/obra.map&Idioma=es")
        val saved = added(library.add(original, caps("a"), WmsRequestMode.TILES))

        address(saved.address).key shouldBe original.key
        address(saved.address).parameters shouldBe original.parameters
    }

    @Test
    fun `S036 RF-3 los servicios salen por nombre sin distinguir mayusculas`() = runBlocking<Unit> {
        library.add(address("https://x.es/a"), caps("a"), WmsRequestMode.TILES, name = "beta")
        library.add(address("https://x.es/b"), caps("a"), WmsRequestMode.TILES, name = "Alfa")
        library.add(address("https://x.es/c"), caps("a"), WmsRequestMode.TILES, name = "gamma")

        library.services().first().map { it.name } shouldBe listOf("Alfa", "beta", "gamma")
    }

    @Test
    fun `S036 RF-7 renombrar cambia el nombre, quita los espacios y no admite uno en blanco`() = runBlocking<Unit> {
        val service = added(library.add(address("https://x.es/wms"), caps("a"), WmsRequestMode.TILES))

        library.rename(service.id, "  Ortofoto obra  ") shouldBe true
        library.service(service.id)?.name shouldBe "Ortofoto obra"
        library.rename(service.id, "   ") shouldBe false
        library.rename("no-existe", "x") shouldBe false
        library.service(service.id)?.name shouldBe "Ortofoto obra"
    }

    @Test
    fun `S036 RF-17 se puede cambiar el modo de peticion de un servicio`() = runBlocking<Unit> {
        val service = added(library.add(address("https://x.es/wms"), caps("a"), WmsRequestMode.TILES))

        library.setRequestMode(service.id, WmsRequestMode.IMAGE) shouldBe true
        library.service(service.id)?.requestMode shouldBe WmsRequestMode.IMAGE
        library.setRequestMode("no-existe", WmsRequestMode.TILES) shouldBe false
    }

    @Test
    fun `S036 RF-7 los proyectos con capas del servicio salen con su recuento sin los de la papelera`() = runBlocking<Unit> {
        val service = added(library.add(address("https://x.es/wms"), caps("a", "b", "c"), WmsRequestMode.TILES))
        val obra = project("Obra")
        val casa = project("casa")
        val vieja = project("Vieja")
        use(obra, service.id, "a")
        use(obra, service.id, "b", 1)
        use(casa, service.id, "c")
        use(vieja, service.id, "a")
        db.openHelper.writableDatabase.execSQL("UPDATE projects SET deleted_at = 9 WHERE id = '$vieja'")

        library.projectsUsing(service.id) shouldBe listOf(WmsServiceUse(casa, "casa", 1), WmsServiceUse(obra, "Obra", 2))
    }

    @Test
    fun `S036 RF-7 un servicio sin proyectos no tiene usos`() = runBlocking<Unit> {
        val service = added(library.add(address("https://x.es/wms"), caps("a"), WmsRequestMode.TILES))

        library.projectsUsing(service.id) shouldBe emptyList()
    }

    @Test
    fun `S036 RF-7 borrar quita el servicio, sus capas y las de los proyectos y deja los demas`() = runBlocking<Unit> {
        val one = added(library.add(address("https://x.es/a"), caps("a", "b"), WmsRequestMode.TILES))
        val two = added(library.add(address("https://x.es/b"), caps("a"), WmsRequestMode.TILES))
        val obra = project("Obra")
        use(obra, one.id, "a")
        use(obra, two.id, "a", 1)

        library.delete(one.id)

        library.service(one.id) shouldBe null
        library.layers(one.id) shouldBe emptyList()
        library.services().first().map { it.id } shouldBe listOf(two.id)
        db.wms().projectLayers(obra).first().map { it.serviceId } shouldBe listOf(two.id)
    }

    @Test
    fun `S036 RF-6 actualizar cambia lo leido y avisa de las capas de proyectos que ya no estan`() = runBlocking<Unit> {
        val service = added(library.add(address("https://x.es/wms"), caps(null, "a", "b", "c"), WmsRequestMode.TILES))
        val obra = project("Obra")
        val casa = project("Casa")
        use(obra, service.id, "a")
        use(obra, service.id, "b", 1)
        use(casa, service.id, "c")
        clock = 50_000

        val reread = caps(
            null,
            "a",
            "d",
            title = "Nuevo título",
            version = "1.1.1",
            attribution = "© Otro",
            map = listOf("image/gif"),
            info = emptyList(),
        )

        val missing = library.refresh(service.id, reread)

        missing shouldBe listOf(MissingProjectLayer(casa, "Casa", "c"), MissingProjectLayer(obra, "Obra", "b"))
        val updated = library.service(service.id)!!
        updated.title shouldBe "Nuevo título"
        updated.version shouldBe "1.1.1"
        updated.attribution shouldBe "© Otro"
        updated.mapFormats shouldBe listOf("image/gif")
        updated.infoFormats shouldBe emptyList()
        updated.name shouldBe "Servicio de ejemplo" // el nombre que puso el usuario no cambia
        (updated.updatedAtMs >= 50_000) shouldBe true
        library.layers(service.id).map { it.name } shouldBe listOf(null, "a", "d")
        db.wms().projectLayers(obra).first().size shouldBe 2 // siguen en el proyecto, como «no disponibles»
    }

    @Test
    fun `S036 RF-27 una capa que ya solo agrupa o ya no ofrece Web Mercator tambien cuenta como desaparecida`() = runBlocking<Unit> {
        val service = added(library.add(address("https://x.es/wms"), caps("a", "b"), WmsRequestMode.TILES))
        val obra = project("Obra")
        use(obra, service.id, "a")
        use(obra, service.id, "b", 1)
        val now = WmsCapabilities(
            "1.3.0",
            "S",
            "",
            emptyList(),
            emptyList(),
            listOf(
                WmsLayerInfo(0, 0, "a", "A", "", true, false, null), // ya no ofrece Web Mercator
                WmsLayerInfo(1, 0, null, "B", "", true, false, "EPSG:3857"), // ya solo agrupa
            ),
        )

        library.refresh(service.id, now)?.map { it.layerName } shouldBe listOf("a", "b")
    }

    @Test
    fun `S036 RF-6 actualizar no avisa de las capas de un proyecto de la papelera`() = runBlocking<Unit> {
        val service = added(library.add(address("https://x.es/wms"), caps("a"), WmsRequestMode.TILES))
        val vieja = project("Vieja")
        use(vieja, service.id, "a")
        db.openHelper.writableDatabase.execSQL("UPDATE projects SET deleted_at = 9 WHERE id = '$vieja'")

        library.refresh(service.id, caps("z")) shouldBe emptyList()
    }

    @Test
    fun `S036 RF-6 actualizar un servicio que no existe da nulo y no guarda nada`() = runBlocking<Unit> {
        library.refresh("no-existe", caps("a")) shouldBe null

        library.services().first() shouldBe emptyList()
    }

    @Test
    fun `S036 RF-4 un documento de varios miles de capas se guarda entero por lotes`() = runBlocking<Unit> {
        val names = List(3_500) { "capa-$it" }.toTypedArray()

        val service = added(library.add(address("https://x.es/grande"), caps(*names), WmsRequestMode.TILES))

        library.layers(service.id).size shouldBe 3_500
        library.layers(service.id).last().name shouldBe "capa-3499"
    }
}
