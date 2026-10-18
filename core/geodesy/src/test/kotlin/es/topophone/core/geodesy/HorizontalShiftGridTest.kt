package es.topophone.core.geodesy

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import org.junit.jupiter.api.Test

/** Rejilla de desplazamientos ED50 → ETRS89 del IGN (SPED2ETV2) en GeoTIFF con dos subrejillas y dos bandas (S008/RF-7). */
class HorizontalShiftGridTest {

    private val bytes = HorizontalShiftGridTest::class.java.getResourceAsStream("/shift/es_ign_SPED2ETV2.tif")!!.use { it.readBytes() }
    private val grids = GeoTiffGrid.readAll(bytes)
    private val shift = HorizontalShiftGrid(grids)

    @Test
    fun `S008 RF-7 el fichero tiene la subrejilla de Baleares primero y la de la Peninsula despues, con dos bandas`() {
        grids.map { it.metadata["grid_name"] } shouldBe listOf("BALEARES", "PENINSUL")
        grids.map { it.width to it.height } shouldBe listOf(93 to 68, 259 to 161)
        grids.all { it.bands == 2 } shouldBe true
        grids.first().metadata["positive_value:1"] shouldBe "east"
        grids.first().stepLatitudeDeg shouldBe (2.5 / 60 plusOrMinus 1e-12)
    }

    @Test
    fun `S008 RF-7 los desplazamientos coinciden con los de PROJ en cada punto ED50 de los vectores`() {
        // En el punto ED50 la rejilla da ETRS89 − ED50 (hgridshift); los vectores traen las dos coordenadas.
        val rows = GeodesyFixtures.rows("ed50_utm.csv").filter { it[2].isNotEmpty() && it[4] == "30" }
        rows.size shouldBeGreaterThan 50

        val failures = rows.mapNotNull { row ->
            val (lat, lon) = row[0].toDouble() to row[1].toDouble()
            val (latEd50, lonEd50) = row[2].toDouble() to row[3].toDouble()
            val offset = shift.offset(latEd50, lonEd50) ?: return@mapNotNull "($latEd50, $lonEd50) sin desplazamiento"
            val dLat = abs(offset.latitudeArcSec - (lat - latEd50) * 3600)
            val dLon = abs(offset.longitudeArcSec - (lon - lonEd50) * 3600)
            if (dLat > 1e-5 || dLon > 1e-5) "($latEd50, $lonEd50): Δφ=$dLat″ Δλ=$dLon″" else null
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S008 RF-7 en Baleares se usa su subrejilla y en la Peninsula la suya`() {
        shift.offset(39.5696, 2.6502).shouldNotBeNull().gridName shouldBe "BALEARES"
        shift.offset(40.4168, -3.7038).shouldNotBeNull().gridName shouldBe "PENINSUL"
    }

    @Test
    fun `S008 RF-7 en Madrid el desplazamiento de la longitud es hacia el oeste y el de la latitud hacia el sur`() {
        val offset = shift.offset(40.418, -3.7025).shouldNotBeNull()

        offset.longitudeArcSec shouldBeLessThan 0.0
        offset.latitudeArcSec shouldBeLessThan 0.0
    }

    @Test
    fun `S008 RF-8 fuera de las dos subrejillas no hay desplazamiento`() {
        shift.offset(28.1, -15.4).shouldBeNull() // Canarias
        shift.offset(45.5, -3.0).shouldBeNull()
        shift.offset(40.0, -12.0).shouldBeNull()
    }
}
