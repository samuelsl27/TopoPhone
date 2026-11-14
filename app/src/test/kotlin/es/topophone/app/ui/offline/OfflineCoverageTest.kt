package es.topophone.app.ui.offline

import es.topophone.app.ui.map.MapCamera
import es.topophone.core.geodesy.GeoBox
import es.topophone.core.model.map.Basemap
import es.topophone.core.model.offline.DetailLevel
import es.topophone.core.model.offline.DownloadableSource
import es.topophone.core.model.offline.OfflineArea
import es.topophone.core.model.offline.SourceKind
import es.topophone.core.model.offline.ZoneMeta
import es.topophone.core.model.wms.ProjectMapLayer
import es.topophone.core.model.wms.WmsLayerInfo
import es.topophone.core.model.wms.WmsRequestMode
import es.topophone.core.model.wms.WmsService
import es.topophone.data.wms.ProjectLayerView
import io.kotest.matchers.shouldBe
import org.junit.Test

/** Cuándo el mapa está cubierto por una zona sin conexión completa y cuándo se acerca más de lo guardado (S037/RF-20, RF-21). */
class OfflineCoverageTest {

    private val inside = MapCamera(40.4170, -3.7035, 15.0)
    private val box = GeoBox(40.4100, -3.7100, 40.4240, -3.6970)

    private fun zone(key: String, complete: Boolean = true, maxZoom: Double = 17.0, area: GeoBox = box) =
        ZoneCoverage(area, key, maxZoom, complete)

    @Test
    fun `S037 RF-20 dentro de una fuente completa del mapa base el mapa esta cubierto`() {
        OfflineCoverage.status(listOf(zone("pnoa")), setOf("pnoa"), inside).covered shouldBe true
    }

    @Test
    fun `S037 RF-20 fuera del area de la zona o con la zona incompleta el mapa no esta cubierto`() {
        val outside = MapCamera(41.5, -3.7, 15.0)

        OfflineCoverage.status(listOf(zone("pnoa")), setOf("pnoa"), outside).covered shouldBe false
        OfflineCoverage.status(listOf(zone("pnoa", complete = false)), setOf("pnoa"), inside).covered shouldBe false
    }

    @Test
    fun `S037 RF-20 una zona de otra fuente no cubre el mapa base que se ve`() {
        OfflineCoverage.status(listOf(zone("ign")), setOf("pnoa"), inside).covered shouldBe false
    }

    @Test
    fun `S037 RF-20 con una capa WMS visible hace falta tambien la zona completa de esa capa`() {
        val base = zone("pnoa")
        val layer = zone("wms:s1:capa")

        OfflineCoverage.status(listOf(base), setOf("pnoa", "wms:s1:capa"), inside).covered shouldBe false
        OfflineCoverage.status(listOf(base, layer), setOf("pnoa", "wms:s1:capa"), inside).covered shouldBe true
    }

    @Test
    fun `S037 RF-20 si el mapa no dibuja ninguna fuente descargable no hay nada cubierto`() {
        OfflineCoverage.status(listOf(zone("pnoa")), emptySet(), inside).covered shouldBe false
    }

    @Test
    fun `S037 RF-20 vale cualquiera de las zonas de una fuente que contenga el centro`() {
        val far = zone("pnoa", area = GeoBox(42.0, -4.0, 42.1, -3.9))

        OfflineCoverage.status(listOf(far, zone("pnoa")), setOf("pnoa"), inside).covered shouldBe true
    }

    @Test
    fun `S037 RF-21 por encima del nivel guardado dentro de la zona se avisa del detalle maximo`() {
        val zones = listOf(zone("pnoa", maxZoom = 17.0))

        OfflineCoverage.status(zones, setOf("pnoa"), inside.copy(zoom = 17.0)).beyondDetail shouldBe false
        OfflineCoverage.status(zones, setOf("pnoa"), inside.copy(zoom = 17.01)).beyondDetail shouldBe true
        OfflineCoverage.status(zones, setOf("pnoa"), inside.copy(zoom = 20.0)).beyondDetail shouldBe true
    }

    @Test
    fun `S037 RF-21 sin zona que cubra no se avisa del detalle maximo`() {
        OfflineCoverage.status(emptyList(), setOf("pnoa"), inside.copy(zoom = 21.0)).beyondDetail shouldBe false
        OfflineCoverage.status(listOf(zone("pnoa", complete = false)), setOf("pnoa"), inside.copy(zoom = 21.0)).beyondDetail shouldBe false
    }

    @Test
    fun `S037 RF-21 con varias fuentes cuenta el nivel de la que guarda menos detalle y de varias zonas el de la que mas`() {
        val zones = listOf(zone("pnoa", maxZoom = 18.0), zone("wms:s1:capa", maxZoom = 16.0), zone("wms:s1:capa", maxZoom = 17.0))
        val drawn = setOf("pnoa", "wms:s1:capa")

        OfflineCoverage.status(zones, drawn, inside.copy(zoom = 17.0)).beyondDetail shouldBe false
        OfflineCoverage.status(zones, drawn, inside.copy(zoom = 17.5)).beyondDetail shouldBe true
    }

    private fun region(source: DownloadableSource, maxZoom: Double, downloadedAt: Long? = 1L): StoredRegion {
        val meta = ZoneMeta("z", "p", "n", source, OfflineArea.Radius(40.4, -3.7, 500.0), DetailLevel.HIGH, 1, 1, downloadedAt, 0)
        return StoredRegion(RegionId(1), RegionDefinition("https://x", box, 7.0, maxZoom), meta, RegionProgress.EMPTY)
    }

    @Test
    fun `S037 RF-21 el nivel guardado es el del zoom de la zona sin pasar del ultimo nivel que sirve la fuente`() {
        // Una ráster de 256 px pide un nivel de tesela más que el zoom del mapa: la ortofoto sirve hasta el zoom 18; el IGN vectorial, hasta el 17.
        ZoneCoverage.of(region(DownloadableSource(SourceKind.PNOA, "PNOA"), 17.0))!!.storedMaxMapZoom shouldBe 17.0
        ZoneCoverage.of(region(DownloadableSource(SourceKind.PNOA, "PNOA"), 22.0))!!.storedMaxMapZoom shouldBe 18.0
        ZoneCoverage.of(region(DownloadableSource(SourceKind.IGN_VECTOR, "IGN"), 22.0))!!.storedMaxMapZoom shouldBe 17.0
        ZoneCoverage.of(region(DownloadableSource(SourceKind.OPENFREEMAP, "OSM"), 17.0))!!.storedMaxMapZoom shouldBe 14.0
        ZoneCoverage.of(region(DownloadableSource(SourceKind.WMS, "W", "s1", "capa"), 22.0))!!.storedMaxMapZoom shouldBe 21.0
    }

    @Test
    fun `S037 RF-20 una region esta completa si tiene fecha de descarga y una ajena no cuenta`() {
        val pnoa = DownloadableSource(SourceKind.PNOA, "PNOA")

        ZoneCoverage.of(region(pnoa, 17.0, downloadedAt = 5L))!!.complete shouldBe true
        ZoneCoverage.of(region(pnoa, 17.0, downloadedAt = null))!!.complete shouldBe false
        ZoneCoverage.of(region(pnoa, 17.0).copy(meta = null)) shouldBe null
    }

    private fun view(
        service: String,
        name: String,
        visible: Boolean = true,
        code: String? = "EPSG:3857",
        mode: WmsRequestMode = WmsRequestMode.TILES,
    ) = ProjectLayerView(
        ProjectMapLayer("$service-$name", "p", service, name, 0, visible, 100),
        WmsService(service, service, "https://$service.es", service, "1.3.0", "", null, mode, emptyList(), emptyList(), 0, 0),
        if (code == null) null else WmsLayerInfo(0, 0, name, name, "", true, true, code),
        false,
    )

    @Test
    fun `S037 RF-20 las fuentes que dibuja el mapa son el mapa base y las capas WMS por teselas visibles y disponibles`() {
        val layers = listOf(
            view("s1", "visible"),
            view("s1", "oculta", visible = false),
            view("s2", "no-disponible", code = null),
            view("s3", "una-imagen", mode = WmsRequestMode.IMAGE),
        )

        OfflineCoverage.drawnKeys(Basemap.PNOA_ORTHOPHOTO, layers) shouldBe setOf("pnoa", "wms:s1:visible")
        OfflineCoverage.drawnKeys(Basemap.IGN_VECTOR, emptyList()) shouldBe setOf("ign")
        OfflineCoverage.drawnKeys(Basemap.OSM_VECTOR, emptyList()) shouldBe setOf("ofm")
        OfflineCoverage.drawnKeys(Basemap.NONE, emptyList()) shouldBe emptySet()
    }
}
