package es.topophone.data.gpkg

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.gpkg.GpkgCrs
import es.topophone.core.formats.gpkg.GpkgCrsCatalog
import es.topophone.core.formats.gpkg.GpkgEnvelope
import es.topophone.core.formats.gpkg.GpkgPart
import es.topophone.core.formats.gpkg.GpkgPartKind
import es.topophone.core.formats.shp.ShpBlobEncoder
import es.topophone.core.formats.shp.ShpShape
import es.topophone.core.model.cad.CadColor
import es.topophone.core.model.cad.CadExtent
import es.topophone.core.model.cad.CadOrigin
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.cad.GpkgColumn
import es.topophone.core.model.cad.GpkgIgnoredReason
import es.topophone.core.model.cad.GpkgLayerInfo
import es.topophone.core.model.cad.GpkgValue
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.survey.LayerBox
import es.topophone.data.cad.FeatureSession
import es.topophone.data.cad.GpkgFeature
import es.topophone.data.cad.RoomCadRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** El importador y el recuento de S048 trabajan con cualquier sesión de capas, no solo con la de un GeoPackage (S030/RF-16, RF-26). */
@RunWith(AndroidJUnit4::class)
class FeatureSessionImporterTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val repository = RoomCadRepository(db)
    private val projectId = runBlocking {
        RoomProjectRepository(db).create(ProjectDefaults().toDraft().copy(name = "Parcelas")).shouldBeInstanceOf<SaveResult.Saved>().id
    }
    private val utm30 = CadSourceCrs.Utm(GeodeticDatum.ETRS89, 30)
    private var tick = 0

    @After
    fun close() = db.close()

    private fun point(e: Double, n: Double): ByteArray = ShpBlobEncoder.encode(
        ShpShape.Parts(GpkgEnvelope(e, e, n, n), listOf(GpkgPart(GpkgPartKind.POINT, doubleArrayOf(e), doubleArrayOf(n), null))),
    )

    private val layer = GpkgLayerInfo("parcelas", "geometry", "POINT", 4, 0, listOf(GpkgColumn("nombre", "TEXT")))

    private fun features(): List<GpkgFeature> = listOf(
        GpkgFeature(1, point(440_100.0, 4_474_100.0), listOf(GpkgValue.Text("Uno"))),
        GpkgFeature(2, null, listOf(GpkgValue.Text("Borrado")), ignored = GpkgIgnoredReason.DELETED_RECORD),
        GpkgFeature(3, null, listOf(GpkgValue.Text("Parche")), ignored = GpkgIgnoredReason.MULTIPATCH),
        GpkgFeature(4, point(440_200.0, 4_474_200.0), listOf(GpkgValue.Text("Dos"))),
    )

    private fun importer() = GeoPackageImporter(repository, clock = { 1_792_000_000_000L }, newId = { "id-${tick++}" })

    private fun import(session: FeatureSession) = runBlocking {
        importer().import(
            projectId,
            ProjectCrs.ETRS89_UTM30,
            "parcelas.zip",
            GpkgImportOptions(listOf(GpkgLayerChoice(layer, utm30, "nombre"))),
            session,
        )
    }

    @Test
    fun `S030 RF-26 el dibujo guarda el origen de la sesion que lo importa`() {
        val result = import(FakeSession(CadOrigin.SHAPEFILE, features())).shouldBeInstanceOf<GpkgImportResult.Imported>()

        val drawing = runBlocking { repository.drawings(projectId).first() }.single().drawing
        drawing.origin shouldBe CadOrigin.SHAPEFILE
        drawing.fileName shouldBe "parcelas.zip"
        result.summary.importedTotal shouldBe 2
    }

    @Test
    fun `S030 RF-16 un objeto que la sesion marca como ignorado se cuenta con su motivo sin convertirlo`() {
        val result = import(FakeSession(CadOrigin.SHAPEFILE, features())).shouldBeInstanceOf<GpkgImportResult.Imported>()

        val summary = result.summary.layers.single()
        summary.imported shouldBe 2
        summary.ignored shouldBe mapOf(GpkgIgnoredReason.DELETED_RECORD to 1, GpkgIgnoredReason.MULTIPATCH to 1)
        summary.outsideArea shouldBe 0
    }

    @Test
    fun `S048 RF-26 un GeoPackage sigue siendo un GeoPackage`() {
        val bytes = GpkgFixtures.bytes("puntos-25830.gpkg")
        val opened = runBlocking { GeoPackageReader(folder.newFolder()).open(bytes.size.toLong()) { ByteArrayInputStream(bytes) } }
        val session: FeatureSession = opened.shouldBeInstanceOf<GpkgOpenResult.Opened>().session

        session.origin shouldBe CadOrigin.GEOPACKAGE
        session.layers.map { it.table } shouldBe listOf("vertices")
        session.close()
    }

    @Test
    fun `S030 RF-13 el recuento usa las cabeceras de la sesion para sus envolventes`() {
        val index = runBlocking { GeoPackageScan(FakeSession(CadOrigin.SHAPEFILE, features())).index(layer) }

        index.size shouldBe 2
        index.bounds shouldBe LayerBox(440_100.0, 440_200.0, 4_474_100.0, 4_474_200.0)
    }

    @Test
    fun `S031 RF-12 el color propio de un objeto llega a su elemento y sin color queda el de su capa`() {
        val colored = listOf(
            GpkgFeature(1, point(440_100.0, 4_474_100.0), listOf(GpkgValue.Text("Rojo")), color = 0xFFFF0000.toInt()),
            GpkgFeature(2, point(440_200.0, 4_474_200.0), listOf(GpkgValue.Text("Sin color"))),
        )
        import(FakeSession(CadOrigin.KML, colored)).shouldBeInstanceOf<GpkgImportResult.Imported>()

        val drawing = runBlocking { repository.drawings(projectId).first() }.single()
        val everywhere = CadExtent(-1e9, 1e9, -1e9, 1e9)
        val entities = runBlocking { repository.chunksIn(drawing.layers.map { it.id }, everywhere) }.flatMap { it.entities }
        entities.map { it.name to it.color } shouldBe listOf("Rojo" to CadColor(0xFFFF0000.toInt()), "Sin color" to null)
    }

    @Test
    fun `S031 RF-16 el dibujo de un KML guarda el origen KML`() {
        import(FakeSession(CadOrigin.KML, features())).shouldBeInstanceOf<GpkgImportResult.Imported>()

        runBlocking { repository.drawings(projectId).first() }.single().drawing.origin shouldBe CadOrigin.KML
    }

    @Test
    fun `S031 RF-9 el resumen lleva los elementos del fichero que no se importan`() {
        val ignored = mapOf(GpkgIgnoredReason.NETWORK_LINK to 2, GpkgIgnoredReason.MODEL to 1)
        val session = FakeSession(CadOrigin.KML, features(), ignoredElements = ignored)
        val result = import(session).shouldBeInstanceOf<GpkgImportResult.Imported>()

        result.summary.ignoredElements shouldBe ignored
        result.summary.ignoredElementsTotal shouldBe 3
    }

    @Test
    fun `S031 RF-9 sin elementos ignorados el resumen no lleva ninguno`() {
        val result = import(FakeSession(CadOrigin.SHAPEFILE, features())).shouldBeInstanceOf<GpkgImportResult.Imported>()

        result.summary.ignoredElements shouldBe emptyMap()
        result.summary.ignoredElementsTotal shouldBe 0
    }

    @Test
    fun `S031 RF-13 una sesion que no sabe si hay altitudes lo dice con null`() {
        FakeSession(CadOrigin.SHAPEFILE, features()).hasElevations shouldBe null
        FakeSession(CadOrigin.KML, features(), hasElevations = true).hasElevations shouldBe true
    }

    @Test
    fun `S031 RF-13 un GeoPackage y un Shapefile no saben si hay altitudes`() {
        val bytes = GpkgFixtures.bytes("puntos-25830.gpkg")
        val opened = runBlocking { GeoPackageReader(folder.newFolder()).open(bytes.size.toLong()) { ByteArrayInputStream(bytes) } }
        val session: FeatureSession = opened.shouldBeInstanceOf<GpkgOpenResult.Opened>().session

        session.hasElevations shouldBe null
        session.ignoredElements shouldBe emptyMap()
        session.close()
    }

    /** Una sesión en memoria: cada capa con sus objetos, sin ficheros. */
    private class FakeSession(
        override val origin: CadOrigin,
        private val all: List<GpkgFeature>,
        override val ignoredElements: Map<GpkgIgnoredReason, Int> = emptyMap(),
        override val hasElevations: Boolean? = null,
    ) : FeatureSession {
        private val info = GpkgLayerInfo("parcelas", "geometry", "POINT", all.size.toLong(), 0, listOf(GpkgColumn("nombre", "TEXT")))
        override val layers: List<GpkgLayerInfo> = listOf(info)
        override val leftOutTables: List<String> = emptyList()

        override fun crs(layer: GpkgLayerInfo): GpkgCrs = GpkgCrsCatalog.supported(ProjectCrs.ETRS89_UTM30)

        override fun forEachFeature(layer: GpkgLayerInfo, box: LayerBox?, action: (GpkgFeature) -> Unit) = all.forEach(action)

        override fun forEachHead(layer: GpkgLayerInfo, action: (fid: Long, head: ByteArray?) -> Unit) {
            all.filter { it.ignored == null }.forEach { action(it.fid, it.geometry?.copyOf(40)) }
        }

        override fun forEachIndexRow(
            layer: GpkgLayerInfo,
            action: (fid: Long, minX: Double, maxX: Double, minY: Double, maxY: Double) -> Unit,
        ): Boolean = false

        override fun forEachGeometry(layer: GpkgLayerInfo, ids: LongArray, action: (fid: Long, geometry: ByteArray?) -> Unit) {
            all.filter { it.fid in ids }.forEach { action(it.fid, it.geometry) }
        }

        override fun close() = Unit
    }
}
