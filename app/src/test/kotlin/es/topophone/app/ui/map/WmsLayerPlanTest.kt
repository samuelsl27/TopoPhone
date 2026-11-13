package es.topophone.app.ui.map

import es.topophone.core.formats.wms.WmsPresets
import es.topophone.core.formats.wms.WmsRequests
import es.topophone.core.model.wms.ProjectMapLayer
import es.topophone.core.model.wms.WmsAddress
import es.topophone.core.model.wms.WmsAddressResult
import es.topophone.core.model.wms.WmsLayerInfo
import es.topophone.core.model.wms.WmsRequestMode
import es.topophone.core.model.wms.WmsService
import es.topophone.data.wms.ProjectLayerView
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test

/** Qué capas WMS del proyecto se dibujan, en qué orden, con qué petición y con qué opacidad (S036/RF-17, RF-20 a RF-22, RF-29). */
class WmsLayerPlanTest {

    private val own = service("own", "https://ejemplo.es/wms", "1.3.0", listOf("image/png", "image/jpeg"))

    private fun service(
        id: String,
        address: String,
        version: String,
        formats: List<String>,
        mode: WmsRequestMode = WmsRequestMode.TILES,
        presetKey: String? = null,
        attribution: String = "",
        title: String = "Servicio $id",
    ) = WmsService(id, "Mi $id", address, title, version, attribution, presetKey, mode, formats, listOf("text/plain"), 0L, 0L)

    private fun view(
        name: String,
        position: Int,
        service: WmsService = own,
        visible: Boolean = true,
        opacity: Int = 100,
        code: String? = "EPSG:3857",
        available: Boolean = true,
    ): ProjectLayerView {
        val layer = ProjectMapLayer("${service.id}-$name", "p", service.id, name, position, visible, opacity)
        val info = if (available) WmsLayerInfo(0, 0, name, "Capa $name", "", true, true, code) else null
        return ProjectLayerView(layer, service, info, showService = false)
    }

    private fun ids(plan: WmsLayerPlan) = plan.layers.map { it.id }

    @Test
    fun `S036 RF-22 la primera de la lista queda encima y se anade la ultima antes`() {
        val plan = WmsLayerPlan.of(listOf(view("a", 0), view("b", 1), view("c", 2)))

        ids(plan) shouldBe listOf("own-c", "own-b", "own-a")
    }

    @Test
    fun `S036 RF-22 el orden lo da la posicion y no el de la lista recibida`() {
        val plan = WmsLayerPlan.of(listOf(view("c", 2), view("a", 0), view("b", 1)))

        ids(plan) shouldBe listOf("own-c", "own-b", "own-a")
    }

    @Test
    fun `S036 RF-20 una capa oculta no se pide pero sigue en el proyecto`() {
        val list = listOf(view("a", 0), view("b", 1, visible = false), view("c", 2))

        ids(WmsLayerPlan.of(list)) shouldBe listOf("own-c", "own-a")
        list.size shouldBe 3
    }

    @Test
    fun `S036 RF-27 una capa no disponible no se pide`() {
        val plan = WmsLayerPlan.of(listOf(view("a", 0, available = false), view("b", 1)))

        ids(plan) shouldBe listOf("own-b")
    }

    @Test
    fun `S036 RF-12 una capa sin Web Mercator no se pide aunque el servicio la ofrezca`() {
        val plan = WmsLayerPlan.of(listOf(view("a", 0, code = null), view("b", 1)))

        ids(plan) shouldBe listOf("own-b")
    }

    @Test
    fun `S036 RF-21 la opacidad va del 0 al 1 en pasos de 0,05 y al 0 por ciento sigue en el plan`() {
        val plan = WmsLayerPlan.of(listOf(view("a", 0, opacity = 0), view("b", 1, opacity = 45), view("c", 2, opacity = 100)))

        plan.layers.map { it.opacity } shouldBe listOf(1f, 0.45f, 0f)
    }

    @Test
    fun `S036 RF-29 una capa por teselas pide EPSG 3857 con la plantilla del cuadro de MapLibre`() {
        val tiles = WmsLayerPlan.of(listOf(view("capa uno|x", 0))).layers.single().source.shouldBeInstanceOf<WmsLayerSource.Tiles>()

        tiles.template shouldContain WmsRequests.BBOX_PLACEHOLDER
        tiles.template shouldContain "REQUEST=GetMap"
        tiles.template shouldContain "CRS=EPSG:3857"
        tiles.template shouldContain "WIDTH=256&HEIGHT=256"
        tiles.template shouldContain "LAYERS=capa%20uno%7Cx"
        val address = (WmsAddress.parse(own.address) as WmsAddressResult.Valid).address
        tiles.template shouldBe WmsRequests.tileTemplate(address, "1.3.0", "capa uno|x", "EPSG:3857", "image/png")
    }

    @Test
    fun `S036 RF-29 la version que declara el servicio decide CRS o SRS y el codigo es el que anuncia la capa`() {
        val old = service("old", "https://viejo.es/wms", "1.1.1", listOf("image/png"))

        val plan = WmsLayerPlan.of(listOf(view("a", 0, old, code = "EPSG:900913")))

        val template = plan.layers.single().source.shouldBeInstanceOf<WmsLayerSource.Tiles>().template
        template shouldContain "VERSION=1.1.1"
        template shouldContain "SRS=EPSG:900913"
        template shouldNotContain "CRS="
    }

    @Test
    fun `S036 RF-29 el formato es el del preset y si el servicio no lo ofrece el primero que anuncia`() {
        val pnoaAddress = "https://www.ign.es/wms-inspire/pnoa-ma"
        val pnoa = service("pnoa", pnoaAddress, "1.3.0", listOf("image/png", "image/jpeg"), presetKey = "ign-pnoa-ma")
        val onlyJpeg = service("jpg", "https://solo-jpeg.es/wms", "1.3.0", listOf("image/jpeg"))
        val unknown = service("raro", "https://raro.es/wms", "1.3.0", listOf("image/gif", "image/webp"))
        val silent = service("mudo", "https://mudo.es/wms", "1.3.0", emptyList())

        fun format(service: WmsService) = WmsLayerPlan.of(listOf(view("a", 0, service))).layers.single().source
            .shouldBeInstanceOf<WmsLayerSource.Tiles>().template.substringAfter("FORMAT=").substringBefore("&")

        WmsPresets.byKey("ign-pnoa-ma")?.preferredFormat shouldBe "image/jpeg"
        format(pnoa) shouldBe "image/jpeg"
        format(own) shouldBe "image/png"
        format(onlyJpeg) shouldBe "image/jpeg"
        format(unknown) shouldBe "image/gif"
        format(silent) shouldBe "image/png"
    }

    @Test
    fun `S036 RF-17 una capa en modo una imagen lleva lo que hace falta para pedir la zona visible y no una plantilla`() {
        val grafcan = service(
            "gc",
            "https://idecan1.grafcan.es/ServicioWMS/OrtoUrb",
            "1.1.1",
            listOf("image/jpeg"),
            mode = WmsRequestMode.IMAGE,
            presetKey = "grafcan-orto-urbana",
        )

        val image = WmsLayerPlan.of(listOf(view("OU", 0, grafcan))).layers.single().source.shouldBeInstanceOf<WmsLayerSource.Image>()

        image.address.text shouldBe "https://idecan1.grafcan.es/ServicioWMS/OrtoUrb"
        image.version shouldBe "1.1.1"
        image.layerName shouldBe "OU"
        image.code shouldBe "EPSG:3857"
        image.format shouldBe "image/jpeg"
    }

    @Test
    fun `S036 RF-17 el plan separa las capas por teselas de las de una imagen sin cambiar su orden`() {
        val image = service("img", "https://imagen.es/wms", "1.3.0", listOf("image/png"), mode = WmsRequestMode.IMAGE)

        val plan = WmsLayerPlan.of(listOf(view("a", 0), view("b", 1, image), view("c", 2)))

        ids(plan) shouldBe listOf("own-c", "img-b", "own-a")
        plan.layers.map { it.source is WmsLayerSource.Image } shouldBe listOf(false, true, false)
        plan.hasImages shouldBe true
        WmsLayerPlan.of(listOf(view("a", 0))).hasImages shouldBe false
    }

    @Test
    fun `S036 RF-33 sin capas visibles el plan esta vacio y es el vacio de siempre`() {
        WmsLayerPlan.of(emptyList()) shouldBe WmsLayerPlan.EMPTY
        WmsLayerPlan.of(listOf(view("a", 0, visible = false))) shouldBe WmsLayerPlan.EMPTY
    }

    @Test
    fun `S036 RF-22 el mismo orden, visibilidad y opacidad dan el mismo plan para no rehacer el mapa`() {
        val list = listOf(view("a", 0, opacity = 50), view("b", 1))

        WmsLayerPlan.of(list) shouldBe WmsLayerPlan.of(list.map { it.copy() })
        WmsLayerPlan.of(list) shouldBe WmsLayerPlan.of(listOf(view("a", 0, opacity = 50), view("b", 1)))
        (WmsLayerPlan.of(list) == WmsLayerPlan.of(listOf(view("a", 0, opacity = 55), view("b", 1)))) shouldBe false
    }
}
