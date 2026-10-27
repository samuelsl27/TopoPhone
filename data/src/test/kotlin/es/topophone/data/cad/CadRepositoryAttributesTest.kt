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
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Leer los atributos de un elemento de un dibujo de GeoPackage por su referencia (S048/RF-20). */
@RunWith(AndroidJUnit4::class)
class CadRepositoryAttributesTest {

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
    private val columns = listOf("nombre", "cota")
    private val everywhere = CadExtent(-1e9, 1e9, -1e9, 1e9)

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

    private fun point(e: Double) = CadEntity(CadEntityKind.POINT, doubleArrayOf(e), doubleArrayOf(e), null, null, name = "P$e")

    private fun row(name: String, cota: Double) = GpkgAttributesCodec.encodeRow(listOf(GpkgValue.Text(name), GpkgValue.Real(cota)))

    /** Un dibujo con un trozo de dos puntos y, si se pide, su bloque de atributos. */
    private fun withChunk(id: String, origin: CadOrigin, attributes: ByteArray?): CadEntityRef = runBlocking {
        cad.begin(drawing(id, origin))
        cad.addLayer(DesignLayer("l-$id", id, "capa", DesignLayerKind.POINTS, CadColor.rgb(0)))
        val entities = listOf(point(1.0), point(2.0))
        cad.addChunks(listOf(CadChunkWrite("l-$id", CadExtent(1.0, 2.0, 1.0, 2.0), 2, CadChunkCodec.encode(entities), attributes)))
        cad.finish(id, mapOf("l-$id" to 2), false, false, CadExtent(1.0, 2.0, 1.0, 2.0))
        CadEntityRef("l-$id", cad.chunksIn(listOf("l-$id"), everywhere).single().chunkId, 0)
    }

    private fun block(vararg rows: ByteArray) = GpkgAttributesCodec.encodeBlock(columns, rows.toList())

    @Test
    fun `S048 RF-20 los atributos de cada elemento salen por su posicion`() = runBlocking<Unit> {
        val ref = withChunk("g1", CadOrigin.GEOPACKAGE, block(row("Farola", 646.5), row("Árbol", 647.0)))

        cad.attributes(ref) shouldBe linkedMapOf("nombre" to GpkgValue.Text("Farola"), "cota" to GpkgValue.Real(646.5))
        cad.attributes(ref.copy(index = 1)) shouldBe linkedMapOf("nombre" to GpkgValue.Text("Árbol"), "cota" to GpkgValue.Real(647.0))
    }

    @Test
    fun `S048 RF-20 un elemento de un DXF no tiene atributos`() = runBlocking<Unit> {
        val ref = withChunk("d1", CadOrigin.DXF, null)

        cad.attributes(ref) shouldBe emptyMap()
    }

    @Test
    fun `S048 RF-20 una posicion fuera del trozo o un trozo que no existe dan vacio`() = runBlocking<Unit> {
        val ref = withChunk("g1", CadOrigin.GEOPACKAGE, block(row("Farola", 646.5), row("Árbol", 647.0)))

        cad.attributes(ref.copy(index = 2)) shouldBe emptyMap()
        cad.attributes(ref.copy(index = -1)) shouldBe emptyMap()
        cad.attributes(ref.copy(chunkId = 9_999)) shouldBe emptyMap()
    }

    @Test
    fun `S048 RF-20 un bloque danado da vacio y no una excepcion`() = runBlocking<Unit> {
        val ref = withChunk("g1", CadOrigin.GEOPACKAGE, byteArrayOf(5, 1, 2))

        cad.attributes(ref) shouldBe emptyMap()
    }

    @Test
    fun `S048 RF-20 cada trozo tiene sus propios atributos`() = runBlocking<Unit> {
        val first = withChunk("g1", CadOrigin.GEOPACKAGE, block(row("Uno", 1.0), row("Dos", 2.0)))
        val second = withChunk("g2", CadOrigin.GEOPACKAGE, block(row("Tres", 3.0), row("Cuatro", 4.0)))

        cad.attributes(first)["nombre"] shouldBe GpkgValue.Text("Uno")
        cad.attributes(second)["nombre"] shouldBe GpkgValue.Text("Tres")
        (first.chunkId == second.chunkId) shouldBe false
    }
}
