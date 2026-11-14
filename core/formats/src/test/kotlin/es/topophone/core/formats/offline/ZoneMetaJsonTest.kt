package es.topophone.core.formats.offline

import es.topophone.core.model.offline.DetailLevel
import es.topophone.core.model.offline.DownloadableSource
import es.topophone.core.model.offline.OfflineArea
import es.topophone.core.model.offline.SourceKind
import es.topophone.core.model.offline.ZoneMeta
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ZoneMetaJsonTest {

    private val pnoa = DownloadableSource(SourceKind.PNOA, "Ortofoto PNOA")

    private fun meta(
        source: DownloadableSource = pnoa,
        area: OfflineArea = OfflineArea.Radius(40.416775, -3.703790, 500.0),
        downloadedAtMs: Long? = 1_763_100_000_000L,
    ) = ZoneMeta(
        zoneId = "z1",
        projectId = "p1",
        name = "Obra – 14/11/2026",
        source = source,
        area = area,
        detail = DetailLevel.HIGH,
        tiles = 4_650L,
        estimatedBytes = 123_456_789L,
        downloadedAtMs = downloadedAtMs,
        failed = 3L,
    )

    private fun roundTrip(meta: ZoneMeta) = ZoneMetaJson.decode(ZoneMetaJson.encode(meta))

    @Test
    fun `S037 RF-24 los metadatos de una region vuelven iguales de su JSON`() {
        roundTrip(meta()) shouldBe meta()
    }

    @Test
    fun `S037 RF-2 las tres formas de area vuelven iguales`() {
        listOf(
            OfflineArea.Radius(40.416775, -3.703790, 20_000.0),
            OfflineArea.Viewport(40.4160, -3.7050, 40.4180, -3.7020),
            OfflineArea.ProjectExtent(40.41, -3.71, 40.41, -3.71, 200.0),
        ).forEach { roundTrip(meta(area = it)) shouldBe meta(area = it) }
    }

    @Test
    fun `S037 RF-7 las coordenadas no se redondean al guardarlas`() {
        val area = OfflineArea.Radius(40.41677512345678, -3.70379098765432, 123.456789)

        (roundTrip(meta(area = area))?.area as OfflineArea.Radius).let {
            it.latitudeDeg shouldBe 40.41677512345678
            it.longitudeDeg shouldBe -3.70379098765432
            it.radiusM shouldBe 123.456789
        }
    }

    @Test
    fun `S037 RF-8 una capa WMS con espacios, barras verticales y comillas en su nombre vuelve igual`() {
        val wms = DownloadableSource(SourceKind.WMS, "Capa \"rara\"", serviceId = "s-1", layerName = "capa uno|x \"y\" ñ")

        roundTrip(meta(source = wms)) shouldBe meta(source = wms)
    }

    @Test
    fun `S037 RF-26 una zona sin descargar todavia no tiene fecha y la conserva si la tiene`() {
        roundTrip(meta(downloadedAtMs = null))?.downloadedAtMs shouldBe null
        roundTrip(meta(downloadedAtMs = 42L))?.downloadedAtMs shouldBe 42L
    }

    @Test
    fun `S037 RF-24 el JSON lleva su version y los nombres del plan`() {
        val json = String(ZoneMetaJson.encode(meta()), Charsets.UTF_8)

        json.contains("\"v\":1") shouldBe true
        listOf("zone", "project", "name", "source", "area", "detail", "tiles", "estimatedBytes", "downloadedAt", "failed")
            .forEach { json.contains("\"$it\"") shouldBe true }
    }

    @Test
    fun `S037 RF-24 una version que esta app no conoce no se lee`() {
        val json = String(ZoneMetaJson.encode(meta()), Charsets.UTF_8).replace("\"v\":1", "\"v\":2")

        ZoneMetaJson.decode(json.toByteArray(Charsets.UTF_8)) shouldBe null
    }

    @Test
    fun `S037 RF-24 un texto que no es JSON, un campo que falta o un valor invalido no se leen`() {
        ZoneMetaJson.decode("pnoa".toByteArray()) shouldBe null
        ZoneMetaJson.decode(ByteArray(0)) shouldBe null
        ZoneMetaJson.decode("{\"v\":1}".toByteArray()) shouldBe null
        val invalidRadius = String(ZoneMetaJson.encode(meta()), Charsets.UTF_8).replace("500.0", "5.0")
        ZoneMetaJson.decode(invalidRadius.toByteArray(Charsets.UTF_8)) shouldBe null
        val unknownDetail = String(ZoneMetaJson.encode(meta()), Charsets.UTF_8).replace("\"high\"", "\"enorme\"")
        ZoneMetaJson.decode(unknownDetail.toByteArray(Charsets.UTF_8)) shouldBe null
    }
}
