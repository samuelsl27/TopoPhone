package es.topophone.data.shp

import es.topophone.core.geodesy.Utm
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.cad.ImportArea
import es.topophone.core.model.project.CentralPoint
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.ProjectCrs
import es.topophone.data.gpkg.GeoPackageScan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** El recuento por zona de S048 sobre un Shapefile: envolventes de las cabeceras, extensión, distancia y objetos en la zona (S030/RF-10 a RF-14). */
class ShapefileScanTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val sessions = ArrayList<ShapefileSession>()
    private var cacheId = 0
    private val crs = ProjectCrs.ETRS89_UTM30
    private val utm30 = CadSourceCrs.Utm(GeodeticDatum.ETRS89, 30)
    private val center = Utm.inverse(440_100.0, 4_474_100.0, 30).let { CentralPoint(it.latitudeDeg, it.longitudeDeg) }

    @After
    fun close() = sessions.forEach { it.close() }

    private fun session(scenario: String, vararg layers: String): ShapefileSession {
        val files = File(ShpFixtures.folder, scenario).listFiles().orEmpty()
            .filter { f -> layers.any { f.nameWithoutExtension.equals(it, ignoreCase = true) } }
            .map { "$scenario/${it.name}" }
        val reader = ShapefileReader(folder.newFolder("c${cacheId++}"), freeSpace = { Long.MAX_VALUE })
        return runBlocking { reader.open(files.map { ShpFixtures.input(it) }) }
            .shouldBeInstanceOf<ShpOpenResult.Opened>().session.also { sessions += it }
    }

    @Test
    fun `S030 RF-10 la extension de una capa sale de las envolventes de sus cabeceras`() {
        val session = session("puntos-25830", "vertices")
        val scan = GeoPackageScan(session)
        val layer = session.layers.single()

        val index = runBlocking { scan.index(layer) }

        index.size shouldBe 5
        index.withoutEnvelope shouldBe 0
        val bounds = index.bounds!!
        listOf(bounds.minX - 440_100.0, bounds.maxX - 440_300.0, bounds.minY - 4_474_100.0, bounds.maxY - 4_474_180.0)
            .all { abs(it) < 1e-3 } shouldBe true
        val extent = scan.extent(index, utm30, crs)!!
        (abs(extent.minEasting - 440_100.0) < 1e-3 && abs(extent.maxNorthing - 4_474_180.0) < 1e-3) shouldBe true
        val distance = scan.distanceToCenterM(extent, crs, center)!!
        (distance > 100.0 && distance < 120.0) shouldBe true // del centro de la capa (440200, 4474140) al centro del proyecto
    }

    @Test
    fun `S030 RF-10 los objetos nulos y los borrados no tienen envolvente`() {
        val session = session("borrados-y-nulos", "borrados", "nulos", "multipatch")
        val scan = GeoPackageScan(session)

        val deleted = runBlocking { scan.index(session.layers.first { it.table == "borrados" }) }
        val nulls = runBlocking { scan.index(session.layers.first { it.table == "nulos" }) }
        val patches = runBlocking { scan.index(session.layers.first { it.table == "multipatch" }) }

        deleted.ids.toList() shouldBe listOf(1L, 3L, 5L)
        deleted.withoutEnvelope shouldBe 2
        nulls.ids.toList() shouldBe listOf(1L, 3L)
        nulls.withoutEnvelope shouldBe 2
        patches.size shouldBe 0
        patches.bounds shouldBe null
    }

    @Test
    fun `S030 RF-11 el recuento por radio lee solo las geometrias de los candidatos y da lo mismo que importar`() {
        val session = session("puntos-25830", "vertices")
        val scan = GeoPackageScan(session)
        val index = runBlocking { scan.index(session.layers.single()) }

        val all = runBlocking { scan.count(index, utm30, crs, ImportArea.All) }
        val near = runBlocking { scan.count(index, utm30, crs, ImportArea.Circle(center, 100.0)) }
        val none = runBlocking { scan.count(index, utm30, crs, ImportArea.Circle(CentralPoint(37.3891, -5.9845), 5_000.0)) }
        val wide = runBlocking { scan.count(index, utm30, crs, ImportArea.Circle(center, 1_000.0)) }

        all.inZone shouldBe 5
        near.inZone shouldBe 2
        none.inZone shouldBe 0
        wide.inZone shouldBe 5
        near.exceeded shouldBe false
    }

    @Test
    fun `S030 RF-14 pasar del limite de objetos corta el recuento`() {
        val session = session("puntos-25830", "vertices")
        val scan = GeoPackageScan(session, maxObjects = 3)
        val index = runBlocking { scan.index(session.layers.single()) }

        val counted = runBlocking { scan.count(index, utm30, crs, ImportArea.Circle(center, 1_000.0)) }

        counted.exceeded shouldBe true
        runBlocking { scan.count(index, utm30, crs, ImportArea.All) }.exceeded shouldBe true
    }

    @Test
    fun `S030 RF-11 un objeto fuera de la caja de la zona ni se mira y no entra en el recuento`() {
        val session = session("ambito", "fuera")
        val scan = GeoPackageScan(session)
        val geographic = CadSourceCrs.Geographic(GeodeticDatum.ETRS89)
        val index = runBlocking { scan.index(session.layers.single()) }

        val counted = runBlocking { scan.count(index, geographic, crs, ImportArea.Circle(center, 50_000.0)) }

        counted.inZone shouldBe 2 // el punto de Italia queda fuera de la caja del prefiltro: no se convierte ni se cuenta como ignorado
        counted.ignored shouldBe emptyMap()
        runBlocking { scan.count(index, geographic, crs, ImportArea.All) }.inZone shouldBe 3
    }

    @Test
    fun `S030 RF-12 una capa lejos del centro del proyecto da una distancia de mas de 100 km`() {
        val session = session("puntos-25830", "vertices")
        val scan = GeoPackageScan(session)
        val index = runBlocking { scan.index(session.layers.single()) }
        val extent = scan.extent(index, utm30, crs)!!

        val seville = scan.distanceToCenterM(extent, crs, CentralPoint(37.3891, -5.9845))!!

        (seville > 100_000.0) shouldBe true
    }
}
