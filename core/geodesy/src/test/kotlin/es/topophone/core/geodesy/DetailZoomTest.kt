package es.topophone.core.geodesy

import es.topophone.core.model.offline.DetailLevel
import es.topophone.core.model.offline.DownloadableSource
import es.topophone.core.model.offline.SourceKind
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DetailZoomTest {

    @Test
    fun `S037 RF-8 Normal es el zoom de la escala 1 a 5000 en la latitud de la zona y Alto el de 1 a 1000`() {
        DetailZoom.maxMapZoom(DetailLevel.NORMAL, 40.4) shouldBe WebMercator.zoomForScale(5_000.0, 40.4)
        DetailZoom.maxMapZoom(DetailLevel.HIGH, 40.4) shouldBe WebMercator.zoomForScale(1_000.0, 40.4)
    }

    @Test
    fun `S037 RF-8 Maximo es el zoom maximo del mapa y cada nivel acerca mas que el anterior`() {
        DetailZoom.maxMapZoom(DetailLevel.MAX, 40.4) shouldBe WebMercator.MAX_ZOOM
        DetailZoom.maxMapZoom(DetailLevel.HIGH, 40.4) shouldBeGreaterThan DetailZoom.maxMapZoom(DetailLevel.NORMAL, 40.4)
        WebMercator.MAX_ZOOM shouldBeGreaterThan DetailZoom.maxMapZoom(DetailLevel.HIGH, 40.4)
    }

    @Test
    fun `S037 RF-8 la misma escala pide menos zoom en una latitud mas alta porque Mercator estira el mapa`() {
        DetailZoom.maxMapZoom(DetailLevel.NORMAL, 28.0) shouldBeGreaterThan DetailZoom.maxMapZoom(DetailLevel.NORMAL, 43.0)
    }

    @Test
    fun `S037 RF-8 una fuente con teselas vectoriales baja desde el zoom 0 y una solo ráster 10 niveles bajo el maximo`() {
        val ign = DownloadableSource(SourceKind.IGN_VECTOR, "IGN")
        val ofm = DownloadableSource(SourceKind.OPENFREEMAP, "OSM")
        val pnoa = DownloadableSource(SourceKind.PNOA, "PNOA")
        val wms = DownloadableSource(SourceKind.WMS, "WMS", serviceId = "s", layerName = "l")

        // El IGN lleva una fuente vectorial y otra ráster: la región tiene un solo zoom mínimo.
        DetailZoom.minMapZoom(ign, 17.0) shouldBe 0.0
        DetailZoom.minMapZoom(ofm, 17.0) shouldBe 0.0
        DetailZoom.minMapZoom(pnoa, 17.0) shouldBe 7.0
        DetailZoom.minMapZoom(wms, 16.2) shouldBe (6.2 plusOrMinus 1e-9)
    }

    @Test
    fun `S037 RF-8 el zoom minimo de una raster no baja de cero`() {
        DetailZoom.minMapZoom(DownloadableSource(SourceKind.PNOA, "PNOA"), 6.0) shouldBe 0.0
    }
}
