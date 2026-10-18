package es.topophone.app.ui.map

import es.topophone.core.model.map.Basemap
import es.topophone.core.model.map.MapSettings
import io.kotest.matchers.shouldBe
import org.junit.Test

/** Atribución de las fuentes a la vista y opciones fijas del mapa (S009/RF-9, RF-11, RF-12). */
class MapAttributionTest {

    @Test
    fun `S009 RF-9 cada mapa base atribuye su fuente y el Catastro se suma si esta a la vista`() {
        MapAttribution.sources(MapSettings(Basemap.IGN_VECTOR)) shouldBe listOf(AttributionSource.IGN)
        MapAttribution.sources(MapSettings(Basemap.OSM_VECTOR)) shouldBe listOf(AttributionSource.OPENFREEMAP)
        MapAttribution.sources(MapSettings(Basemap.PNOA_ORTHOPHOTO)) shouldBe listOf(AttributionSource.IGN)
        MapAttribution.sources(MapSettings(Basemap.NONE)) shouldBe emptyList()
        MapAttribution.sources(MapSettings(Basemap.OSM_VECTOR, cadastre = true)) shouldBe
            listOf(AttributionSource.OPENFREEMAP, AttributionSource.CADASTRE)
        MapAttribution.sources(MapSettings(Basemap.NONE, cadastre = true)) shouldBe listOf(AttributionSource.CADASTRE)
    }

    @Test
    fun `S009 RF-11 el mapa no gira ni se inclina con los gestos`() {
        MapOptions.ROTATE_GESTURES shouldBe false
        MapOptions.TILT_GESTURES shouldBe false
    }

    @Test
    fun `S009 RF-12 el mapa deja acercar hasta el zoom 22 y la atribucion de MapLibre la sustituye la propia`() {
        MapOptions.MAX_ZOOM shouldBe 22.0
        MapOptions.MAPLIBRE_ATTRIBUTION shouldBe false
    }
}
