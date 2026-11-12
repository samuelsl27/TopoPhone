package es.topophone.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.backup.BackupKind
import es.topophone.core.model.cad.CadChunkCodec
import es.topophone.core.model.cad.CadColor
import es.topophone.core.model.cad.CadDrawing
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadEntityRef
import es.topophone.core.model.cad.CadExtent
import es.topophone.core.model.cad.CadOrigin
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.cad.DesignLayer
import es.topophone.core.model.cad.DesignLayerKind
import es.topophone.core.model.cad.GpkgAttributesCodec
import es.topophone.core.model.cad.GpkgValue
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.cad.CadChunkWrite
import es.topophone.data.cad.RoomCadRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.SaveResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Los dibujos de Shapefile viajan en las copias y los paquetes con su origen, el nombre de cada capa, el campo de nombre y los atributos (S030/RF-27, RF-28). */
@RunWith(AndroidJUnit4::class)
class ShpBackupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mine = "shp-a-${System.nanoTime()}.db"
    private val theirs = "shp-b-${System.nanoTime()}.db"
    private lateinit var db: TopoPhoneDatabase
    private lateinit var other: TopoPhoneDatabase
    private lateinit var settings: TestSettings
    private lateinit var repository: BackupRepository
    private lateinit var otherRepository: BackupRepository
    private lateinit var cad: RoomCadRepository
    private lateinit var otherCad: RoomCadRepository
    private lateinit var project: String
    private val everywhere = CadExtent(-1e9, 1e9, -1e9, 1e9)
    private val columns = listOf("nombre", "cota", "foto")

    @Before
    fun open() = runBlocking {
        db = TopoPhoneDatabase.build(context, mine)
        other = TopoPhoneDatabase.build(context, theirs)
        val fixture = BackupFixture(db).fill()
        settings = TestSettings(folder.newFolder("settings"))
        repository =
            BackupRepository(context, db, settings.archive, "0.13.0", workDir = folder.newFolder("wa"), undoDir = folder.newFolder("ua"))
        otherRepository =
            BackupRepository(context, other, settings.archive, "0.13.0", workDir = folder.newFolder("wb"), undoDir = folder.newFolder("ub"))
        cad = RoomCadRepository(db)
        otherCad = RoomCadRepository(other)
        project = (fixture.projects.create(ProjectDefaults().toDraft().copy(name = "Con Shapefile")) as SaveResult.Saved).id
        shpDrawing()
        Unit
    }

    @After
    fun close() {
        settings.close()
        db.close()
        other.close()
        context.deleteDatabase(mine)
        context.deleteDatabase(theirs)
    }

    private fun point(name: String, e: Double) =
        CadEntity(CadEntityKind.POINT, doubleArrayOf(e), doubleArrayOf(4_474_000.0), doubleArrayOf(646.377), null, name = name)

    private fun ring(name: String) = CadEntity(
        CadEntityKind.POLYLINE,
        doubleArrayOf(0.0, 10.0, 10.0, 0.0),
        doubleArrayOf(0.0, 0.0, 10.0, 0.0),
        null,
        null,
        name = name,
        polygonRing = true,
    )

    private fun row(name: String, cota: Double?, foto: GpkgValue) =
        GpkgAttributesCodec.encodeRow(listOf(GpkgValue.Text(name), cota?.let { GpkgValue.Real(it) } ?: GpkgValue.Null, foto))

    private suspend fun shpDrawing() {
        cad.begin(
            CadDrawing(
                id = "g1",
                projectId = project,
                fileName = "parcelas.zip",
                importedAtMs = 1_792_346_000_000L,
                source = CadSourceCrs.Project,
                unit = LinearUnit.METRE,
                useElevations = true,
                hasElevations = true,
                origin = CadOrigin.SHAPEFILE,
            ),
        )
        cad.addLayer(
            DesignLayer(
                "lp",
                "g1",
                "vertices",
                DesignLayerKind.POINTS,
                CadColor.rgb(0xE6194B),
                sourceTable = "vertices",
                nameField = "nombre",
            ),
        )
        cad.addLayer(
            DesignLayer("ll", "g1", "parcelas", DesignLayerKind.LINES, CadColor.rgb(0x3CB44B), position = 1, sourceTable = "parcelas"),
        )
        val points = listOf(point("Farola", 440_100.0), point("Árbol", 440_200.0))
        val rings = listOf(ring("Parcela"), ring("Parcela"))
        cad.addChunks(
            listOf(
                CadChunkWrite(
                    "lp",
                    CadExtent(440_100.0, 440_200.0, 4_474_000.0, 4_474_000.0),
                    2,
                    CadChunkCodec.encode(points),
                    GpkgAttributesCodec.encodeBlock(
                        columns,
                        listOf(row("Farola", 646.5, GpkgValue.Null), row("Árbol", null, GpkgValue.Omitted(70_000))),
                    ),
                ),
                CadChunkWrite(
                    "ll",
                    CadExtent(0.0, 10.0, 0.0, 10.0),
                    2,
                    CadChunkCodec.encode(rings),
                    GpkgAttributesCodec.encodeBlock(
                        columns,
                        listOf(row("Parcela", 1.0, GpkgValue.Null), row("Parcela", 1.0, GpkgValue.Null)),
                    ),
                ),
            ),
        )
        cad.finish(
            "g1",
            mapOf("lp" to 2, "ll" to 2),
            hasElevations = true,
            incomplete = false,
            extent = CadExtent(0.0, 440_200.0, 0.0, 4_474_000.0),
        )
    }

    private fun packageBytes() = ByteArrayOutputStream().also {
        runBlocking { repository.writePackage(project, it) } shouldBe true
    }.toByteArray()

    private fun importInto(repo: BackupRepository, bytes: ByteArray, choice: ImportChoice? = null) {
        val valid = runBlocking { repo.read({ bytes.inputStream() }, BackupKind.PACKAGE) }.shouldBeInstanceOf<BackupRead.Valid>()
        runBlocking { repo.importPackage(valid, choice, importedLabel = "importado") }.shouldBeInstanceOf<ImportResult.Imported>()
    }

    private fun count(database: TopoPhoneDatabase, table: String): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
            it.moveToFirst()
            it.getInt(0)
        }

    /** Los atributos del primer elemento de cada trozo de la capa de [table], por nombre de elemento. */
    private fun attributesByName(repo: RoomCadRepository, table: String): Map<String?, Map<String, GpkgValue>> = runBlocking {
        val layer = repo.drawings(project).first().single().layers.first { it.sourceTable == table }
        val chunk = repo.chunksIn(listOf(layer.id), everywhere).single()
        chunk.entities.indices.associate { chunk.entities[it].name to repo.attributes(CadEntityRef(layer.id, chunk.chunkId, it)) }
    }

    @Test
    fun `S030 RF-28 el paquete lleva el origen, las capas y los atributos`() {
        importInto(otherRepository, packageBytes())

        val drawing = runBlocking { otherCad.drawings(project).first() }.single()
        drawing.drawing.origin shouldBe CadOrigin.SHAPEFILE
        drawing.layers.map { it.sourceTable to it.nameField } shouldBe listOf("vertices" to "nombre", "parcelas" to null)
        count(other, "cad_chunk_attributes") shouldBe 2
        attributesByName(otherCad, "vertices").getValue("Farola") shouldBe
            linkedMapOf("nombre" to GpkgValue.Text("Farola"), "cota" to GpkgValue.Real(646.5), "foto" to GpkgValue.Null)
        attributesByName(otherCad, "vertices").getValue("Árbol")["foto"] shouldBe GpkgValue.Omitted(70_000)
    }

    @Test
    fun `S030 RF-28 los trozos de un paquete reciben ids nuevos y sus atributos los siguen`() {
        val bytes = packageBytes()
        importInto(otherRepository, bytes)
        importInto(otherRepository, bytes, ImportChoice.AS_NEW) // un segundo dibujo igual, con otros ids

        count(other, "cad_chunks") shouldBe 4
        count(other, "cad_chunk_attributes") shouldBe 4
        val orphans = other.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM cad_chunk_attributes WHERE chunk_id NOT IN (SELECT id FROM cad_chunks)",
        ).use {
            it.moveToFirst()
            it.getInt(0)
        }
        orphans shouldBe 0
    }

    @Test
    fun `S030 RF-28 los contornos de poligono vuelven con su nombre y su marca`() {
        importInto(otherRepository, packageBytes())

        val layer = runBlocking { otherCad.drawings(project).first() }.single().layers.first { it.sourceTable == "parcelas" }
        val entities = runBlocking { otherCad.chunksIn(listOf(layer.id), everywhere) }.flatMap { it.entities }

        entities.map { it.name to it.polygonRing } shouldBe listOf("Parcela" to true, "Parcela" to true)
        attributesByName(otherCad, "parcelas").getValue("Parcela")["cota"] shouldBe GpkgValue.Real(1.0)
    }

    @Test
    fun `S030 RF-28 la copia completa restaura los atributos`() {
        val backup = ByteArrayOutputStream().also { runBlocking { repository.writeBackup(it) } shouldBe true }.toByteArray()
        runBlocking { cad.deleteDrawing("g1") }
        count(db, "cad_chunk_attributes") shouldBe 0

        val valid = runBlocking { repository.read({ backup.inputStream() }, BackupKind.BACKUP) }.shouldBeInstanceOf<BackupRead.Valid>()
        runBlocking { repository.restore(valid) }.shouldBeInstanceOf<RestoreResult.Restored>()

        count(db, "cad_chunk_attributes") shouldBe 2
        runBlocking { cad.drawings(project).first() }.single().drawing.origin shouldBe CadOrigin.SHAPEFILE
        attributesByName(cad, "vertices").getValue("Farola")["cota"] shouldBe GpkgValue.Real(646.5)
    }

    @Test
    fun `S030 RF-27 borrar el dibujo borra sus atributos y no toca los puntos`() {
        val points = count(db, "points")

        runBlocking { cad.deleteDrawing("g1") }

        count(db, "cad_chunk_attributes") shouldBe 0
        count(db, "cad_chunks") shouldBe 0
        count(db, "points") shouldBe points
    }

    @Test
    fun `S030 RF-28 borrar el proyecto borra los atributos de sus dibujos`() {
        db.openHelper.writableDatabase.execSQL("DELETE FROM projects WHERE id = ?", arrayOf<Any>(project))

        count(db, "cad_chunk_attributes") shouldBe 0
        count(db, "cad_drawings") shouldBe 0
    }
}
