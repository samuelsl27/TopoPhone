package es.topophone.data.surface

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.surface.SurfaceExtent
import es.topophone.core.model.surface.SurfaceHeight
import es.topophone.core.model.surface.SurfaceTileCodec
import es.topophone.core.survey.TinTiler
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.doubles.plusOrMinus
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

/**
 * La cota de una superficie que no está activa, la de la tarjeta del mapa (S061/RF-16, RF-18): un TIN plano frente a su ecuación y un
 * ráster frente a `fixtures/raster/consultas.csv`, calculado aparte (≤ 1 mm).
 */
@RunWith(AndroidJUnit4::class)
class SurfaceProbeTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val repository = RoomSurfaceRepository(db)
    private val probe = SurfaceProbe(repository)
    private val project = runBlocking {
        RoomProjectRepository(db).create(ProjectDefaults().toDraft().copy(name = "Obra")).shouldBeInstanceOf<SaveResult.Saved>().id
    }
    private val context = RasterProjectContext(ProjectCrs.ETRS89_UTM30, HeightSystem.ORTHOMETRIC)

    @After
    fun close() = db.close()

    private val e0 = 440_000.0
    private val n0 = 4_474_000.0

    /** Cota de un plano inclinado: la interpolación en cualquier triángulo de la malla la da exacta. */
    private fun plane(e: Double, n: Double) = 600.0 + 0.005 * (e - e0) + 0.01 * (n - n0)

    /** Un TIN de 20 × 20 celdas de 2 m sobre el plano, sin la celda (10, 10), que no es la activa del proyecto. */
    private fun writeTin(): String = runBlocking {
        val w = 21
        val e = DoubleArray(w * w) { e0 + (it % w) * 2.0 }
        val n = DoubleArray(w * w) { n0 + (it / w) * 2.0 }
        val z = DoubleArray(w * w) { plane(e[it], n[it]) }
        val t = buildList {
            for (j in 0 until 20) {
                for (i in 0 until 20) {
                    if (i == 10 && j == 10) continue
                    val a = j * w + i
                    addAll(listOf(a, a + 1, a + w + 1, a, a + w + 1, a + w))
                }
            }
        }.toIntArray()
        repository.begin(SurfaceDraft("tin", project, "plano", "plano.xml", 1L, CadSourceCrs.Project, LinearUnit.METRE))
        val cells = TinTiler.tile(e, n, z, t, maxTriangles = 100)
        repository.addTiles("tin", cells.map { SurfaceTileWrite(it.region, it.tile.triangleCount, SurfaceTileCodec.encode(it.tile)) })
        repository.finish("tin", SurfaceSummary(SurfaceExtent(e0, e0 + 40.0, n0, n0 + 40.0), 600.0, 600.6, w * w, t.size / 3, 0))
        repository.activate(project, null)
        "tin"
    }

    private suspend fun surface(id: String) = repository.surfaces(project).first().single { it.id == id }

    @Test
    fun `S061 RF-16 la cota de un TIN que no es el activo es la del plano`() = runBlocking<Unit> {
        val tin = surface(writeTin())

        for ((e, n) in listOf(1.3 to 0.7, 17.25 to 33.9, 39.99 to 0.01, 2.0 to 2.0, 30.0 to 12.5)) {
            val height = probe.heightAt(tin, e0 + e, n0 + n, context).shouldBeInstanceOf<SurfaceHeight.Value>()
            height.metres shouldBe (plane(e0 + e, n0 + n) plusOrMinus 1e-9)
        }
    }

    @Test
    fun `S061 RF-18 en el hueco o fuera del TIN no hay cota`() = runBlocking<Unit> {
        val tin = surface(writeTin())

        probe.heightAt(tin, e0 + 21.0, n0 + 21.0, context) shouldBe SurfaceHeight.Outside // la celda (10, 10)
        probe.heightAt(tin, e0 - 5.0, n0 + 5.0, context) shouldBe SurfaceHeight.Outside
    }

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }

    private fun importRaster(name: String): String = runBlocking {
        val importer = RasterImporter(repository, folder.newFolder(), clock = { 2_000L })
        val bytes = File(root, "fixtures/raster/$name").readBytes()
        val staged = (importer.stage({ ByteArrayInputStream(bytes) }) {} as RasterStageResult.Staged).staged
        val result = importer.import(
            project,
            ProjectCrs.ETRS89_UTM30,
            staged,
            name,
            RasterImportOptions(CadSourceCrs.Project, LinearUnit.METRE, HeightSystem.ORTHOMETRIC),
        )
        staged.close()
        (result as RasterImportResult.Imported).surfaceId
    }

    @Test
    fun `S061 RF-16 RF-18 la cota de un raster es la de la referencia y en el hueco no hay`() = runBlocking<Unit> {
        val file = "f32-sin-tiras-area.tif"
        val raster = surface(importRaster(file))
        val queries = File(root, "fixtures/raster/consultas.csv").readLines().drop(1).map { it.split(",") }
            .filter { it[0] == file && it[1] == "ORTHOMETRIC" && it[2] == "ORTHOMETRIC" }

        for (q in queries) {
            val height = probe.heightAt(raster, q[4].toDouble(), q[5].toDouble(), context)
            val expected = q[6].toDoubleOrNull()
            if (expected == null) {
                height shouldBe SurfaceHeight.Outside
            } else {
                height.shouldBeInstanceOf<SurfaceHeight.Value>().metres shouldBe (expected plusOrMinus 1e-3)
            }
        }
    }
}
