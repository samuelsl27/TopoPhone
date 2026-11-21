package es.topophone.core.geodesy.datum

import es.topophone.core.geodesy.Ecef
import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.GeoTiffGrid
import es.topophone.core.geodesy.GeodeticPoint
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** Ida y vuelta a menos de 0,1 mm en todas las familias, en una rejilla de puntos propia y no en los vectores (S033/RF-5, RF-10, RF-11). */
class DatumRoundTripTest {

    private fun pointOf(result: TransformResult): GeodeticPoint = result.shouldBeInstanceOf<TransformResult.Ok>().point

    private fun distanceM(a: GeodeticPoint, b: GeodeticPoint, ellipsoid: Ellipsoid): Double {
        val ea = Ecef.fromGeodetic(a.latitudeDeg, a.longitudeDeg, a.ellipsoidalHeightM, ellipsoid)
        val eb = Ecef.fromGeodetic(b.latitudeDeg, b.longitudeDeg, b.ellipsoidalHeightM, ellipsoid)
        return ea.distanceTo(eb)
    }

    /** Todo el mundo, de −80° a 80° de latitud y con alturas de −100 m a 9 km. */
    private val worldGrid: List<GeodeticPoint> =
        (-80..80 step 20).flatMap { lat ->
            (-180..160 step 40).flatMap { lon ->
                listOf(-100.0, 0.0, 650.0, 9000.0).map { h -> GeodeticPoint(lat.toDouble(), lon.toDouble(), h) }
            }
        }

    /** La Península y Baleares cada medio grado: parte queda fuera de la rejilla y se omite. */
    private val spainGrid: List<GeodeticPoint> =
        (72..88).flatMap { latHalf -> (-18..8).map { lonHalf -> GeodeticPoint(latHalf / 2.0, lonHalf / 2.0, 100.0) } }

    private fun roundTripFailures(
        name: String,
        transformation: DatumTransformation,
        points: List<GeodeticPoint>,
        ellipsoid: Ellipsoid,
        limitM: Double = 1e-4,
    ): Pair<Int, List<String>> {
        var tested = 0
        val failures = points.mapNotNull { p ->
            val forward = transformation.forward(p) as? TransformResult.Ok ?: return@mapNotNull null
            tested++
            val back = pointOf(transformation.inverse(forward.point))
            val d = distanceM(back, p, ellipsoid)
            if (d > limitM) "$name (${p.latitudeDeg}, ${p.longitudeDeg}, ${p.ellipsoidalHeightM}): ${d * 1000} mm" else null
        }
        return tested to failures
    }

    @Test
    fun `S033 RF-5 el Helmert de 3, 4, 6 y 7 parametros en PV y CF cierra a menos de 0,1 mm en todo el mundo`() {
        val transformations = HelmertVector.all().distinctBy { it.case }
        transformations.size shouldBeGreaterThan 12

        val failures = transformations.flatMap { v ->
            val (tested, failed) = roundTripFailures(v.case, v.transformation(), worldGrid, v.source)
            tested shouldBeGreaterThan 100
            failed
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S033 RF-5 Molodensky-Badekas cierra a menos de 0,1 mm en todo el mundo`() {
        val transformations = MolodenskyBadekasVector.all().distinctBy { it.case }
        transformations.size shouldBeGreaterThan 5

        val failures = transformations.flatMap { v ->
            val (tested, failed) = roundTripFailures(v.case, v.transformation(), worldGrid, v.source)
            tested shouldBeGreaterThan 100
            failed
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S033 RF-10 la rejilla cierra a menos de 0,1 mm en la Peninsula y Baleares`() {
        val grid = GridShiftTransformation(GridShiftVector.GRID_NAME, ClasspathGridSource("/shift/"), TransformationInfo("SPED2ETV2"))

        // Donde se solapan las subrejillas de la Península y de Baleares sus desplazamientos difieren varios metros: allí la transformación no
        // es inyectiva (dos puntos de ED50 dan el mismo de ETRS89) y la vuelta puede dar el otro. Es un rasgo del dato, que PROJ comparte.
        val subgrids = GeoTiffGrid.readAll(
            GridShiftTransformation::class.java.getResourceAsStream("/shift/es_ign_SPED2ETV2.tif")!!.use {
                it.readBytes()
            },
        )
        fun subgridCount(p: GeodeticPoint) = subgrids.count { it.interpolate(p.latitudeDeg, p.longitudeDeg, band = 0) != null }
        val (overlapping, clear) = spainGrid.partition { p ->
            subgridCount(p) > 1 || (grid.forward(p) as? TransformResult.Ok)?.let { subgridCount(it.point) > 1 } == true
        }

        val (tested, failures) = roundTripFailures("SPED2ETV2", grid, clear, Ellipsoid.INTERNATIONAL_1924)

        tested shouldBeGreaterThan 100
        failures.shouldBeEmpty()
        // En la zona de solape la garantía es la otra: lo que devuelve la inversa es siempre un punto que la directa lleva al de partida.
        overlapping.size shouldBeLessThan 60
        val inverseFailures = overlapping.mapNotNull { p ->
            val y = (grid.forward(p) as? TransformResult.Ok)?.point ?: return@mapNotNull null
            val x = pointOf(grid.inverse(y))
            val d = distanceM(pointOf(grid.forward(x)), y, Ellipsoid.INTERNATIONAL_1924)
            if (d > 1e-4) "(${p.latitudeDeg}, ${p.longitudeDeg}): ${d * 1000} mm" else null
        }
        inverseFailures.shouldBeEmpty()
    }

    @Test
    fun `S033 RF-11 las cadenas de tres y de cuatro pasos cierran a menos de 0,1 mm`() {
        val chains = ChainVector.all().groupBy { it.case }.values.map { it.first().chain() }
        chains.size shouldBeGreaterThan 1

        val failures = chains.flatMap { chain ->
            val (tested, failed) = roundTripFailures(chain.info.name, chain, spainGrid, Ellipsoid.GRS80)
            tested shouldBeGreaterThan 100
            failed
        }

        failures.shouldBeEmpty()
    }
}
