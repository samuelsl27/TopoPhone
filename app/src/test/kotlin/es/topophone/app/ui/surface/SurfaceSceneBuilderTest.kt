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
import es.topophone.core.model.surface.ElevationRamp
import es.topophone.core.model.surface.SurfaceExtent
import es.topophone.core.model.surface.SurfaceTileCodec
import es.topophone.core.survey.CadPlacement
import es.topophone.core.survey.TinTiler
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.data.surface.RasterImportOptions
import es.topophone.data.surface.RasterImportResult
import es.topophone.data.surface.RasterImporter
import es.topophone.data.surface.RasterStageResult
import es.topophone.data.surface.RoomSurfaceRepository
import es.topophone.data.surface.SurfaceDraft
import es.topophone.data.surface.SurfaceSummary
import es.topophone.data.surface.SurfaceTileWrite
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** La escena de las superficies visibles al pararse la cámara (S061/RF-6 a RF-10, RF-13, plan A3 a A5). */
@RunWith(AndroidJUnit4::class)
class SurfaceSceneBuilderTest {

    @get:Rule
    val folder = TemporaryFolder()

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

    /** Un TIN de [nx] × [ny] celdas de 2 m desde ([e0], [n0]) con cotas de [z0] a [z0] + 1, sin la celda central si [hole]. */
    private fun writeTin(id: String, nx: Int, ny: Int, e0: Double, n0: Double, z0: Double, hole: Boolean = false) = runBlocking {
        val w = nx + 1
        val e = DoubleArray(w * (ny + 1)) { e0 + (it % w) * 2.0 }
        val n = DoubleArray(w * (ny + 1)) { n0 + (it / w) * 2.0 }
        val z = DoubleArray(w * (ny + 1)) { z0 + (it % w).toDouble() / nx }
        val t = buildList {
            for (j in 0 until ny) {
                for (i in 0 until nx) {
                    if (hole && i == nx / 2 && j == ny / 2) continue
                    val a = j * w + i
                    addAll(listOf(a, a + 1, a + w + 1, a, a + w + 1, a + w))
                }
            }
        }.toIntArray()
        repository.begin(SurfaceDraft(id, project, id, "$id.xml", 1L, CadSourceCrs.Project, LinearUnit.METRE))
        val cells = TinTiler.tile(e, n, z, t)
        repository.addTiles(id, cells.map { SurfaceTileWrite(it.region, it.tile.triangleCount, SurfaceTileCodec.encode(it.tile)) })
        val extent = SurfaceExtent(e0, e0 + nx * 2.0, n0, n0 + ny * 2.0)
        repository.finish(id, SurfaceSummary(extent, z0, z0 + 1.0, e.size, t.size / 3, 0))
        repository.saveOutline(id, es.topophone.core.survey.TinOutline.rings(e, n, t))
    }

    /** La vista que se ve: el recuadro en latitud y longitud de un recuadro del proyecto. */
    private fun viewOf(minE: Double, maxE: Double, minN: Double, maxN: Double): GeoBox {
        val sw = CadPlacement.geographic(ProjectCrs.ETRS89_UTM30, minE, minN)!!
        val ne = CadPlacement.geographic(ProjectCrs.ETRS89_UTM30, maxE, maxN)!!
        return GeoBox(sw.first, sw.second, ne.first, ne.second)
    }

    private suspend fun visible() = repository.surfaces(project).first().filter { it.visible }

    @Test
    fun `S061 RF-7 RF-13 un TIN pequeno da su malla y su contorno con el hueco`() = runBlocking<Unit> {
        writeTin("s1", 20, 20, 440_000.0, 4_474_000.0, 600.0, hole = true)

        val scene = SurfaceSceneBuilder(repository).build(frame, visible(), "s1", viewOf(439_990.0, 440_050.0, 4_473_990.0, 4_474_050.0))
            .shouldNotBeNull()

        scene.meshes.single().count shouldBe 798
        scene.outlines.size shouldBe 2 // el exterior y el hueco
        scene.outlines.all { it.active } shouldBe true
        scene.ramp.shouldNotBeNull()
    }

    @Test
    fun `S061 RF-6 con demasiados triangulos en la vista solo el contorno y sin rampa`() = runBlocking<Unit> {
        writeTin("s1", 120, 100, 440_000.0, 4_474_000.0, 600.0) // 24.000 triángulos

        val scene = SurfaceSceneBuilder(repository).build(frame, visible(), null, viewOf(439_990.0, 440_250.0, 4_473_990.0, 4_474_210.0))
            .shouldNotBeNull()

        scene.meshes shouldBe emptyList()
        scene.outlines.size shouldBe 1
        scene.outlines.single().active shouldBe false
        scene.ramp.shouldBeNull()
    }

    @Test
    fun `S061 RF-10 la rampa es comun a las superficies visibles`() = runBlocking<Unit> {
        writeTin("baja", 4, 4, 440_000.0, 4_474_000.0, 600.0)
        writeTin("alta", 4, 4, 440_020.0, 4_474_000.0, 699.0)

        val scene = SurfaceSceneBuilder(repository).build(frame, visible(), null, viewOf(439_990.0, 440_040.0, 4_473_990.0, 4_474_020.0))
            .shouldNotBeNull()

        val ramp = scene.ramp.shouldNotBeNull()
        ramp.minM shouldBe 600.0
        ramp.maxM shouldBe 700.0
        scene.meshes.size shouldBe 2
        // Las superficies van por fecha y nombre: «alta» y después «baja». Las cotas medias de los triángulos de «baja» son
        // 600 + (3i + 1) / 12 y 600 + (3i + 2) / 12, y sus colores son los de esas cotas en la rampa común, no en una de 600 a 601.
        fun z(i: Int) = 600.0 + i.toDouble() / 4
        val ramp100 = ElevationRamp(600.0, 700.0)
        val expected = (0..3).flatMap { i ->
            listOf(
                ramp100.argb((z(i) + z(i + 1) + z(i)) / 3),
                ramp100.argb(
                    (z(i) + z(i + 1) + z(i + 1)) / 3,
                ),
            )
        }
        scene.meshes[1].argb.toSet() shouldBe expected.toSet()
    }

    @Test
    fun `S061 RF-6 una superficie oculta no se dibuja`() = runBlocking<Unit> {
        writeTin("s1", 4, 4, 440_000.0, 4_474_000.0, 600.0)
        repository.setVisible("s1", false)

        SurfaceSceneBuilder(repository).build(frame, visible(), null, viewOf(439_990.0, 440_020.0, 4_473_990.0, 4_474_020.0)).shouldBeNull()
    }

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }

    @Test
    fun `S061 RF-9 un raster con pocas celdas da su imagen y su recuadro`() = runBlocking<Unit> {
        val importer = RasterImporter(repository, folder.newFolder(), clock = { 2_000L })
        val bytes = File(root, "fixtures/raster/f32-sin-tiras-area.tif").readBytes()
        val staged = (importer.stage({ ByteArrayInputStream(bytes) }) {} as RasterStageResult.Staged).staged
        val result = importer.import(
            project,
            ProjectCrs.ETRS89_UTM30,
            staged,
            "mdt.tif",
            RasterImportOptions(CadSourceCrs.Project, LinearUnit.METRE, HeightSystem.ORTHOMETRIC),
        )
        staged.close()
        result.shouldBeInstanceOf<RasterImportResult.Imported>()
        val raster = visible().single()
        val x = raster.extent

        val scene = SurfaceSceneBuilder(repository)
            .build(frame, visible(), null, viewOf(x.minEasting - 10, x.maxEasting + 10, x.minNorthing - 10, x.maxNorthing + 10))
            .shouldNotBeNull()

        val image = scene.images.single()
        image.width shouldBe raster.raster!!.columns
        image.height shouldBe raster.raster!!.rows
        image.argb.any { it != 0 } shouldBe true
        scene.outlines.single().vertexCount shouldBe 128
        scene.ramp.shouldNotBeNull()
    }
}
