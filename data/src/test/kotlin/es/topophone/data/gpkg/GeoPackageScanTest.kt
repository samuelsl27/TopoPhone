package es.topophone.data.gpkg

import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.geodesy.Utm
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.cad.GpkgIgnoredReason
import es.topophone.core.model.cad.GpkgLayerInfo
import es.topophone.core.model.cad.ImportArea
import es.topophone.core.model.project.CentralPoint
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.LayerBox
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import kotlin.math.hypot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Envolventes, extensión, distancia y recuento de la zona de un GeoPackage (S048/RF-12, RF-13, RF-15, RF-16). */
@RunWith(AndroidJUnit4::class)
class GeoPackageScanTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val e0 = 440_000.0
    private val n0 = 4_474_000.0
    private val center = Utm.inverse(e0, n0, 30).let { CentralPoint(it.latitudeDeg, it.longitudeDeg) }
    private val crs = ProjectCrs.ETRS89_UTM30
    private val expected = GpkgFixtures.expected("esperado.csv")
    private val utm30 = CadSourceCrs.Utm(GeodeticDatum.ETRS89, 30)

    private fun session(name: String): GpkgSession {
        val bytes = GpkgFixtures.bytes(name)
        val result = runBlocking { GeoPackageReader(folder.newFolder()).open(bytes.size.toLong()) { ByteArrayInputStream(bytes) } }
        return result.shouldBeInstanceOf<GpkgOpenResult.Opened>().session
    }

    private fun GpkgSession.layer(table: String): GpkgLayerInfo = layers.first { it.table == table }

    /** Los vértices en el SC de la capa de cada objeto: de cada (fid) la lista de (x, y). */
    private fun sourceVertices(file: String, table: String): Map<Int, List<Pair<Double, Double>>> = expected
        .filter { it["archivo"] == file && it["tabla"] == table && it["estado"] == "OK" }
        .groupBy { it.getValue("fid").toInt() }
        .mapValues { (_, rows) ->
            rows.flatMap { row ->
                row.getValue("vertices_fuente").split(';').map { v ->
                    v.split(' ').let {
                        it[0].toDouble() to
                            it[1].toDouble()
                    }
                }
            }
        }

    private fun projectVertices(file: String, table: String): Map<Int, List<Pair<Double, Double>>> = expected
        .filter { it["archivo"] == file && it["tabla"] == table && it["estado"] == "OK" }
        .groupBy { it.getValue("fid").toInt() }
        .mapValues { (_, rows) ->
            rows.flatMap { row ->
                row.getValue("vertices_proyecto").split(';').map { v ->
                    v.split(' ').let {
                        it[0].toDouble() to
                            it[1].toDouble()
                    }
                }
            }
        }

    private fun expectedInZone(file: String, table: String, radius: Double): Int = projectVertices(file, table).values.count { vertices ->
        vertices.any { (e, n) -> hypot(e - e0, n - n0) <= radius }
    }

    @Test
    fun `S048 RF-13 las envolventes de una capa de puntos son los puntos`() = runBlocking<Unit> {
        session("puntos-25830.gpkg").use { session ->
            val index = GeoPackageScan(session).index(session.layers.single())
            val points = sourceVertices("puntos-25830.gpkg", "vertices")

            index.size shouldBe 5
            index.withoutEnvelope shouldBe 0
            for (i in 0 until index.size) {
                val (x, y) = points.getValue(index.ids[i].toInt()).single()
                index.envelope(i) shouldBe LayerBox(x, x, y, y)
            }
        }
    }

    @Test
    fun `S048 RF-13 las envolventes de lineas en geograficas son sus recuadros`() = runBlocking<Unit> {
        session("lineas-4258.gpkg").use { session ->
            val index = GeoPackageScan(session).index(session.layers.single())
            val lines = sourceVertices("lineas-4258.gpkg", "ejes")

            index.size shouldBe 3
            for (i in 0 until index.size) {
                val v = lines.getValue(index.ids[i].toInt())
                index.envelope(i) shouldBe
                    LayerBox(v.minOf { it.first }, v.maxOf { it.first }, v.minOf { it.second }, v.maxOf { it.second })
            }
        }
    }

    @Test
    fun `S048 RF-13 sin R-tree y sin envolvente en el blob se calcula de la geometria`() = runBlocking<Unit> {
        session("sin-rtree-25830.gpkg").use { session ->
            val scan = GeoPackageScan(session)
            val noEnvelope = scan.index(session.layer("sin_envolvente"))
            val withHeader = scan.index(session.layer("sin_rtree"))
            val polygons = sourceVertices("sin-rtree-25830.gpkg", "sin_envolvente")

            noEnvelope.size shouldBe 2
            for (i in 0 until noEnvelope.size) {
                val v = polygons.getValue(noEnvelope.ids[i].toInt())
                noEnvelope.envelope(i) shouldBe
                    LayerBox(v.minOf { it.first }, v.maxOf { it.first }, v.minOf { it.second }, v.maxOf { it.second })
            }
            withHeader.size shouldBe 3
        }
    }

    @Test
    fun `S048 RF-18 los vacios y los nulos no tienen envolvente y las curvas si`() = runBlocking<Unit> {
        session("curvas-y-vacios-25830.gpkg").use { session ->
            val index = GeoPackageScan(session).index(session.layers.single())

            index.ids.toList() shouldBe listOf(1L, 5L, 6L, 7L, 8L)
            index.withoutEnvelope shouldBe 3
        }
    }

    @Test
    fun `S048 RF-13 los candidatos son los objetos cuya envolvente corta el recuadro`() = runBlocking<Unit> {
        session("puntos-25830.gpkg").use { session ->
            val index = GeoPackageScan(session).index(session.layers.single())
            val points = sourceVertices("puntos-25830.gpkg", "vertices").mapValues { it.value.single() }
            val box = LayerBox(e0 + 120.0, e0 + 260.0, n0 + 100.0, n0 + 170.0)

            index.idsIn(box).map { it.toInt() }.toSet() shouldBe
                points.filter { (_, p) -> p.first in 440_120.0..440_260.0 && p.second in 4_474_100.0..4_474_170.0 }.keys
            index.idsIn(null).size shouldBe 5
            index.idsIn(LayerBox(0.0, 1.0, 0.0, 1.0)).size shouldBe 0
            val bounds = index.bounds.shouldNotBeNull()
            bounds.minX shouldBe points.values.minOf { it.first }
            bounds.maxX shouldBe points.values.maxOf { it.first }
            bounds.minY shouldBe points.values.minOf { it.second }
            bounds.maxY shouldBe points.values.maxOf { it.second }
        }
    }

    @Test
    fun `S048 RF-15 el recuento de la zona es el de pyproj para cada radio`() = runBlocking<Unit> {
        val cases = listOf(
            Triple("puntos-25830.gpkg", "vertices", utm30),
            Triple("lineas-4258.gpkg", "ejes", CadSourceCrs.Geographic(GeodeticDatum.ETRS89)),
            Triple("poligonos-25830.gpkg", "parcelas", utm30),
            Triple("poligonos-25830.gpkg", "manzanas", utm30),
            Triple("multiples-25830.gpkg", "mixta", utm30),
            Triple("wgs84-4326.gpkg", "puntos", CadSourceCrs.Geographic(GeodeticDatum.ETRS89)),
        )

        for ((file, table, source) in cases) {
            session(file).use { session ->
                val scan = GeoPackageScan(session)
                val index = scan.index(session.layer(table))
                for (radius in listOf(100.0, 150.0, 250.0, 400.0, 600.0, 1_000.0)) {
                    val count = scan.count(index, source, crs, ImportArea.Circle(center, radius))

                    count.inZone shouldBe expectedInZone(file, table, radius)
                    count.exceeded shouldBe false
                }
            }
        }
    }

    @Test
    fun `S048 RF-15 un objeto entra entero si solo un vertice esta dentro`() = runBlocking<Unit> {
        session("poligonos-25830.gpkg").use { session ->
            val scan = GeoPackageScan(session)
            val index = scan.index(session.layer("parcelas"))

            // La parcela 2 (150..350, 0..200 desde la esquina) tiene su vértice más cercano a unos 150 m del centro del proyecto.
            scan.count(index, utm30, crs, ImportArea.Circle(center, 100.0)).inZone shouldBe 1
            scan.count(index, utm30, crs, ImportArea.Circle(center, 160.0)).inZone shouldBe 2
        }
    }

    @Test
    fun `S048 RF-15 todo el contenido cuenta los objetos con envolvente sin leer geometrias`() = runBlocking<Unit> {
        session("puntos-25830.gpkg").use { session ->
            val scan = GeoPackageScan(session)
            val index = scan.index(session.layers.single())

            scan.count(index, utm30, crs, ImportArea.All) shouldBe GpkgZoneCount(5, emptyMap(), exceeded = false)
        }
    }

    @Test
    fun `S048 RF-18 lo que se ignoraria se cuenta aparte`() = runBlocking<Unit> {
        session("curvas-y-vacios-25830.gpkg").use { session ->
            val scan = GeoPackageScan(session)
            val index = scan.index(session.layers.single())

            val count = scan.count(index, utm30, crs, ImportArea.Circle(center, 5_000.0))

            count.inZone shouldBe 2
            count.ignored shouldBe mapOf(GpkgIgnoredReason.CURVE_GEOMETRY to 3)
            count.ignoredTotal shouldBe 3
        }
    }

    @Test
    fun `S048 RF-11 una capa de Canarias no tiene objetos en una zona de Madrid`() = runBlocking<Unit> {
        session("sistemas-mixtos.gpkg").use { session ->
            val scan = GeoPackageScan(session)
            val index = scan.index(session.layer("canarias_utm28"))

            val count = scan.count(index, CadSourceCrs.Utm(GeodeticDatum.REGCAN95, 28), crs, ImportArea.Circle(center, 100_000.0))

            count.inZone shouldBe 0
            index.size shouldBe 3
        }
    }

    @Test
    fun `S048 RF-16 al pasar del limite se para y lo dice`() = runBlocking<Unit> {
        session("puntos-25830.gpkg").use { session ->
            val scan = GeoPackageScan(session, maxObjects = 2)
            val index = scan.index(session.layers.single())

            val circle = scan.count(index, utm30, crs, ImportArea.Circle(center, 5_000.0))
            val all = scan.count(index, utm30, crs, ImportArea.All)

            circle.exceeded shouldBe true
            circle.inZone shouldBe 3
            all.exceeded shouldBe true
            scan.count(index, utm30, crs, ImportArea.Circle(center, 120.0)).exceeded shouldBe false
        }
        ImportArea.MAX_OBJECTS shouldBe 500_000
    }

    @Test
    fun `S048 RF-12 la extension de la capa en el proyecto es el recuadro de sus puntos`() = runBlocking<Unit> {
        session("puntos-25830.gpkg").use { session ->
            val scan = GeoPackageScan(session)
            val index = scan.index(session.layers.single())
            val points = projectVertices("puntos-25830.gpkg", "vertices").values.flatten()

            val extent = scan.extent(index, utm30, crs).shouldNotBeNull()

            extent.minEasting shouldBe (points.minOf { it.first } plusOrMinus 1e-3)
            extent.maxEasting shouldBe (points.maxOf { it.first } plusOrMinus 1e-3)
            extent.minNorthing shouldBe (points.minOf { it.second } plusOrMinus 1e-3)
            extent.maxNorthing shouldBe (points.maxOf { it.second } plusOrMinus 1e-3)
        }
    }

    @Test
    fun `S048 RF-12 la distancia al centro es la de su extension en metros`() = runBlocking<Unit> {
        session("puntos-25830.gpkg").use { session ->
            val scan = GeoPackageScan(session)
            val extent = scan.extent(scan.index(session.layers.single()), utm30, crs).shouldNotBeNull()

            val distance = scan.distanceToCenterM(extent, crs, center).shouldNotBeNull()

            distance shouldBe (hypot(440_200.0 - e0, 4_474_140.0 - n0) plusOrMinus 1e-3)
        }
    }

    @Test
    fun `S048 RF-12 un centro a mas de 100 km da una distancia que avisa`() = runBlocking<Unit> {
        session("sistemas-mixtos.gpkg").use { session ->
            val scan = GeoPackageScan(session)
            val index = scan.index(session.layer("utm29"))
            val extent = scan.extent(index, CadSourceCrs.Utm(GeodeticDatum.ETRS89, 29), crs).shouldNotBeNull()
            val seville = CentralPoint(37.3891, -5.9845)

            // La capa está en Madrid aunque sus cifras sean del huso 29: cerca del centro de Madrid y a unos 390 km de Sevilla.
            (scan.distanceToCenterM(extent, crs, center).shouldNotBeNull() < 1_000.0) shouldBe true
            (scan.distanceToCenterM(extent, crs, seville).shouldNotBeNull() > 100_000.0) shouldBe true
        }
    }

    @Test
    fun `S048 RF-12 un proyecto en geograficas mide la distancia en el gran circulo`() = runBlocking<Unit> {
        session("lineas-4258.gpkg").use { session ->
            val scan = GeoPackageScan(session)
            val index = scan.index(session.layers.single())
            val source = CadSourceCrs.Geographic(GeodeticDatum.ETRS89)

            val extent = scan.extent(index, source, ProjectCrs.ETRS89_GEOGRAPHIC).shouldNotBeNull()

            val distance = scan.distanceToCenterM(extent, ProjectCrs.ETRS89_GEOGRAPHIC, center).shouldNotBeNull()
            (distance in 100.0..300.0) shouldBe true
        }
    }

    @Test
    fun `S048 RF-29 cancelar el indice o el recuento lo detiene sin dejar el fichero abierto`() = runBlocking<Unit> {
        val session = session("puntos-25830.gpkg")
        val scan = GeoPackageScan(session)
        val job = async { scan.index(session.layers.single()) }
        job.cancel()

        runCatching { job.await() }.exceptionOrNull().let { (it is CancellationException) shouldBe true }
        session.close()
        folder.root.walkTopDown().filter { it.isFile }.toList() shouldBe emptyList()
    }
}
