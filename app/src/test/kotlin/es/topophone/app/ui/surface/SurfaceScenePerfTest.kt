package es.topophone.app.ui.surface

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.geodesy.GeoBox
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.surface.SurfaceExtent
import es.topophone.core.model.surface.SurfaceTileCodec
import es.topophone.core.survey.CadPlacement
import es.topophone.core.survey.TinOutline
import es.topophone.core.survey.TinTiler
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.data.surface.RoomSurfaceRepository
import es.topophone.data.surface.SurfaceDraft
import es.topophone.data.surface.SurfaceSummary
import es.topophone.data.surface.SurfaceTileWrite
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.measureTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La escena de una vista con 20.000 triángulos de una superficie de un millón se construye en menos de 1 s, y la de la superficie
 * entera (solo el contorno) también (S061, RNF y plan, estrategia de tests). Robolectric sirve de cota; la fluidez del mapa se mide en
 * la demo del móvil de referencia.
 */
@RunWith(AndroidJUnit4::class)
class SurfaceScenePerfTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val repository = RoomSurfaceRepository(db)
    private val project = runBlocking {
        RoomProjectRepository(db).create(ProjectDefaults().toDraft().copy(name = "Obra")).shouldBeInstanceOf<SaveResult.Saved>().id
    }
    private val frame = SurfaceProjectFrame(ProjectCrs.ETRS89_UTM30, HeightSystem.ORTHOMETRIC, null)

    @After
    fun close() = db.close()

    private val e0 = 440_000.0
    private val n0 = 4_474_000.0
    private val side = 708 // 1.002.528 triángulos de 2 m

    private fun write() = runBlocking {
        val w = side + 1
        val e = DoubleArray(w * w) { e0 + (it % w) * 2.0 }
        val n = DoubleArray(w * w) { n0 + (it / w) * 2.0 }
        val z = DoubleArray(w * w) { 600.0 + 0.01 * (it % w) + 0.02 * (it / w) }
        val t = IntArray(side * side * 6)
        var k = 0
        for (j in 0 until side) {
            for (i in 0 until side) {
                val a = j * w + i
                for (v in intArrayOf(a, a + 1, a + w + 1, a, a + w + 1, a + w)) t[k++] = v
            }
        }
        repository.begin(SurfaceDraft("s1", project, "grande", "grande.xml", 1L, CadSourceCrs.Project, LinearUnit.METRE))
        for (batch in TinTiler.tile(e, n, z, t).chunked(8)) {
            repository.addTiles("s1", batch.map { SurfaceTileWrite(it.region, it.tile.triangleCount, SurfaceTileCodec.encode(it.tile)) })
        }
        val extent = SurfaceExtent(e0, e0 + side * 2.0, n0, n0 + side * 2.0)
        repository.finish("s1", SurfaceSummary(extent, 600.0, 621.3, w * w, t.size / 3, 0))
        repository.saveOutline("s1", TinOutline.rings(e, n, t))
    }

    private fun viewOf(minE: Double, maxE: Double, minN: Double, maxN: Double): GeoBox {
        val sw = CadPlacement.geographic(ProjectCrs.ETRS89_UTM30, minE, minN)!!
        val ne = CadPlacement.geographic(ProjectCrs.ETRS89_UTM30, maxE, maxN)!!
        return GeoBox(sw.first, sw.second, ne.first, ne.second)
    }

    @Test
    fun `S061 RNF la escena de una vista de 20000 triangulos tarda menos de 1 s`() = runBlocking<Unit> {
        write()
        val surfaces = repository.surfaces(project).first()
        val builder = SurfaceSceneBuilder(repository)
        builder.build(frame, surfaces, "s1", viewOf(e0 + 600.0, e0 + 700.0, n0 + 600.0, n0 + 700.0)) // calentar la JIT y la caché

        // 190 × 190 m: unas 98 × 98 celdas, cerca de 20.000 triángulos dentro (el límite de la malla) y unos 28.000 dibujados con la
        // vista ampliada (la convergencia de la cuadrícula UTM ensancha un poco el recuadro al pasarlo a grados y volver).
        var scene: SurfaceScene? = null
        val elapsed = measureTime {
            scene = builder.build(frame, surfaces, "s1", viewOf(e0 + 105.0, e0 + 295.0, n0 + 105.0, n0 + 295.0))
        }

        val mesh = scene.shouldNotBeNull().meshes.single()
        (mesh.count >= 19_000) shouldBe true
        println("Escena de ${mesh.count} triángulos: ${elapsed.inWholeMilliseconds} ms")
        elapsed.inWholeMilliseconds shouldBeLessThan 1_000L
    }

    @Test
    fun `S061 RNF la escena de la superficie entera es solo su contorno y tarda menos de 1 s`() = runBlocking<Unit> {
        write()
        val surfaces = repository.surfaces(project).first()
        val builder = SurfaceSceneBuilder(repository)
        val whole = viewOf(e0 - 10.0, e0 + side * 2.0 + 10.0, n0 - 10.0, n0 + side * 2.0 + 10.0)
        builder.build(frame, surfaces, "s1", whole) // el contorno se convierte una vez y queda en la caché

        var scene: SurfaceScene? = null
        val elapsed = measureTime { scene = builder.build(frame, surfaces, "s1", whole) }

        scene.shouldNotBeNull().meshes shouldBe emptyList()
        scene!!.outlines.size shouldBe 1
        elapsed.inWholeMilliseconds shouldBeLessThan 1_000L
    }
}
