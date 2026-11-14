package es.topophone.core.model.offline

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class OfflineLimitsTest {

    @Test
    fun `S037 RF-3 el radio va de 100 m a 20 km`() {
        listOf(100.0, 500.0, 20_000.0).forEach { OfflineLimits.isValidRadius(it) shouldBe true }
        listOf(0.0, 99.9, 20_000.1, -100.0, Double.NaN).forEach { OfflineLimits.isValidRadius(it) shouldBe false }
    }

    @Test
    fun `S037 RF-4 el margen de todo el proyecto va de 200 m a 2 km`() {
        listOf(200.0, 1_000.0, 2_000.0).forEach { OfflineLimits.isValidMargin(it) shouldBe true }
        listOf(0.0, 199.9, 2_000.1, Double.NaN).forEach { OfflineLimits.isValidMargin(it) shouldBe false }
    }

    @Test
    fun `S037 RF-31 el limite por omision es 2 GB y se acota entre 200 MB y 10 GB`() {
        OfflineLimits.DEFAULT_STORAGE_LIMIT_BYTES shouldBe 2L * 1024 * 1024 * 1024
        OfflineLimits.clampStorageLimit(1L) shouldBe 200L * 1024 * 1024
        OfflineLimits.clampStorageLimit(5L * 1024 * 1024 * 1024) shouldBe 5L * 1024 * 1024 * 1024
        OfflineLimits.clampStorageLimit(50L * 1024 * 1024 * 1024) shouldBe 10L * 1024 * 1024 * 1024
    }

    @Test
    fun `S037 RF-12 la reserva de espacio libre es de 500 MB`() {
        OfflineLimits.FREE_SPACE_RESERVE_BYTES shouldBe 500L * 1024 * 1024
    }

    @Test
    fun `S037 RF-14 con datos moviles se confirma a partir de mas de 50 MB`() {
        OfflineLimits.MOBILE_CONFIRMATION_BYTES shouldBe 50L * 1024 * 1024
        OfflineLimits.needsMobileConfirmation(50L * 1024 * 1024) shouldBe false
        OfflineLimits.needsMobileConfirmation(50L * 1024 * 1024 + 1) shouldBe true
    }

    @Test
    fun `S037 RF-19 una fuente falla si mas del 2 por ciento de sus teselas no llega`() {
        OfflineLimits.exceedsFailureThreshold(failed = 20, total = 1_000) shouldBe false
        OfflineLimits.exceedsFailureThreshold(failed = 21, total = 1_000) shouldBe true
        OfflineLimits.exceedsFailureThreshold(failed = 1, total = 10) shouldBe true
        OfflineLimits.exceedsFailureThreshold(failed = 0, total = 1_000) shouldBe false
        OfflineLimits.exceedsFailureThreshold(failed = 5, total = 0) shouldBe false
    }

    @Test
    fun `S037 RF-9 OpenFreeMap admite hasta 3000 teselas y 50 MB por descarga`() {
        OfflineLimits.OPENFREEMAP_MAX_TILES shouldBe 3_000L
        OfflineLimits.OPENFREEMAP_MAX_BYTES shouldBe 50_000_000L
        OfflineLimits.exceedsOpenFreeMapLimit(tiles = 3_000, bytes = 50_000_000) shouldBe false
        OfflineLimits.exceedsOpenFreeMapLimit(tiles = 3_001, bytes = 1) shouldBe true
        OfflineLimits.exceedsOpenFreeMapLimit(tiles = 10, bytes = 50_000_001) shouldBe true
    }

    @Test
    fun `S037 RNF no mas de 4 peticiones a un mismo servidor y 30 s sin avance para rendirse`() {
        OfflineLimits.MAX_REQUESTS_PER_HOST shouldBe 4
        OfflineLimits.STALL_TIMEOUT_S shouldBe 30
    }
}
