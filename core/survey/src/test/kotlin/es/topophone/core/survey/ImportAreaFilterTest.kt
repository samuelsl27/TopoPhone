package es.topophone.core.survey

import es.topophone.core.geodesy.Ed50
import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.Utm
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.cad.ImportArea
import es.topophone.core.model.project.CentralPoint
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import org.junit.jupiter.api.Test

/** La zona de importación: la prueba exacta de «algún vértice dentro» y el recuadro de prefiltro en el sistema de la capa (S048/RF-13, RF-15). */
class ImportAreaFilterTest {

    private val e0 = 440_000.0
    private val n0 = 4_474_000.0
    private val center = Utm.inverse(e0, n0, 30).let { CentralPoint(it.latitudeDeg, it.longitudeDeg) }

    private fun filter(radiusM: Double, crs: ProjectCrs = ProjectCrs.ETRS89_UTM30) =
        ImportAreaFilter.of(crs, ImportArea.Circle(center, radiusM)).shouldNotBeNull()

    @Test
    fun `S048 RF-15 un vertice a menos del radio esta dentro y a mas esta fuera`() {
        val zone = filter(1_000.0)

        zone.contains(e0 + 999.0, n0) shouldBe true
        zone.contains(e0 + 1_001.0, n0) shouldBe false
        zone.contains(e0, n0 - 999.9) shouldBe true
        zone.contains(e0 + 707.0, n0 + 707.0) shouldBe true
        zone.contains(e0 + 708.0, n0 + 708.0) shouldBe false
        zone.radiusM shouldBe 1_000.0
    }

    @Test
    fun `S048 RF-15 el vertice justo en el radio cuenta como dentro`() {
        val zone = filter(500.0)

        zone.contains(e0 + 500.0, n0) shouldBe true
        zone.contains(e0, n0) shouldBe true
    }

    @Test
    fun `S048 RF-15 basta un vertice dentro aunque el resto este fuera`() {
        val zone = filter(100.0)

        zone.anyInside(doubleArrayOf(e0 - 5_000, e0 + 50, e0 + 5_000), doubleArrayOf(n0, n0 + 50, n0)) shouldBe true
        zone.anyInside(doubleArrayOf(e0 - 5_000, e0 + 5_000), doubleArrayOf(n0, n0)) shouldBe false
        zone.anyInside(doubleArrayOf(), doubleArrayOf()) shouldBe false
    }

    @Test
    fun `S048 RF-15 un segmento que cruza la zona sin vertices dentro no cuenta`() {
        val zone = filter(100.0)

        // De (-1000, 0) a (+1000, 0) pasa por el centro, pero ningún vértice está a menos de 100 m.
        zone.anyInside(doubleArrayOf(e0 - 1_000, e0 + 1_000), doubleArrayOf(n0, n0)) shouldBe false
    }

    @Test
    fun `S048 RF-13 el radio de 100 m y el de 100 km funcionan`() {
        filter(100.0).contains(e0 + 99.0, n0) shouldBe true
        filter(100_000.0).contains(e0 + 99_000.0, n0) shouldBe true
        filter(100_000.0).contains(e0 + 101_000.0, n0) shouldBe false
    }

    @Test
    fun `S048 RF-15 en un proyecto en geograficas la distancia es la del gran circulo`() {
        val zone = filter(5_000.0, ProjectCrs.ETRS89_GEOGRAPHIC)
        val dLat5km = 5_000.0 / 111_195.0

        zone.contains(center.longitudeDeg, center.latitudeDeg + dLat5km * 0.98) shouldBe true
        zone.contains(center.longitudeDeg, center.latitudeDeg + dLat5km * 1.02) shouldBe false
        // En longitud el grado mide cos(lat) veces menos.
        val dLon5km = dLat5km / cos(Math.toRadians(center.latitudeDeg))
        zone.contains(center.longitudeDeg + dLon5km * 0.98, center.latitudeDeg) shouldBe true
        zone.contains(center.longitudeDeg + dLon5km * 1.02, center.latitudeDeg) shouldBe false
    }

    @Test
    fun `S048 RF-15 en un proyecto ED50 el centro se pasa por la rejilla y los vertices vienen en ED50`() {
        val zone = filter(200.0, ProjectCrs.ED50_UTM30)
        val (lat, lon) = Ed50.fromEtrs89(center.latitudeDeg, center.longitudeDeg)!!
        val ed50 = Utm.forward(lat, lon, 30, Ellipsoid.INTERNATIONAL_1924)

        zone.contains(ed50.easting + 150.0, ed50.northing) shouldBe true
        zone.contains(ed50.easting + 250.0, ed50.northing) shouldBe false
        zone.contains(e0, n0) shouldBe false // las mismas cifras en ETRS89 caen a unos 235 m
    }

    @Test
    fun `S048 RF-14 si el centro no tiene coordenadas en el sistema del proyecto no hay filtro`() {
        val canarias = CentralPoint(28.1, -15.4)

        ImportAreaFilter.of(ProjectCrs.ETRS89_UTM30, ImportArea.Circle(canarias, 5_000.0)).shouldBeNull()
        ImportAreaFilter.of(ProjectCrs.REGCAN95_UTM28, ImportArea.Circle(canarias, 5_000.0)).shouldNotBeNull()
    }

    @Test
    fun `S048 RF-13 el recuadro en un UTM del mismo huso es el centro mas menos el radio con su margen`() {
        val box = filter(1_000.0).candidateBox(CadSourceCrs.Utm(GeodeticDatum.ETRS89, 30)).shouldNotBeNull()

        val half = 1_000.0 * 1.005 + 5.0
        box.minX shouldBe (e0 - half plusOrMinus 1e-6)
        box.maxX shouldBe (e0 + half plusOrMinus 1e-6)
        box.minY shouldBe (n0 - half plusOrMinus 1e-6)
        box.maxY shouldBe (n0 + half plusOrMinus 1e-6)
    }

    @Test
    fun `S048 RF-13 el recuadro en geograficas esta en grados alrededor del centro`() {
        val box = filter(1_000.0).candidateBox(CadSourceCrs.Geographic(GeodeticDatum.ETRS89)).shouldNotBeNull()

        ((box.minX + box.maxX) / 2) shouldBe (center.longitudeDeg plusOrMinus 1e-12)
        ((box.minY + box.maxY) / 2) shouldBe (center.latitudeDeg plusOrMinus 1e-12)
        (box.maxY - box.minY) shouldBe (2 * 1_010.0 / 111_320.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S048 RF-13 el recuadro nunca deja fuera un vertice que la prueba exacta acepta`() {
        val random = Random(48)
        val radius = 20_000.0
        val zone = filter(radius)
        val sources = listOf(
            CadSourceCrs.Utm(GeodeticDatum.ETRS89, 29),
            CadSourceCrs.Utm(GeodeticDatum.ETRS89, 31),
            CadSourceCrs.Utm(GeodeticDatum.ED50, 30),
            CadSourceCrs.Geographic(GeodeticDatum.ETRS89),
        )

        for (source in sources) {
            val box = zone.candidateBox(source).shouldNotBeNull()
            repeat(500) {
                val angle = random.nextDouble() * 2 * Math.PI
                val distance = radius * kotlin.math.sqrt(random.nextDouble())
                val east = e0 + distance * cos(angle)
                val north = n0 + distance * sin(angle)
                zone.contains(east, north) shouldBe true

                val (x, y) = inLayer(east, north, source)

                box.intersects(x, x, y, y) shouldBe true
            }
        }
    }

    @Test
    fun `S048 RF-13 el recuadro no es mucho mayor que la zona`() {
        val box = filter(5_000.0).candidateBox(CadSourceCrs.Utm(GeodeticDatum.ETRS89, 29)).shouldNotBeNull()

        (box.maxX - box.minX) shouldBe (2 * (5_000.0 * 1.005 + 5.0) plusOrMinus 1e-6)
        (box.maxX - box.minX < 2 * 5_000.0 * 1.01 + 20) shouldBe true
    }

    @Test
    fun `S048 RF-13 dos recuadros se tocan si se solapan y no si estan separados`() {
        val box = LayerBox(0.0, 10.0, 0.0, 10.0)

        box.intersects(5.0, 15.0, 5.0, 15.0) shouldBe true
        box.intersects(10.0, 20.0, 10.0, 20.0) shouldBe true
        box.intersects(11.0, 20.0, 0.0, 10.0) shouldBe false
        box.intersects(-5.0, -1.0, 0.0, 10.0) shouldBe false
        box.intersects(2.0, 3.0, 11.0, 12.0) shouldBe false
        box.intersects(-100.0, 100.0, -100.0, 100.0) shouldBe true
    }

    @Test
    fun `S048 RF-13 un recuadro para el sistema del proyecto no existe porque la capa ya viene en el`() {
        filter(1_000.0).candidateBox(CadSourceCrs.Project).shouldBeNull()
    }

    /** Las coordenadas en la capa del punto (Este, Norte) del proyecto UTM 30 de ETRS89. */
    private fun inLayer(east: Double, north: Double, source: CadSourceCrs): Pair<Double, Double> {
        val ground = Utm.inverse(east, north, 30)
        return when (source) {
            is CadSourceCrs.Geographic -> ground.longitudeDeg to ground.latitudeDeg
            is CadSourceCrs.Utm -> {
                val (lat, lon) = if (source.datum == GeodeticDatum.ED50) {
                    Ed50.fromEtrs89(ground.latitudeDeg, ground.longitudeDeg)!!
                } else {
                    ground.latitudeDeg to ground.longitudeDeg
                }
                val ellipsoid = if (source.datum == GeodeticDatum.ED50) Ellipsoid.INTERNATIONAL_1924 else Ellipsoid.GRS80
                Utm.forward(lat, lon, source.zone, ellipsoid).let { it.easting to it.northing }
            }
            CadSourceCrs.Project -> error("sin recuadro")
        }
    }
}
