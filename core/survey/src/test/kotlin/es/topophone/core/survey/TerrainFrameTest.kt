package es.topophone.core.survey

import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.SpainCrs
import es.topophone.core.model.project.TerrainPlane
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.hypot
import kotlin.system.measureTimeMillis
import org.junit.jupiter.api.Test

/** El paso entre cuadrícula y terreno: una homotecia respecto al origen (S058/RF-6, RF-7, RF-8). */
class TerrainFrameTest {
    private val crs = SpainCrs.ETRS89_UTM30
    private val plane = TerrainPlane(40.4, -3.7, 0.9995415149066068)
    private val frame = TerrainFrame.of(plane, crs).shouldNotBeNull()

    @Test
    fun `S058 RF-7 el origen conserva sus coordenadas de cuadricula`() {
        val origin = frame.toTerrain(frame.originEasting, frame.originNorthing)

        origin.easting shouldBe frame.originEasting
        origin.northing shouldBe frame.originNorthing
    }

    @Test
    fun `S058 RF-7 el origen del marco es el del suelo en el SC del proyecto`() {
        // pyproj: EPSG:4258 a EPSG:25830 para (40.4, -3.7)
        frame.originEasting shouldBe (440_598.0788513809 plusOrMinus 1e-6)
        frame.originNorthing shouldBe (4_472_390.031014141 plusOrMinus 1e-6)
    }

    @Test
    fun `S058 RF-4 con el factor 1 el terreno es la cuadricula`() {
        val identity = TerrainFrame.of(plane.copy(factor = 1.0), crs).shouldNotBeNull()

        val p = identity.toTerrain(441_098.5, 4_473_190.25)

        p.easting shouldBe 441_098.5
        p.northing shouldBe 4_473_190.25
    }

    @Test
    fun `S058 RF-7 la distancia al origen se multiplica por el factor y el acimut no cambia`() {
        val e = frame.originEasting + 1200.0
        val n = frame.originNorthing - 500.0

        val t = frame.toTerrain(e, n)

        hypot(t.easting - frame.originEasting, t.northing - frame.originNorthing) shouldBe
            (frame.factor * hypot(1200.0, 500.0) plusOrMinus 1e-9)
        (t.easting - frame.originEasting) / (t.northing - frame.originNorthing) shouldBe (1200.0 / -500.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S058 RF-9 las distancias y los deltas se multiplican por el factor y las areas por el factor al cuadrado`() {
        frame.distance(1000.0) shouldBe (frame.factor * 1000.0 plusOrMinus 1e-12)
        frame.area(250_000.0) shouldBe (frame.factor * frame.factor * 250_000.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S058 RF-7 la ida y vuelta cierra a menos de 0,1 mm`() {
        val points = listOf(0.0 to 0.0, 1.0 to 1.0, 5_000.0 to -3_000.0, -40_000.0 to 25_000.0, 100_000.0 to 100_000.0)

        points.forEach { (de, dn) ->
            val e = frame.originEasting + de
            val n = frame.originNorthing + dn
            val back = frame.toGrid(frame.toTerrain(e, n).easting, frame.toTerrain(e, n).northing)
            hypot(back.easting - e, back.northing - n) shouldBe (0.0 plusOrMinus 1e-4)
        }
    }

    @Test
    fun `S058 RF-8 pasar 10000 puntos a terreno tarda menos de 100 ms`() {
        val points = List(10_000) { (440_000.0 + it % 100 * 7.0) to (4_472_000.0 + it / 100 * 7.0) }
        points.take(100).forEach { frame.toTerrain(it.first, it.second) }

        val millis = measureTimeMillis { points.forEach { frame.toTerrain(it.first, it.second) } }

        millis shouldBeLessThan 100L
    }

    @Test
    fun `S058 RF-2 con un SC geografico no hay marco`() {
        TerrainFrame.of(plane, SpainCrs.ETRS89_GEOGRAPHIC).shouldBeNull()
    }

    @Test
    fun `S058 RF-5 un origen fuera del ambito del SC no da marco`() {
        val lambert93 = CrsCatalog.bundled.build(2154, CorrectionsDatum.ETRS89, GeoPoint(48.0, 2.0)).let {
            LibraryCrs((it as CrsBuildResult.Ready).definition, CorrectionsDatum.ETRS89)
        }

        TerrainFrame.of(plane, lambert93).shouldBeNull() // Madrid queda al sur de Lambert-93
        TerrainFrame.of(TerrainPlane(48.85, 2.35, 1.0), lambert93).shouldNotBeNull()
    }

    @Test
    fun `S058 RF-6 el origen sigue en el mismo punto al cambiar de SC`() {
        val other = SpainCrs.ETRS89_UTM31 // huso forzado: Madrid se calcula en el 31

        val moved = TerrainFrame.of(plane, other).shouldNotBeNull()

        (moved.originEasting != frame.originEasting) shouldBe true
        moved.plane shouldBe plane
    }

    @Test
    fun `S058 RF-6 el plano vuelve igual de su texto sin redondear`() {
        val odd = TerrainPlane(40.416801234567891, -3.7037123456789, 1.0000412345678901)

        TerrainPlane.parseOrNull(odd.toText()) shouldBe odd
        TerrainPlane.parseOrNull("basura").shouldBeNull()
        TerrainPlane.parseOrNull(null).shouldBeNull()
        TerrainPlane.parseOrNull("1;2").shouldBeNull()
    }
}
