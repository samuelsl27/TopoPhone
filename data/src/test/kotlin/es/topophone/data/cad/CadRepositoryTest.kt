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
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.cad.DesignLayer
import es.topophone.core.model.cad.DesignLayerKind
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Guardar, listar, ocultar y borrar los dibujos importados (S040/RF-12, RF-19, RF-27, RF-28, RF-30). */
@RunWith(AndroidJUnit4::class)
class CadRepositoryTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val cad = RoomCadRepository(db)

    @After
    fun close() = db.close()

    private fun newProject(name: String) = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = name)).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private val project = newProject("Calle Mayor 12")

    private fun drawing(id: String, projectId: String = project, file: String = "plano.dxf", at: Long = 1_000L) = CadDrawing(
        id = id,
        projectId = projectId,
        fileName = file,
        importedAtMs = at,
        source = CadSourceCrs.Utm(GeodeticDatum.ED50, 30),
        unit = LinearUnit.US_SURVEY_FOOT,
        useElevations = true,
        hasElevations = false,
    )

    private fun layer(id: String, drawingId: String, name: String = "Ejes", kind: DesignLayerKind = DesignLayerKind.LINES) =
        DesignLayer(id, drawingId, name, kind, CadColor.rgb(0xFF0000))

    private fun line(e: Double, n: Double, z: Double? = null) = CadEntity(
        CadEntityKind.LINE,
        doubleArrayOf(e, e + 10.0),
        doubleArrayOf(n, n),
        z?.let { doubleArrayOf(it, it) },
        null,
    )

    private fun chunk(layerId: String, vararg entities: CadEntity): CadChunkWrite {
        val boxes = entities.map { it.bounds }
        val bounds =
            CadExtent(
                boxes.minOf {
                    it.minEasting
                },
                boxes.maxOf { it.maxEasting },
                boxes.minOf { it.minNorthing },
                boxes.maxOf { it.maxNorthing },
            )
        return CadChunkWrite(layerId, bounds, entities.size, CadChunkCodec.encode(entities.toList()))
    }

    private fun finished(id: String, layers: Map<String, Int>, hasElevations: Boolean = true, incomplete: Boolean = false) = runBlocking {
        cad.finish(id, layers, hasElevations, incomplete, CadExtent(440000.0, 440100.0, 4474000.0, 4474100.0))
    }

    @Test
    fun `S040 RF-12 un dibujo sin terminar no existe para el resto de la app`() = inRoom {
        cad.begin(drawing("d1"))
        cad.addLayer(layer("l1", "d1"))
        cad.addChunks(listOf(chunk("l1", line(440000.0, 4474000.0))))

        cad.drawings(project).first() shouldBe emptyList()
    }

    @Test
    fun `S040 RF-12 al terminar el dibujo aparece con sus capas, la cota y la extension`() = inRoom {
        cad.begin(drawing("d1"))
        cad.addLayer(layer("l1", "d1", "Ejes"))
        cad.addLayer(layer("l2", "d1", "Pozos", DesignLayerKind.POINTS).copy(position = 1))
        cad.addChunks(listOf(chunk("l1", line(440000.0, 4474000.0))))
        finished("d1", mapOf("l1" to 5, "l2" to 2), hasElevations = true, incomplete = true)

        val listed = cad.drawings(project).first().single()

        listed.drawing.id shouldBe "d1"
        listed.drawing.fileName shouldBe "plano.dxf"
        listed.drawing.source shouldBe CadSourceCrs.Utm(GeodeticDatum.ED50, 30)
        listed.drawing.unit shouldBe LinearUnit.US_SURVEY_FOOT
        listed.drawing.hasElevations shouldBe true
        listed.drawing.incomplete shouldBe true
        listed.drawing.extent shouldBe CadExtent(440000.0, 440100.0, 4474000.0, 4474100.0)
        listed.layers.map { it.name to it.entityCount } shouldBe listOf("Ejes" to 5, "Pozos" to 2)
        listed.layers.map { it.kind } shouldBe listOf(DesignLayerKind.LINES, DesignLayerKind.POINTS)
        listed.layers.all { it.visible } shouldBe true
    }

    @Test
    fun `S040 RF-12 cancelar o fallar borra el dibujo con sus capas y trozos`() = inRoom {
        cad.begin(drawing("d1"))
        cad.addLayer(layer("l1", "d1"))
        cad.addChunks(listOf(chunk("l1", line(1.0, 2.0))))

        cad.abort("d1")

        count("cad_drawings") shouldBe 0
        count("cad_layers") shouldBe 0
        count("cad_chunks") shouldBe 0
    }

    @Test
    fun `S040 RF-12 al arrancar se borra lo que quedo a medias y no lo terminado`() = inRoom {
        cad.begin(drawing("hecho"))
        cad.addLayer(layer("l1", "hecho"))
        finished("hecho", mapOf("l1" to 0))
        cad.begin(drawing("a-medias"))
        cad.addLayer(layer("l2", "a-medias"))
        cad.addChunks(listOf(chunk("l2", line(1.0, 2.0))))

        cad.cleanUpUnfinished() shouldBe 1

        cad.drawings(project).first().map { it.drawing.id } shouldBe listOf("hecho")
        count("cad_chunks") shouldBe 0
    }

    @Test
    fun `S040 RF-19 las coordenadas de un trozo vuelven sin redondear`() = inRoom {
        val original = line(440000.123456789, 4474000.987654321, 646.377)
        cad.begin(drawing("d1"))
        cad.addLayer(layer("l1", "d1"))
        cad.addChunks(listOf(chunk("l1", original)))
        finished("d1", mapOf("l1" to 1))

        val back = cad.chunksIn(listOf("l1"), CadExtent(0.0, 1.0e9, 0.0, 1.0e9)).single().entities.single()

        back shouldBe original
        back.easting[0].toRawBits() shouldBe original.easting[0].toRawBits()
    }

    @Test
    fun `S040 RF-27 los trozos se leen por zona y solo de las capas pedidas`() = inRoom {
        cad.begin(drawing("d1"))
        cad.addLayer(layer("l1", "d1", "Ejes"))
        cad.addLayer(layer("l2", "d1", "Otra"))
        cad.addChunks(
            listOf(
                chunk("l1", line(440000.0, 4474000.0)), // 440000..440010
                chunk("l1", line(441000.0, 4474000.0)),
                chunk("l2", line(440000.0, 4474000.0)),
            ),
        )
        finished("d1", mapOf("l1" to 2, "l2" to 1))

        val near = cad.chunksIn(listOf("l1"), CadExtent(439990.0, 440005.0, 4473990.0, 4474010.0))
        val both = cad.chunksIn(listOf("l1", "l2"), CadExtent(439990.0, 441500.0, 4473990.0, 4474010.0))
        val none = cad.chunksIn(listOf("l1"), CadExtent(0.0, 10.0, 0.0, 10.0))

        near.size shouldBe 1
        near.single().layerId shouldBe "l1"
        near.single().bounds.minEasting shouldBe 440000.0
        both.size shouldBe 3
        none shouldBe emptyList()
        cad.chunksIn(emptyList(), CadExtent(0.0, 1.0e9, 0.0, 1.0e9)) shouldBe emptyList()
    }

    @Test
    fun `S040 RF-27 un trozo concreto se lee por su identificador`() = inRoom {
        cad.begin(drawing("d1"))
        cad.addLayer(layer("l1", "d1"))
        cad.addChunks(listOf(chunk("l1", line(1.0, 2.0), line(3.0, 4.0))))
        finished("d1", mapOf("l1" to 2))
        val id = cad.chunksIn(listOf("l1"), CadExtent(0.0, 100.0, 0.0, 100.0)).single().chunkId

        cad.chunk(id)!!.entities.size shouldBe 2
        cad.chunk(id + 1000) shouldBe null
    }

    @Test
    fun `S040 RF-27 la visibilidad de una capa se guarda y sobrevive a releer`() = inRoom {
        cad.begin(drawing("d1"))
        cad.addLayer(layer("l1", "d1"))
        cad.addLayer(layer("l2", "d1", "Otra"))
        finished("d1", mapOf("l1" to 0, "l2" to 0))

        cad.setLayerVisible("l1", false)

        cad.drawings(project).first().single().layers.associate { it.id to it.visible } shouldBe mapOf("l1" to false, "l2" to true)
        cad.setLayerVisible("l1", true)
        cad.drawings(project).first().single().layers.all { it.visible } shouldBe true
    }

    @Test
    fun `S040 RF-28 dos dibujos con una capa del mismo nombre se guardan aparte`() = inRoom {
        cad.begin(drawing("d1", file = "uno.dxf", at = 1))
        cad.addLayer(layer("l1", "d1", "Ejes"))
        finished("d1", mapOf("l1" to 1))
        cad.begin(drawing("d2", file = "dos.dxf", at = 2))
        cad.addLayer(layer("l2", "d2", "Ejes"))
        finished("d2", mapOf("l2" to 3))

        val listed = cad.drawings(project).first()

        listed.map { it.drawing.fileName } shouldBe listOf("uno.dxf", "dos.dxf")
        listed.map { it.layers.single().entityCount } shouldBe listOf(1, 3)
        listed.map { it.layers.single().id } shouldBe listOf("l1", "l2")
    }

    @Test
    fun `S040 RF-30 borrar un dibujo quita sus capas y trozos y deja los puntos y otros dibujos`() = inRoom {
        cad.begin(drawing("d1"))
        cad.addLayer(layer("l1", "d1"))
        cad.addChunks(listOf(chunk("l1", line(1.0, 2.0))))
        finished("d1", mapOf("l1" to 1))
        cad.begin(drawing("d2"))
        cad.addLayer(layer("l2", "d2"))
        cad.addChunks(listOf(chunk("l2", line(3.0, 4.0))))
        finished("d2", mapOf("l2" to 1))
        val points = RoomPointRepository(db)

        cad.deleteDrawing("d1")

        cad.drawings(project).first().map { it.drawing.id } shouldBe listOf("d2")
        count("cad_chunks") shouldBe 1
        points.points(project).first() shouldBe emptyList()
    }

    @Test
    fun `S040 RF-30 los dibujos de cada proyecto son de ese proyecto`() = inRoom {
        val other = newProject("Otro")
        cad.begin(drawing("d1"))
        cad.addLayer(layer("l1", "d1"))
        finished("d1", mapOf("l1" to 0))
        cad.begin(drawing("d2", projectId = other))
        cad.addLayer(layer("l2", "d2"))
        finished("d2", mapOf("l2" to 0))

        cad.drawings(project).first().map { it.drawing.id } shouldBe listOf("d1")
        cad.drawings(other).first().map { it.drawing.id } shouldBe listOf("d2")
    }

    @Test
    fun `S040 RF-12 varias tandas de trozos se suman`() = inRoom {
        cad.begin(drawing("d1"))
        cad.addLayer(layer("l1", "d1"))
        repeat(3) { batch -> cad.addChunks(List(64) { chunk("l1", line(440000.0 + batch * 100 + it, 4474000.0)) }) }
        finished("d1", mapOf("l1" to 192))

        count("cad_chunks") shouldBe 192
    }

    /** Los métodos de prueba de JUnit 4 devuelven void: el bloque devuelve Unit aunque su última línea sea una comprobación. */
    private fun inRoom(block: suspend () -> Unit) = runBlocking { block() }

    private fun count(table: String): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
        it.moveToFirst()
        it.getInt(0)
    }
}
