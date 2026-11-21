package es.topophone.core.geodesy.datum

import es.topophone.core.geodesy.Ed50
import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.GeodesyFixtures
import es.topophone.core.geodesy.GeodeticPoint
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.abs
import org.junit.jupiter.api.Test

/**
 * El cálculo de ED50 de S008 no cambia y la rejilla de S033 da lo mismo que él, también dentro de una cadena (S033/RF-16, RF-11).
 * `Ed50.kt` no se ha tocado en toda S033: lo que se comprueba aquí es que ambos caminos coinciden con PROJ y entre sí.
 */
class Ed50UnchangedTest {

    private val rows = GeodesyFixtures.rows("ed50_utm.csv").filter { it[4] == "30" }
    private val inside = rows.filter { it[2].isNotEmpty() }
    private val grid = GridShiftTransformation(GridShiftVector.GRID_NAME, ClasspathGridSource("/shift/"), TransformationInfo("SPED2ETV2"))

    private fun pointOf(result: TransformResult): GeodeticPoint = result.shouldBeInstanceOf<TransformResult.Ok>().point

    @Test
    fun `S033 RF-16 ED50 de S008 sigue dando las geograficas de PROJ a menos de 1 mm`() {
        inside.size shouldBeGreaterThan 50

        val failures = inside.mapNotNull { row ->
            val ed50 = Ed50.fromEtrs89(row[0].toDouble(), row[1].toDouble()) ?: return@mapNotNull "(${row[0]}, ${row[1]}) sin ED50"
            val dLat = abs(ed50.first - row[2].toDouble())
            val dLon = abs(ed50.second - row[3].toDouble())
            if (dLat > 1e-8 || dLon > 1e-8) "(${row[0]}, ${row[1]}): $dLat°, $dLon°" else null
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S033 RF-16 la rejilla de S033 da bit a bit lo mismo que Ed50 de ED50 a ETRS89`() {
        inside.forEach { row ->
            val ed50 = GeodeticPoint(row[2].toDouble(), row[3].toDouble(), 100.0)
            val expected = Ed50.toEtrs89(ed50.latitudeDeg, ed50.longitudeDeg).shouldNotBeNull()

            val actual = pointOf(grid.forward(ed50))

            actual.latitudeDeg shouldBe expected.first
            actual.longitudeDeg shouldBe expected.second
        }
    }

    @Test
    fun `S033 RF-16 la inversa de la rejilla coincide con la de Ed50 a menos de 0,1 mm`() {
        val failures = inside.mapNotNull { row ->
            val (lat, lon) = row[0].toDouble() to row[1].toDouble()
            val expected = Ed50.fromEtrs89(lat, lon).shouldNotBeNull()
            val actual = pointOf(grid.inverse(GeodeticPoint(lat, lon, 0.0)))
            val d = maxOf(abs(actual.latitudeDeg - expected.first), abs(actual.longitudeDeg - expected.second))
            // 1e-9 grados son unos 0,1 mm
            if (d > 1e-9) "($lat, $lon): $d°" else null
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S033 RF-16 fuera de la rejilla Ed50 y la rejilla de S033 rechazan los mismos puntos`() {
        val outside = GeodesyFixtures.rows("ed50_utm.csv").filter { it[2].isEmpty() && it[4] == "30" }
        outside.size shouldBeGreaterThan 2

        outside.forEach { row ->
            Ed50.fromEtrs89(row[0].toDouble(), row[1].toDouble()).shouldBeNull()
            grid.inverse(GeodeticPoint(row[0].toDouble(), row[1].toDouble(), 0.0)).shouldBeInstanceOf<TransformResult.OutsideGrid>()
        }
    }

    @Test
    fun `S033 RF-11 una cadena con la rejilla de ED50 entre dos Helmert nulos da lo mismo que Ed50`() {
        val intl = Ellipsoid.INTERNATIONAL_1924
        val grs80 = Ellipsoid.GRS80
        val zero = (HelmertParameters.translations3(Triple(0.0, 0.0, 0.0)) as HelmertParametersResult.Ok).parameters
        val chain = TransformationChain(
            listOf(
                HelmertTransformation(zero, intl, intl, TransformationInfo("nulo ED50")),
                grid,
                HelmertTransformation(zero, grs80, grs80, TransformationInfo("nulo ETRS89")),
            ),
            TransformationInfo("ED50 a ETRS89"),
        )

        val failures = inside.mapNotNull { row ->
            val ed50 = GeodeticPoint(row[2].toDouble(), row[3].toDouble(), 100.0)
            val expected = Ed50.toEtrs89(ed50.latitudeDeg, ed50.longitudeDeg).shouldNotBeNull()
            val actual = pointOf(chain.forward(ed50))
            val dLat = abs(actual.latitudeDeg - expected.first)
            val dLon = abs(actual.longitudeDeg - expected.second)
            val dH = abs(actual.ellipsoidalHeightM - 100.0)
            if (dLat > 1e-9 || dLon > 1e-9 || dH > 0.001) "(${row[2]}, ${row[3]}): $dLat°, $dLon°, ${dH * 1000} mm" else null
        }

        failures.shouldBeEmpty()
    }
}
