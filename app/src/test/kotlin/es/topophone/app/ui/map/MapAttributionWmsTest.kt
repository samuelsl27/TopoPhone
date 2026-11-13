package es.topophone.app.ui.map

import es.topophone.core.model.map.Basemap
import es.topophone.core.model.map.MapSettings
import es.topophone.core.model.wms.ProjectMapLayer
import es.topophone.core.model.wms.WmsLayerInfo
import es.topophone.core.model.wms.WmsRequestMode
import es.topophone.core.model.wms.WmsService
import es.topophone.data.wms.ProjectLayerView
import io.kotest.matchers.shouldBe
import org.junit.Test

/** La atribución de cada servicio con una capa visible, junto a la del mapa base y la del Catastro (S036/RF-30, RF-33; S009/RF-9). */
class MapAttributionWmsTest {

    private fun service(id: String, attribution: String, title: String = "", presetKey: String? = null, name: String = "Mi $id") =
        WmsService(
            id = id,
            name = name,
            address = "https://$id.es/wms",
            title = title,
            version = "1.3.0",
            attribution = attribution,
            presetKey = presetKey,
            requestMode = WmsRequestMode.TILES,
            mapFormats = listOf("image/png"),
            infoFormats = emptyList(),
            createdAtMs = 0L,
            updatedAtMs = 0L,
        )

    private fun view(service: WmsService, layer: String, position: Int, visible: Boolean = true) = ProjectLayerView(
        ProjectMapLayer("${service.id}-$layer", "p", service.id, layer, position, visible, 100),
        service,
        WmsLayerInfo(0, 0, layer, layer, "", true, true, "EPSG:3857"),
        showService = false,
    )

    private fun attributions(vararg views: ProjectLayerView) = MapAttribution.wms(WmsLayerPlan.of(views.toList()))

    @Test
    fun `S036 RF-30 cada servicio con una capa visible pone su atribucion una sola vez`() {
        val a = service("a", "© Servicio A")
        val b = service("b", "© Servicio B")

        attributions(view(a, "uno", 0), view(b, "dos", 1), view(a, "tres", 2)) shouldBe listOf("© Servicio A", "© Servicio B")
    }

    @Test
    fun `S036 RF-30 una capa oculta no pone la atribucion de su servicio`() {
        val a = service("a", "© Servicio A")
        val b = service("b", "© Servicio B")

        attributions(view(a, "uno", 0), view(b, "dos", 1, visible = false)) shouldBe listOf("© Servicio A")
        attributions(view(a, "uno", 0, visible = false)) shouldBe emptyList()
    }

    @Test
    fun `S036 RF-30 la atribucion del preset manda sobre la del servicio`() {
        val preset = service("pnoa", "texto del servicio", presetKey = "ign-pnoa-ma")

        attributions(view(preset, "OI.OrthoimageCoverage", 0)).single().startsWith("PNOA") shouldBe true
    }

    @Test
    fun `S036 RF-30 sin atribucion sale el titulo del servicio y sin titulo su nombre`() {
        val titled = service("t", "", title = "Ortofoto de la comarca")
        val unnamed = service("n", "  ", title = "", name = "Mi servicio")

        attributions(view(titled, "a", 0)) shouldBe listOf("Ortofoto de la comarca")
        attributions(view(unnamed, "a", 0)) shouldBe listOf("Mi servicio")
    }

    @Test
    fun `S036 RF-30 la atribucion de arriba es la de la capa de arriba`() {
        val a = service("a", "© Servicio A")
        val b = service("b", "© Servicio B")

        attributions(view(a, "uno", 1), view(b, "dos", 0)) shouldBe listOf("© Servicio B", "© Servicio A")
    }

    @Test
    fun `S036 RF-33 las capas WMS no cambian las fuentes del mapa base ni del Catastro`() {
        val settings = MapSettings(Basemap.OSM_VECTOR, cadastre = true)

        val before = MapAttribution.sources(settings)
        attributions(view(service("a", "© A"), "uno", 0))

        MapAttribution.sources(settings) shouldBe before
        before shouldBe listOf(AttributionSource.OPENFREEMAP, AttributionSource.CADASTRE)
    }
}
