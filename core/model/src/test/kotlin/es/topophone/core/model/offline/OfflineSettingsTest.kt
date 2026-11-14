package es.topophone.core.model.offline

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class OfflineSettingsTest {

    @Test
    fun `S037 RF-31 de fabrica el limite es de 2 GB`() {
        OfflineSettings().storageLimitBytes shouldBe OfflineLimits.DEFAULT_STORAGE_LIMIT_BYTES
    }

    @Test
    fun `S037 RF-8 de fabrica el nivel es Normal y el area un radio de 500 m con un margen de 500 m`() {
        val settings = OfflineSettings()

        settings.detail shouldBe DetailLevel.NORMAL
        settings.areaKind shouldBe OfflineAreaKind.RADIUS
        settings.radiusM shouldBe 500.0
        settings.marginM shouldBe 500.0
    }

    @Test
    fun `S037 RF-31 y RF-3 y RF-4 los valores fuera de limites se acotan`() {
        val settings = OfflineSettings(storageLimitBytes = 1L, radiusM = 5.0, marginM = 99_999.0).sanitized()

        settings.storageLimitBytes shouldBe OfflineLimits.MIN_STORAGE_LIMIT_BYTES
        settings.radiusM shouldBe OfflineLimits.MIN_RADIUS_M
        settings.marginM shouldBe OfflineLimits.MAX_MARGIN_M
    }

    @Test
    fun `S037 RF-3 un radio que no es un numero cuenta como el de fabrica`() {
        OfflineSettings(radiusM = Double.NaN, marginM = Double.NaN).sanitized().let {
            it.radiusM shouldBe 500.0
            it.marginM shouldBe 500.0
        }
    }

    @Test
    fun `S037 RF-8 los valores validos no cambian al acotarlos`() {
        val settings = OfflineSettings(
            storageLimitBytes = 5L * 1024 * 1024 * 1024,
            detail = DetailLevel.MAX,
            areaKind = OfflineAreaKind.PROJECT,
            radiusM = 20_000.0,
            marginM = 200.0,
        )

        settings.sanitized() shouldBe settings
    }
}
