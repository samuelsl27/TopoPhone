package es.topophone.core.geodesy.catalog

import es.topophone.core.geodesy.GeoTiffGrid
import es.topophone.core.geodesy.GeodesyFixtures
import es.topophone.core.geodesy.GridWindow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import org.junit.jupiter.api.Test

/** Geoides descargados, leídos por ventanas (S034/RF-14, RF-16; ≤ 1 mm frente a PROJ con la misma rejilla). */
class GeoidFromGridTest {

    private fun geoid(file: String) = GeoidFromGrid(file, "prueba") { GeodesyFixtures.bytes("rejillas/$file") }

    @Test
    fun `S034 RF-14 tres geoides del CDN dan la ondulacion de PROJ a menos de 1 mm y fuera de la rejilla no dan nada`() {
        val rows = GeodesyFixtures.rows("geoide_cdn.csv")
        rows.size shouldBeGreaterThan 100

        val geoids = HashMap<String, GeoidFromGrid>()
        val failures = rows.mapNotNull { (file, lat, lon, expected) ->
            val n = geoids.getOrPut(file) { geoid(file) }.undulationM(lat.toDouble(), lon.toDouble())
            when {
                expected.isEmpty() -> if (n != null) "$file ($lat, $lon): debía estar fuera y da $n" else null
                n == null -> "$file ($lat, $lon): sin valor"
                abs(n - expected.toDouble()) > 0.001 -> "$file ($lat, $lon): ${n - expected.toDouble()} m"
                else -> null
            }
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S034 RF-16 una rejilla que no esta instalada no da ondulacion`() {
        GeoidFromGrid("x", "x") { null }.undulationM(40.0, -3.0).shouldBeNull()
    }

    @Test
    fun `S034 RF-14 el fichero solo se vuelve a leer al salir de la ventana`() {
        var reads = 0
        val geoid = GeoidFromGrid("EGM08-REDNAP", "IGN", windowDeg = 2.0) {
            reads++
            GeodesyFixtures.bytes("../../core/geodesy/src/main/resources/geoid/es_ign_egm08-rednap.tif")
        }

        geoid.undulationM(40.0, -3.0).shouldNotBeNull()
        geoid.undulationM(40.3, -3.4).shouldNotBeNull()
        reads shouldBe 1
        geoid.undulationM(42.0, 1.0).shouldNotBeNull()
        reads shouldBe 2
        geoid.undulationM(50.0, -3.0).shouldBeNull() // fuera de la cobertura, sin leer de nuevo cada vez
        geoid.undulationM(50.0, -3.0).shouldBeNull()
        reads shouldBe 3
    }

    @Test
    fun `S034 RF-14 leer una ventana da los mismos valores que leer la rejilla entera dentro de ella`() {
        val bytes = GeodesyFixtures.bytes("../../core/geodesy/src/main/resources/geoid/es_ign_egm08-rednap.tif")
        val whole = GeoTiffGrid.read(bytes)
        val window = GeoTiffGrid.readWindow(bytes, GridWindow(39.0, -4.0, 41.0, -2.0)).shouldNotBeNull()

        window.width shouldBeGreaterThan 10
        (window.width < whole.width) shouldBe true
        listOf(39.0 to -4.0, 40.0 to -3.0, 40.123456 to -2.654321, 41.0 to -2.0, 39.5 to -3.999).forEach { (lat, lon) ->
            window.interpolate(lat, lon).shouldNotBeNull() shouldBe whole.interpolate(lat, lon).shouldNotBeNull().plusOrMinus(1e-9)
        }
        window.interpolate(45.0, -3.0).shouldBeNull() // fuera de la ventana: se rechaza
    }

    @Test
    fun `S034 RF-14 una ventana que no toca la rejilla no da nada`() {
        val bytes = GeodesyFixtures.bytes("../../core/geodesy/src/main/resources/geoid/es_ign_egm08-rednap.tif")

        GeoTiffGrid.readWindow(bytes, GridWindow(60.0, 100.0, 61.0, 101.0)).shouldBeNull()
    }
}
