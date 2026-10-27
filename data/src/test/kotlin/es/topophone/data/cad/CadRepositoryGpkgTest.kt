package es.topophone.data.cad

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.cad.CadChunkCodec
import es.topophone.core.model.cad.CadColor
import es.topophone.core.model.cad.CadDrawing
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadExtent
import es.topophone.core.model.cad.CadOrigin
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.cad.DesignLayer
import es.topophone.core.model.cad.DesignLayerKind
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** El origen de un dibujo, la tabla y el campo de nombre de sus capas, y los atributos de un trozo (S048/RF-20, RF-21, RF-26). */
@RunWith(AndroidJUnit4::class)
class CadRepositoryGpkgTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val cad = RoomCadRepository(db)
    private val projectId = runBlocking {
        RoomProjectRepository(
            db,
        ).create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @After
    fun close() = db.close()

    private fun drawing(id: String, origin: CadOrigin) = CadDrawing(
        id = id,
        projectId = projectId,
        fileName = "capas.gpkg",
        importedAtMs = 1_000L,
        source = CadSourceCrs.Project,
        unit = LinearUnit.METRE,
        useElevations = true,
        hasElevations = false,
        origin = origin,
    )

    private fun chunk(layerId: String): CadChunkWrite {
        val entity =
            CadEntity(
                CadEntityKind.POLYLINE,
                doubleArrayOf(1.0, 2.0),
                doubleArrayOf(3.0, 3.0),
                null,
                null,
                name = "Parcela",
                polygonRing = true,
            )
        return CadChunkWrite(layerId, entity.bounds, 1, CadChunkCodec.encode(listOf(entity)))
    }

    @Test
    fun `S048 RF-26 el dibujo vuelve con su origen`() = runBlocking<Unit> {
        cad.begin(drawing("g1", CadOrigin.GEOPACKAGE))
        cad.begin(drawing("d1", CadOrigin.DXF))
        listOf("g1", "d1").forEach { cad.finish(it, emptyMap(), false, false, CadExtent(0.0, 1.0, 0.0, 1.0)) }

        val read = cad.drawings(projectId).first().associate { it.drawing.id to it.drawing.origin }

        read shouldBe mapOf("g1" to CadOrigin.GEOPACKAGE, "d1" to CadOrigin.DXF)
    }

    @Test
    fun `S048 RF-21 la capa guarda tabla y campo de nombre`() = runBlocking<Unit> {
        cad.begin(drawing("g1", CadOrigin.GEOPACKAGE))
        val withName =
            DesignLayer(
                "l1",
                "g1",
                "parcelas",
                DesignLayerKind.LINES,
                CadColor.rgb(0x00AA55),
                sourceTable = "parcelas",
                nameField = "nombre",
            )
        val withoutName = DesignLayer("l2", "g1", "vertices", DesignLayerKind.POINTS, CadColor.rgb(0xAA0055), sourceTable = "vertices")
        val dxf = DesignLayer("l3", "g1", "Ejes", DesignLayerKind.LINES, CadColor.rgb(0xFF0000))
        listOf(withName, withoutName, dxf).forEach { cad.addLayer(it) }
        cad.finish("g1", emptyMap(), false, false, CadExtent(0.0, 1.0, 0.0, 1.0))

        val layers = cad.drawings(projectId).first().single().layers.associateBy { it.id }

        layers.getValue("l1").sourceTable shouldBe "parcelas"
        layers.getValue("l1").nameField shouldBe "nombre"
        layers.getValue("l2").sourceTable shouldBe "vertices"
        layers.getValue("l2").nameField shouldBe null
        layers.getValue("l3").sourceTable shouldBe null
        layers.getValue("l3").nameField shouldBe null
    }

    @Test
    fun `S048 RF-22 nombre y contorno vuelven con el trozo`() = runBlocking<Unit> {
        cad.begin(drawing("g1", CadOrigin.GEOPACKAGE))
        cad.addLayer(DesignLayer("l1", "g1", "parcelas", DesignLayerKind.LINES, CadColor.rgb(0)))
        cad.addChunks(listOf(chunk("l1")))
        cad.finish("g1", mapOf("l1" to 1), false, false, CadExtent(0.0, 3.0, 0.0, 3.0))

        val back = cad.chunksIn(listOf("l1"), CadExtent(0.0, 10.0, 0.0, 10.0)).single().entities.single()

        back.name shouldBe "Parcela"
        back.polygonRing shouldBe true
    }

    @Test
    fun `S048 RF-20 los atributos se borran con el dibujo`() = runBlocking<Unit> {
        cad.begin(drawing("g1", CadOrigin.GEOPACKAGE))
        cad.addLayer(DesignLayer("l1", "g1", "parcelas", DesignLayerKind.LINES, CadColor.rgb(0)))
        cad.addChunks(listOf(chunk("l1")))
        val chunkId = cad.chunksIn(listOf("l1"), CadExtent(0.0, 10.0, 0.0, 10.0)).single().chunkId
        db.openHelper.writableDatabase.execSQL("INSERT INTO cad_chunk_attributes (chunk_id, data) VALUES ($chunkId, x'0102')")
        count("cad_chunk_attributes") shouldBe 1

        cad.deleteDrawing("g1")

        count("cad_chunks") shouldBe 0
        count("cad_chunk_attributes") shouldBe 0
    }

    private fun count(table: String): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
        it.moveToFirst()
        it.getInt(0)
    }
}
