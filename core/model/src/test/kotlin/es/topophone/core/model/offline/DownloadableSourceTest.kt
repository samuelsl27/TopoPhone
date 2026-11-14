package es.topophone.core.model.offline

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DownloadableSourceTest {

    private val ign = DownloadableSource(SourceKind.IGN_VECTOR, "Mapa IGN")
    private val pnoa = DownloadableSource(SourceKind.PNOA, "Ortofoto PNOA")
    private val ofm = DownloadableSource(SourceKind.OPENFREEMAP, "Mapa OpenStreetMap")
    private val wms = DownloadableSource(SourceKind.WMS, "Ortoimagen", serviceId = "s1", layerName = "OI.OrthoimageCoverage")

    @Test
    fun `S037 RF-8 cada fuente tiene una clave estable`() {
        ign.key shouldBe "ign"
        pnoa.key shouldBe "pnoa"
        ofm.key shouldBe "ofm"
        wms.key shouldBe "wms:s1:OI.OrthoimageCoverage"
    }

    @Test
    fun `S037 RF-11 el IGN vectorial descarga tambien su relieve y el resto una sola fuente de teselas`() {
        ign.tileSources shouldBe listOf(
            TileSourceSpec(TileKind.VECTOR, 512, 0, 17),
            TileSourceSpec(TileKind.RASTER, 512, 5, 15),
        )
        pnoa.tileSources shouldBe listOf(TileSourceSpec(TileKind.RASTER, 256, 0, 19))
        ofm.tileSources shouldBe listOf(TileSourceSpec(TileKind.VECTOR, 512, 0, 14))
        wms.tileSources shouldBe listOf(TileSourceSpec(TileKind.RASTER, 256, 0, 22))
    }

    @Test
    fun `S037 RF-9 solo OpenFreeMap tiene el tope de descarga`() {
        listOf(ign, pnoa, wms).forEach { it.hasDownloadCap shouldBe false }
        ofm.hasDownloadCap shouldBe true
    }

    @Test
    fun `S037 RF-10 una capa WMS lleva su servicio y su nombre y las demas no`() {
        shouldThrow<IllegalArgumentException> { DownloadableSource(SourceKind.WMS, "x") }
        shouldThrow<IllegalArgumentException> { DownloadableSource(SourceKind.WMS, "x", serviceId = "s1") }
        shouldThrow<IllegalArgumentException> { DownloadableSource(SourceKind.WMS, "x", serviceId = "s1", layerName = " ") }
        shouldThrow<IllegalArgumentException> { DownloadableSource(SourceKind.PNOA, "x", serviceId = "s1") }
    }

    @Test
    fun `S037 RF-8 las claves de dos capas del mismo servicio no se confunden`() {
        val other = wms.copy(layerName = "OI.MosaicElement")
        (wms.key == other.key) shouldBe false
    }
}
