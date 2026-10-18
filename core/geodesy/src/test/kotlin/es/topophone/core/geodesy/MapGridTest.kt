package es.topophone.core.geodesy

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.math.cos
import org.junit.jupiter.api.Test

/** Cuadrícula del fondo neutro del mapa (S009/RF-14). */
class MapGridTest {

    // Unos 400 × 600 m en Madrid.
    private val south = 40.414
    private val west = -3.706
    private val north = 40.4194
    private val east = -3.7013

    @Test
    fun `S009 RF-14 las lineas estan separadas la longitud pedida en el centro de la zona visible`() {
        val lines = MapGrid.lines(south, west, north, east, spacingM = 100.0)

        val meridians = lines.filter { it.first().longitudeDeg == it.last().longitudeDeg }.map { it.first().longitudeDeg }.sorted()
        meridians.size shouldBeGreaterThan 2
        val centerLatitude = Math.toRadians((south + north) / 2)
        meridians.zipWithNext { a, b ->
            val metres = Math.toRadians(b - a) * primeVertical(centerLatitude) * cos(centerLatitude)
            // La separación se fija con la latitud redondeada a 0,1°, para que las líneas no tiemblen al mover el mapa.
            metres shouldBe (100.0 plusOrMinus 0.1)
        }
        val parallels = lines.filter { it.first().latitudeDeg == it.last().latitudeDeg }
        parallels.size shouldBeGreaterThan 2
    }

    @Test
    fun `S009 RF-14 las lineas cubren la zona visible y no se salen de ella`() {
        val lines = MapGrid.lines(south, west, north, east, spacingM = 50.0)

        lines.flatten().filter { it.latitudeDeg < south - 1e-12 || it.latitudeDeg > north + 1e-12 }.shouldBeEmpty()
        lines.flatten().filter { it.longitudeDeg < west - 1e-12 || it.longitudeDeg > east + 1e-12 }.shouldBeEmpty()
    }

    @Test
    fun `S009 RF-14 las lineas no se mueven al desplazar el mapa`() {
        val first = MapGrid.lines(south, west, north, east, spacingM = 100.0).map { it.first() }.toSet()
        val moved = MapGrid.lines(south + 0.0007, west + 0.0011, north + 0.0007, east + 0.0011, spacingM = 100.0).map { it.first() }

        moved.count { point ->
            first.any { it.longitudeDeg == point.longitudeDeg && it.latitudeDeg != point.latitudeDeg }
        } shouldBeGreaterThan
            0
    }

    @Test
    fun `S009 RF-14 con una zona muy grande se duplica la separacion hasta 40 lineas como mucho`() {
        val lines = MapGrid.lines(27.6, -18.2, 43.8, 4.4, spacingM = 1.0)

        lines.size shouldBeLessThanOrEqual MapGrid.MAX_LINES
        lines.size shouldBeGreaterThan 4
    }

    private fun primeVertical(phi: Double): Double {
        val e2 = Ellipsoid.GRS80.eccentricitySquared
        return Ellipsoid.GRS80.semiMajorAxisM / kotlin.math.sqrt(1 - e2 * kotlin.math.sin(phi) * kotlin.math.sin(phi))
    }
}
