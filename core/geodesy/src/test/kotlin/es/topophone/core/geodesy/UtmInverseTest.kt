package es.topophone.core.geodesy

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import kotlin.math.abs
import org.junit.jupiter.api.Test

/**
 * UTM inversa frente a PROJ, con los vectores de S002 y S008 leídos al revés, e ida y vuelta con [Utm.forward]
 * (S011/RF-17, RF-20; constitución nº 2).
 */
class UtmInverseTest {

    /** 1e-9° son 0,11 mm en latitud y menos en longitud. */
    private val toleranceDeg = 1e-9

    private fun check(rows: List<List<String>>, ellipsoid: Ellipsoid, columns: IntArray): List<String> = rows.mapNotNull { row ->
        val (latCol, lonCol, zoneCol, eastCol, northCol) = columns.toList()
        val point = Utm.inverse(row[eastCol].toDouble(), row[northCol].toDouble(), row[zoneCol].toInt(), ellipsoid)
        val dLat = abs(point.latitudeDeg - row[latCol].toDouble())
        val dLon = abs(point.longitudeDeg - row[lonCol].toDouble())
        if (dLat > toleranceDeg ||
            dLon > toleranceDeg
        ) {
            "huso ${row[zoneCol]} (${row[eastCol]}, ${row[northCol]}): Δφ=$dLat° Δλ=$dLon°"
        } else {
            null
        }
    }

    @Test
    fun `S011 RF-17 la inversa en ETRS89 UTM 29, 30 y 31 da las geograficas de PROJ`() {
        val rows = GeodesyFixtures.rows("utm_etrs89.csv")
        rows.size shouldBeGreaterThan 200

        check(rows, Ellipsoid.GRS80, intArrayOf(0, 1, 2, 3, 4)).shouldBeEmpty()
    }

    @Test
    fun `S011 RF-17 la inversa en REGCAN95 UTM 27 y 28 da las geograficas de PROJ`() {
        val rows = GeodesyFixtures.rows("utm_regcan95.csv")
        rows.size shouldBeGreaterThan 50

        check(rows, Ellipsoid.GRS80, intArrayOf(0, 1, 2, 3, 4)).shouldBeEmpty()
    }

    @Test
    fun `S011 RF-17 la inversa en ED50 UTM sobre el internacional de 1924 da las geograficas ED50 de PROJ`() {
        val rows = GeodesyFixtures.rows("ed50_utm.csv").filter { it[2].isNotEmpty() }
        rows.size shouldBeGreaterThan 150

        check(rows, Ellipsoid.INTERNATIONAL_1924, intArrayOf(2, 3, 4, 5, 6)).shouldBeEmpty()
    }

    @Test
    fun `S011 RF-20 ida y vuelta de Este y Norte queda a menos de 0,1 mm en los husos 27 a 31, tambien con huso forzado`() {
        val failures = mutableListOf<String>()
        for (zone in 27..31) {
            val north = if (zone <= 28) 3_000_000.0..3_300_000.0 else 3_850_000.0..4_950_000.0
            for (ellipsoid in listOf(Ellipsoid.GRS80, Ellipsoid.INTERNATIONAL_1924)) {
                // De 120 km a 880 km: hasta unos 4,5° del meridiano central, como con un huso forzado (S008/RF-11).
                for (easting in generateSequence(120_000.0) { it + 95_000.0 }.takeWhile { it <= 880_000.0 }) {
                    for (northing in generateSequence(north.start) { it + 75_000.0 }.takeWhile { it <= north.endInclusive }) {
                        val point = Utm.inverse(easting, northing, zone, ellipsoid)
                        val back = Utm.forward(point.latitudeDeg, point.longitudeDeg, zone, ellipsoid)
                        val dE = abs(back.easting - easting)
                        val dN = abs(back.northing - northing)
                        if (dE > 1e-4 || dN > 1e-4) failures += "huso $zone ($easting, $northing): ΔE=${dE * 1000} mm ΔN=${dN * 1000} mm"
                    }
                }
            }
        }

        failures.shouldBeEmpty()
    }
}
