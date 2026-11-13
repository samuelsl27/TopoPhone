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

/** Las capas de cada proyecto: añadir con el tope, repetidas, orden, visibilidad, opacidad y disponibilidad (S036/RF-18 a RF-28). */
@RunWith(AndroidJUnit4::class)
class ProjectLayersRepoTest {

    private lateinit var db: TopoPhoneDatabase
    private lateinit var library: RoomWmsLibraryRepository
    private lateinit var layers: RoomProjectLayersRepository
    private lateinit var obra: String
    private var ids = 0
    private var serviceIds = 0

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java).build()
        library = RoomWmsLibraryRepository(db, newId = { "s${++serviceIds}" })
        layers = RoomProjectLayersRepository(db, newId = { "l${++ids}" })
        obra = project("Obra")
    }

    @After
    fun close() = db.close()

    private fun project(name: String): String = runBlocking {
        RoomProjectRepository(db).create(ProjectDefaults().toDraft().copy(name = name)).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private fun info(i: Int, name: String?, code: String? = "EPSG:3857", title: String = "Capa $i") =
        WmsLayerInfo(i, if (i == 0) 0 else 1, name, title, "", true, i % 2 == 0, code)

    private fun service(url: String, vararg infos: WmsLayerInfo): String = runBlocking {
        val address = (WmsAddress.parse(url) as WmsAddressResult.Valid).address
        val caps = WmsCapabilities("1.3.0", "Servicio", "© X", listOf("image/png"), emptyList(), infos.toList())
        library.add(address, caps, WmsRequestMode.TILES, name = url).shouldBeInstanceOf<WmsAddResult.Added>().service.id
    }

    private fun named(url: String, count: Int): String = service(url, *Array(count) { info(it, "capa-$it") })

    private fun add(serviceId: String, name: String, project: String = obra) = runBlocking { layers.add(project, serviceId, name) }

    private fun views(project: String = obra) = runBlocking { layers.layers(project).first() }

    @Test
    fun `S036 RF-19 una capa nueva entra con visibilidad y opacidad 100`() {
        val s = named("https://x.es/a", 3)

        val result = add(s, "capa-1").shouldBeInstanceOf<AddLayerResult.Added>().layer

        result.position shouldBe 0
        result.visible shouldBe true
        result.opacity shouldBe 100
        views().single().layer shouldBe result
    }

    @Test
    fun `S036 RF-22 la ultima capa anadida queda la primera de la lista`() {
        val s = named("https://x.es/a", 4)

        add(s, "capa-0")
        add(s, "capa-1")
        add(s, "capa-2")

        views().map { it.layer.layerName } shouldBe listOf("capa-2", "capa-1", "capa-0")
        views().map { it.layer.position } shouldBe listOf(0, 1, 2)
    }

    @Test
    fun `S036 RF-26 el limite es de 10 capas por proyecto y cada proyecto tiene el suyo`() {
        val s = named("https://x.es/a", 12)
        repeat(10) { add(s, "capa-$it").shouldBeInstanceOf<AddLayerResult.Added>() }

        add(s, "capa-10") shouldBe AddLayerResult.LimitReached
        views().size shouldBe 10
        add(s, "capa-10", project("Otra")).shouldBeInstanceOf<AddLayerResult.Added>()
    }

    @Test
    fun `S036 RF-26 una capa repetida en el proyecto dice que ya esta y no cuenta para el limite`() {
        val s = named("https://x.es/a", 3)
        add(s, "capa-1")

        add(s, "capa-1") shouldBe AddLayerResult.AlreadyThere
        views().size shouldBe 1
    }

    @Test
    fun `S036 RF-26 con el proyecto lleno una capa repetida dice que ya esta`() {
        val s = named("https://x.es/a", 11)
        repeat(10) { add(s, "capa-$it") }

        add(s, "capa-3") shouldBe AddLayerResult.AlreadyThere
    }

    @Test
    fun `S036 RF-18 la misma capa se puede usar en dos proyectos`() {
        val s = named("https://x.es/a", 2)
        val otra = project("Otra")

        add(s, "capa-1").shouldBeInstanceOf<AddLayerResult.Added>()
        add(s, "capa-1", otra).shouldBeInstanceOf<AddLayerResult.Added>()

        views().size shouldBe 1
        views(otra).size shouldBe 1
    }

    @Test
    fun `S036 RF-12 una capa sin Web Mercator o que no existe no se anade`() {
        val s = service("https://x.es/a", info(0, "utm", code = null), info(1, null), info(2, "buena"))

        add(s, "utm") shouldBe AddLayerResult.NoWebMercator
        add(s, "no-existe") shouldBe AddLayerResult.NotFound
        add("no-existe", "buena") shouldBe AddLayerResult.NotFound
        add(s, "buena").shouldBeInstanceOf<AddLayerResult.Added>()
        views().size shouldBe 1
    }

    @Test
    fun `S036 RF-12 con dos capas del mismo nombre se toma la que ofrece Web Mercator`() {
        val s = service("https://x.es/a", info(0, "rgb", code = null, title = "Grupo"), info(1, "rgb", title = "Hija"))

        add(s, "rgb").shouldBeInstanceOf<AddLayerResult.Added>()

        views().single().info?.title shouldBe "Hija"
        views().single().available shouldBe true
    }

    @Test
    fun `S036 RF-20 se puede ocultar y volver a mostrar una capa sin quitarla`() = runBlocking<Unit> {
        val s = named("https://x.es/a", 2)
        val id = add(s, "capa-1").shouldBeInstanceOf<AddLayerResult.Added>().layer.id

        layers.setVisible(id, false) shouldBe true
        views().single().layer.visible shouldBe false
        layers.setVisible(id, true) shouldBe true
        views().single().layer.visible shouldBe true
        layers.setVisible("no-existe", false) shouldBe false
    }

    @Test
    fun `S036 RF-21 la opacidad se ajusta a pasos de 5 dentro de 0 a 100`() = runBlocking<Unit> {
        val s = named("https://x.es/a", 2)
        val id = add(s, "capa-1").shouldBeInstanceOf<AddLayerResult.Added>().layer.id

        listOf(35 to 35, 52 to 50, 53 to 55, 0 to 0, -10 to 0, 150 to 100).forEach { (asked, stored) ->
            layers.setOpacity(id, asked) shouldBe true
            views().single().layer.opacity shouldBe stored
        }
        layers.setOpacity("no-existe", 50) shouldBe false
    }

    @Test
    fun `S036 RF-22 mover una capa reordena las demas sin dejar huecos`() = runBlocking<Unit> {
        val s = named("https://x.es/a", 4)
        val ids = (0..3).map { add(s, "capa-$it").shouldBeInstanceOf<AddLayerResult.Added>().layer.id }
        // arriba a abajo: capa-3, capa-2, capa-1, capa-0

        layers.move(ids[0], 0) shouldBe true // capa-0, que era la de abajo, pasa arriba
        views().map { it.layer.layerName } shouldBe listOf("capa-0", "capa-3", "capa-2", "capa-1")
        layers.move(ids[0], 2) shouldBe true
        views().map { it.layer.layerName } shouldBe listOf("capa-3", "capa-2", "capa-0", "capa-1")
        layers.move(ids[3], 3) shouldBe true
        views().map { it.layer.layerName } shouldBe listOf("capa-2", "capa-0", "capa-1", "capa-3")
        views().map { it.layer.position } shouldBe listOf(0, 1, 2, 3)
    }

    @Test
    fun `S036 RF-22 mover fuera del rango la deja al principio o al final`() = runBlocking<Unit> {
        val s = named("https://x.es/a", 3)
        val ids = (0..2).map { add(s, "capa-$it").shouldBeInstanceOf<AddLayerResult.Added>().layer.id }

        layers.move(ids[1], 99) shouldBe true
        views().map { it.layer.layerName } shouldBe listOf("capa-2", "capa-0", "capa-1")
        layers.move(ids[1], -5) shouldBe true
        views().map { it.layer.layerName } shouldBe listOf("capa-1", "capa-2", "capa-0")
        layers.move("no-existe", 0) shouldBe false
    }

    @Test
    fun `S036 RF-23 quitar una capa renumera las demas y deja el servicio`() = runBlocking<Unit> {
        val s = named("https://x.es/a", 3)
        val ids = (0..2).map { add(s, "capa-$it").shouldBeInstanceOf<AddLayerResult.Added>().layer.id }

        layers.remove(ids[1]) shouldBe true

        views().map { it.layer.layerName } shouldBe listOf("capa-2", "capa-0")
        views().map { it.layer.position } shouldBe listOf(0, 1)
        library.service(s)?.id shouldBe s
        layers.remove(ids[1]) shouldBe false
    }

    @Test
    fun `S036 RF-24 el orden, la visibilidad y la opacidad se recuperan al volver a leer`() = runBlocking<Unit> {
        val s = named("https://x.es/a", 3)
        val ids = (0..2).map { add(s, "capa-$it").shouldBeInstanceOf<AddLayerResult.Added>().layer.id }
        layers.setVisible(ids[1], false)
        layers.setOpacity(ids[2], 40)
        layers.move(ids[0], 0)

        val again = RoomProjectLayersRepository(db).layers(obra).first()

        again.map { it.layer.layerName } shouldBe listOf("capa-0", "capa-2", "capa-1")
        again.map { it.layer.visible } shouldBe listOf(true, true, false)
        again.map { it.layer.opacity } shouldBe listOf(100, 40, 100)
    }

    @Test
    fun `S036 RF-27 una capa que el servicio ya no ofrece sigue en el proyecto como no disponible`() = runBlocking<Unit> {
        val s = named("https://x.es/a", 3)
        add(s, "capa-1")
        add(s, "capa-2")

        library.refresh(s, WmsCapabilities("1.3.0", "S", "", emptyList(), emptyList(), listOf(info(0, "capa-2"))))

        val list = views()
        list.map { it.layer.layerName } shouldBe listOf("capa-2", "capa-1")
        list.map { it.available } shouldBe listOf(true, false)
        list[1].info shouldBe null
        list[1].title shouldBe "capa-1"
    }

    @Test
    fun `S036 RF-27 una capa que ya solo agrupa o ya no tiene Web Mercator tambien sale no disponible`() = runBlocking<Unit> {
        val s = named("https://x.es/a", 3)
        add(s, "capa-0")
        add(s, "capa-1")

        val now = listOf(info(0, null), info(1, "capa-1", code = null))
        library.refresh(s, WmsCapabilities("1.3.0", "S", "", emptyList(), emptyList(), now))

        views().map { it.available } shouldBe listOf(false, false)
    }

    @Test
    fun `S036 RF-6 al actualizar el servicio la lista de capas se actualiza sola`() = runBlocking<Unit> {
        val s = named("https://x.es/a", 2)
        add(s, "capa-1")
        val first = layers.layers(obra).first().single().service.version

        library.refresh(s, WmsCapabilities("1.1.1", "Nuevo", "© Nueva", emptyList(), emptyList(), listOf(info(0, "capa-1"))))

        first shouldBe "1.3.0"
        layers.layers(obra).first().single().service.version shouldBe "1.1.1"
        layers.layers(obra).first().single().service.attribution shouldBe "© Nueva"
    }

    @Test
    fun `S036 RF-28 dos capas con el mismo titulo de servicios distintos llevan el nombre del servicio`() {
        val a = service("https://x.es/a", info(0, "orto", title = "Ortofoto"))
        val b = service("https://x.es/b", info(0, "otra", title = "Ortofoto"), info(1, "mapa", title = "Mapa"))
        add(a, "orto")
        add(b, "otra")
        add(b, "mapa")

        views().map { it.layer.layerName to it.showService } shouldBe listOf("mapa" to false, "otra" to true, "orto" to true)
    }

    @Test
    fun `S036 RF-28 capas con titulos distintos o del mismo servicio no llevan el nombre del servicio`() {
        val a = service("https://x.es/a", info(0, "uno", title = "Ortofoto 2024"), info(1, "dos", title = "Ortofoto 2023"))
        add(a, "uno")
        add(a, "dos")

        views().map { it.showService } shouldBe listOf(false, false)
    }

    @Test
    fun `S036 RF-7 al borrar el servicio sus capas desaparecen de los proyectos`() = runBlocking<Unit> {
        val a = named("https://x.es/a", 2)
        val b = named("https://x.es/b", 2)
        add(a, "capa-1")
        add(b, "capa-1")

        library.delete(a)

        views().map { it.service.id } shouldBe listOf(b)
        views().map { it.layer.position } shouldBe listOf(0)
    }

    @Test
    fun `S036 RF-18 un proyecto sin capas da una lista vacia`() {
        views() shouldBe emptyList()
    }

    @Test
    fun `S036 RF-29 la vista lleva lo que hace falta para pedir la capa`() {
        val s = service("https://x.es/wms?map=a", info(0, "rgb", title = "Color"))
        add(s, "rgb")

        val view = views().single()

        view.service.address shouldBe "https://x.es/wms?map=a"
        view.service.version shouldBe "1.3.0"
        view.service.requestMode shouldBe WmsRequestMode.TILES
        view.service.mapFormats shouldBe listOf("image/png")
        view.info?.mercatorCode shouldBe "EPSG:3857"
        view.info?.queryable shouldBe true
        view.info?.transparent shouldBe true
        view.title shouldBe "Color"
    }
}
