package es.topophone.app.ui.offline

import es.topophone.core.geodesy.AreaBounds
import es.topophone.core.geodesy.DetailZoom
import es.topophone.core.geodesy.GeoBox
import es.topophone.core.geodesy.TileCover
import es.topophone.core.model.offline.DownloadableSource
import es.topophone.core.model.offline.OfflineArea
import es.topophone.core.model.offline.OfflineLimits
import es.topophone.core.model.offline.SourceKind
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.Test

/** Teselas, tamaño estimado y comprobaciones antes de descargar (S037/RF-9 a RF-14). */
class DownloadEstimateTest {

    private val sol = GeoBox(40.4160, -3.7050, 40.4180, -3.7020)
    private val pnoa = DownloadableSource(SourceKind.PNOA, "Ortofoto PNOA")
    private val ign = DownloadableSource(SourceKind.IGN_VECTOR, "Mapa IGN")
    private val ofm = DownloadableSource(SourceKind.OPENFREEMAP, "Mapa OpenStreetMap")
    private fun wms(service: String, layer: String) = DownloadableSource(SourceKind.WMS, layer, serviceId = service, layerName = layer)

    private val roomy = StorageState(freeBytes = 50L * GB, usedBytes = 0, limitBytes = 2 * GB)

    private fun estimate(
        sources: List<DownloadableSource>,
        box: GeoBox = sol,
        maxZoom: Double = 17.0,
        storage: StorageState = roomy,
        network: NetworkKind = NetworkKind.UNMETERED,
        own: Map<String, String> = emptyMap(),
        formats: Map<String, String> = emptyMap(),
    ) = DownloadEstimate.of(box, maxZoom, sources, own, formats, storage, network)

    @Test
    fun `S037 RF-11 el numero de teselas de una fuente es el exacto de la cobertura`() {
        val result = estimate(listOf(pnoa))

        val spec = pnoa.tileSources.single()
        result.sources.single().tiles shouldBe TileCover.count(sol, DetailZoom.minMapZoom(pnoa, 17.0), 17.0, spec)
        result.totalTiles shouldBe result.sources.single().tiles
    }

    @Test
    fun `S037 RF-11 el IGN suma las teselas vectoriales y las de su relieve y las del total de las fuentes`() {
        val result = estimate(listOf(ign, pnoa))

        result.sources[0].tiles shouldBe TileCover.count(sol, 0.0, 17.0, ign.tileSources)
        result.totalTiles shouldBe result.sources.sumOf { it.tiles }
    }

    @Test
    fun `S037 RF-11 el tamano estimado es teselas por su tamano medio y los estilos vectoriales suman sus simbolos y glifos`() {
        val result = estimate(listOf(pnoa, ign, ofm))

        result.sources[0].estimatedBytes shouldBe result.sources[0].tiles * TileSizes.PNOA_BYTES
        result.sources[1].estimatedBytes shouldBe result.sources[1].tiles * TileSizes.IGN_BYTES + TileSizes.IGN_STYLE_BYTES
        result.sources[2].estimatedBytes shouldBe result.sources[2].tiles * TileSizes.OPENFREEMAP_BYTES + TileSizes.OPENFREEMAP_STYLE_BYTES
        result.totalBytes shouldBe result.sources.sumOf { it.estimatedBytes }
    }

    @Test
    fun `S037 RF-11 una capa WMS en PNG pesa mas por tesela que una en JPEG`() {
        val layer = wms("s1", "capa")

        val png = estimate(listOf(layer)).sources.single()
        val jpeg = estimate(listOf(layer), formats = mapOf(layer.key to "image/jpeg")).sources.single()

        png.tiles shouldBe jpeg.tiles
        png.estimatedBytes shouldBe png.tiles * TileSizes.WMS_PNG_BYTES
        jpeg.estimatedBytes shouldBe jpeg.tiles * TileSizes.WMS_JPEG_BYTES
    }

    @Test
    fun `S037 RF-9 OpenFreeMap con mas de 50 MB estimados no se descarga y dice cuantos`() {
        val big = AreaBounds.of(OfflineArea.Radius(40.4, -3.7, 20_000.0))

        val result = estimate(listOf(ofm), box = big, maxZoom = 22.0)

        val block = result.blocks.filterIsInstance<EstimateBlock.OpenFreeMapLimit>().single()
        block.bytes shouldBe result.sources.single().estimatedBytes
        result.canStart shouldBe false
    }

    @Test
    fun `S037 RF-9 una zona pequena de OpenFreeMap no se bloquea y las otras fuentes no tienen ese tope`() {
        estimate(listOf(ofm)).blocks.shouldBeEmpty()
        val big = AreaBounds.of(OfflineArea.Radius(40.4, -3.7, 20_000.0))
        estimate(listOf(ign, pnoa), box = big, maxZoom = 14.0).blocks.filterIsInstance<EstimateBlock.OpenFreeMapLimit>().shouldBeEmpty()
    }

    @Test
    fun `S037 RF-10 un servicio WMS propio pide confirmacion una vez aunque se bajen dos capas y un preset no`() {
        val result = estimate(
            listOf(wms("own", "a"), wms("own", "b"), wms("preset", "c")),
            own = mapOf("own" to "Mi servicio"),
        )

        result.confirmations shouldContainExactly listOf(EstimateConfirmation.OwnWms("Mi servicio"))
        result.canStart shouldBe true
    }

    @Test
    fun `S037 RF-11 sin ninguna fuente no hay teselas ni avisos`() {
        val result = estimate(emptyList())

        result.totalTiles shouldBe 0L
        result.blocks.shouldBeEmpty()
        result.confirmations.shouldBeEmpty()
    }

    @Test
    fun `S037 RF-12 si el espacio libre es menor que el estimado mas 500 MB no se empieza y dice cuanto falta`() {
        val total = estimate(listOf(pnoa)).totalBytes
        val reserve = OfflineLimits.FREE_SPACE_RESERVE_BYTES

        estimate(listOf(pnoa), storage = roomy.copy(freeBytes = total + reserve)).blocks.shouldBeEmpty()
        estimate(listOf(pnoa), storage = roomy.copy(freeBytes = total + reserve - 1)).blocks shouldContainExactly
            listOf(EstimateBlock.NoSpace(missingBytes = 1))
    }

    @Test
    fun `S037 RF-12 si lo ocupado mas lo estimado pasa del limite de almacenamiento no se empieza y dice cuanto falta`() {
        val total = estimate(listOf(pnoa)).totalBytes

        val atLimit = estimate(listOf(pnoa), storage = roomy.copy(usedBytes = 2 * GB - total))
        val over = estimate(listOf(pnoa), storage = roomy.copy(usedBytes = 2 * GB - total + 10))

        atLimit.blocks.shouldBeEmpty()
        over.blocks shouldContainExactly listOf(EstimateBlock.OverLimit(missingBytes = 10))
    }

    @Test
    fun `S037 RF-13 sin red con internet no se empieza`() {
        val result = estimate(listOf(pnoa), network = NetworkKind.NONE)

        result.blocks shouldContainExactly listOf(EstimateBlock.NoInternet)
        result.canStart shouldBe false
    }

    @Test
    fun `S037 RF-14 con datos moviles y mas de 50 MB se pide confirmacion y con menos no`() {
        val big = estimate(listOf(ign), box = AreaBounds.of(OfflineArea.Radius(40.4, -3.7, 5_000.0)), network = NetworkKind.MOBILE)
        val small = estimate(listOf(pnoa), network = NetworkKind.MOBILE)

        (big.totalBytes > OfflineLimits.MOBILE_CONFIRMATION_BYTES) shouldBe true
        big.confirmations shouldContainExactly listOf(EstimateConfirmation.MobileData)
        (small.totalBytes <= OfflineLimits.MOBILE_CONFIRMATION_BYTES) shouldBe true
        small.confirmations.shouldBeEmpty()
        estimate(listOf(ign), box = AreaBounds.of(OfflineArea.Radius(40.4, -3.7, 5_000.0))).confirmations.shouldBeEmpty()
    }

    @Test
    fun `S037 RF-12 los bloqueos se acumulan en un orden fijo`() {
        val total = estimate(listOf(pnoa)).totalBytes

        val result = estimate(
            listOf(pnoa),
            storage = StorageState(freeBytes = 0, usedBytes = 2 * GB, limitBytes = 2 * GB),
            network = NetworkKind.NONE,
        )

        result.blocks shouldContainExactly listOf(
            EstimateBlock.NoInternet,
            EstimateBlock.NoSpace(missingBytes = total + OfflineLimits.FREE_SPACE_RESERVE_BYTES),
            EstimateBlock.OverLimit(missingBytes = total),
        )
    }

    private companion object {
        const val GB = 1024L * 1024 * 1024
    }
}
