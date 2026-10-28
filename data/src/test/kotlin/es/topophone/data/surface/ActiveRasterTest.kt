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
import es.topophone.core.model.surface.RasterTile
import es.topophone.core.model.surface.SurfaceGuide
import es.topophone.core.model.surface.SurfaceHeight
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** El ráster activo en memoria: bloques a demanda, Loading, precarga y cambio de superficie (S045/RF-17, RF-21, RF-22). */
@RunWith(AndroidJUnit4::class)
class ActiveRasterTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val repository = RoomSurfaceRepository(db)

    private val project = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @After
    fun close() {
        collectors.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        db.close()
    }

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }

    private fun fixture(name: String): ByteArray = File(root, "fixtures/raster/$name").readBytes()

    private val context = RasterProjectContext(ProjectCrs.ETRS89_UTM30, HeightSystem.ORTHOMETRIC)

    private fun provider(
        repo: SurfaceRepository = repository,
        projectContext: (String) -> Flow<RasterProjectContext?> = { flowOf(context) },
    ) = ActiveSurfaceProvider(repo, Dispatchers.Unconfined, projectContext = projectContext)

    private fun importRaster(
        name: String,
        at: Long = 1_000L,
        heightSystem: HeightSystem = HeightSystem.ORTHOMETRIC,
        source: CadSourceCrs = CadSourceCrs.Project,
        bytes: ByteArray = fixture(name),
    ): String = runBlocking {
        val importer = RasterImporter(repository, folder.newFolder(), clock = { at })
        val staged = (importer.stage({ ByteArrayInputStream(bytes) }) {} as RasterStageResult.Staged).staged
        val result = importer.import(
            project,
            ProjectCrs.ETRS89_UTM30,
            staged,
            name,
            RasterImportOptions(source, LinearUnit.METRE, heightSystem),
        )
        staged.close()
        (result as RasterImportResult.Imported).surfaceId
    }

    private suspend fun awaitUntil(condition: () -> Boolean) = kotlinx.coroutines.withTimeout(5_000) {
        while (!condition()) delay(10)
    }

    private class Query(val kind: String, val easting: Double, val northing: Double, val expected: Double?)

    private fun queries(file: String): List<Query> = File(root, "fixtures/raster/consultas.csv").readLines().drop(1).map { it.split(",") }
        .filter { it[0] == file && it[1] == "ORTHOMETRIC" && it[2] == "ORTHOMETRIC" }
        .map { Query(it[3], it[4].toDouble(), it[5].toDouble(), it[6].toDoubleOrNull()) }

    /** La guía del proyecto mientras el test sigue: si nadie la recoge, el proveedor cierra el ráster al dejar de observarlo. */
    private val jobs = CopyOnWriteArrayList<kotlinx.coroutines.Job>()

    private val collectors = kotlinx.coroutines.CoroutineScope(Dispatchers.Default + kotlinx.coroutines.SupervisorJob())

    private suspend fun guideOf(provider: ActiveSurfaceProvider): SurfaceGuide {
        val seen = CopyOnWriteArrayList<SurfaceGuide?>()
        jobs += collectors.launch { provider.guide(project).collect { seen += it } }
        awaitUntil { seen.lastOrNull() != null }
        return seen.last()!!
    }

    private suspend fun valueAfterLoading(guide: SurfaceGuide, e: Double, n: Double): SurfaceHeight {
        var result = guide.heightAt(e, n)
        awaitUntil {
            result = guide.heightAt(e, n)
            result != SurfaceHeight.Loading
        }
        return result
    }

    @Test
    fun `S045 RF-21 la primera consulta da cargando y despues el valor esperado`() = runBlocking<Unit> {
        importRaster("f32-sin-tiras-area.tif")
        val guide = guideOf(provider())
        val q = queries("f32-sin-tiras-area.tif").first { it.kind == "medio_camino" }

        guide.heightAt(q.easting, q.northing) shouldBe SurfaceHeight.Loading

        (valueAfterLoading(guide, q.easting, q.northing) as SurfaceHeight.Value).metres shouldBe (q.expected!! plusOrMinus 1e-6)
    }

    @Test
    fun `S045 RF-22 el hueco el borde y lo que esta lejos son fuera una vez cargado`() = runBlocking<Unit> {
        importRaster("f32-sin-tiras-area.tif")
        val guide = guideOf(provider())

        for (q in queries("f32-sin-tiras-area.tif")) {
            val result = valueAfterLoading(guide, q.easting, q.northing)
            if (q.expected ==
                null
            ) {
                result shouldBe SurfaceHeight.Outside
            } else {
                (result as SurfaceHeight.Value).metres shouldBe
                    (q.expected plusOrMinus 1e-6)
            }
        }
    }

    @Test
    fun `S045 RF-21 una consulta con el bloque en memoria no lee la base de datos`() = runBlocking<Unit> {
        importRaster("f32-sin-tiras-area.tif")
        val loads = AtomicInteger()
        val counting = object : SurfaceRepository by repository {
            override suspend fun rasterTile(surfaceId: String, tileColumn: Int, tileRow: Int): RasterTile? {
                loads.incrementAndGet()
                return repository.rasterTile(surfaceId, tileColumn, tileRow)
            }
        }
        val guide = guideOf(provider(counting))
        val q = queries("f32-sin-tiras-area.tif").first { it.kind == "centro_celda" }
        valueAfterLoading(guide, q.easting, q.northing)
        val before = loads.get()

        repeat(500) { guide.heightAt(q.easting, q.northing) }

        loads.get() shouldBe before
    }

    @Test
    fun `S045 RF-21 al cambiar de bloque la punta ya tiene precargados los vecinos`() = runBlocking<Unit> {
        val text = "ncols 300\nnrows 300\nxllcorner 440000\nyllcorner 4473000\ncellsize 10\nNODATA_value -9999\n" +
            (0 until 300).joinToString("\n") { row ->
                (0 until 300).joinToString(" ") { col -> (600 + col * 0.01 + row * 0.02).toString() }
            } +
            "\n"
        importRaster("ancho.asc", bytes = text.toByteArray())
        val loads = CopyOnWriteArrayList<Pair<Int, Int>>()
        val counting = object : SurfaceRepository by repository {
            override suspend fun rasterTile(surfaceId: String, tileColumn: Int, tileRow: Int): RasterTile? {
                loads += tileColumn to tileRow
                return repository.rasterTile(surfaceId, tileColumn, tileRow)
            }
        }
        val guide = guideOf(provider(counting))
        // La celda (100, 100) está en el bloque (0, 0) de 3 por 3 bloques.
        val e = 440005.0 + 100 * 10
        val n = 4473005.0 + 299 * 10 - 100 * 10
        valueAfterLoading(guide, e, n)

        awaitUntil { loads.toSet().containsAll(listOf(1 to 0, 0 to 1, 1 to 1)) }

        // Un paso al bloque de la derecha ya está en memoria: responde sin pasar por cargando.
        (guide.heightAt(440005.0 + 200 * 10, n) is SurfaceHeight.Value) shouldBe true
        loads.count { it == (1 to 0) } shouldBe 1
    }

    @Test
    fun `S045 RF-18 el proveedor sigue el raster activo y cierra el anterior al cambiar o desactivar`() = runBlocking<Unit> {
        val first = importRaster("f32-sin-tiras-area.tif")
        val second = importRaster("f32-lzw-teselas-area.tif", at = 2_000L)
        val provider = provider()
        val seen = CopyOnWriteArrayList<ActiveRaster?>()
        val job = collectors.launch { provider.activeRaster(project).collect { seen += it } }

        awaitUntil { seen.lastOrNull()?.surface?.id == first }
        val one = seen.last().shouldNotBeNull()
        one.isClosed shouldBe false

        repository.activate(project, second)
        awaitUntil { seen.lastOrNull()?.surface?.id == second }
        one.isClosed shouldBe true
        val two = seen.last().shouldNotBeNull()

        repository.activate(project, null)
        awaitUntil { seen.last() == null }
        two.isClosed shouldBe true
        job.cancel()
    }

    @Test
    fun `S045 RF-18 con un TIN activo no hay raster activo y con un raster activo no hay TIN`() = runBlocking<Unit> {
        val raster = importRaster("f32-sin-tiras-area.tif")
        val provider = provider()

        withTimeout(5_000) { provider.activeRaster(project).first { it != null } }!!.surface.id shouldBe raster
        withTimeout(5_000) { provider.active(project).first() }.shouldBeNull()
    }

    @Test
    fun `S045 RF-19 el desplazamiento vertical cambia sin recrear el raster y se suma a la cota`() = runBlocking<Unit> {
        importRaster("f32-sin-tiras-area.tif")
        val provider = provider()
        val q = queries("f32-sin-tiras-area.tif").first { it.kind == "centro_celda" }
        val seen = CopyOnWriteArrayList<SurfaceGuide?>()
        val job = collectors.launch { provider.guide(project).collect { seen += it } }

        awaitUntil { seen.lastOrNull() != null }
        val first = seen.last().shouldNotBeNull()
        valueAfterLoading(first, q.easting, q.northing)
        repository.setVerticalOffset(project, 0.3)
        awaitUntil { seen.last()?.verticalOffsetM == 0.3 }
        val moved = seen.last().shouldNotBeNull()

        moved.surface.id shouldBe first.surface.id
        (valueAfterLoading(moved, q.easting, q.northing) as SurfaceHeight.Value).metres shouldBe (q.expected!! plusOrMinus 1e-6)
        (moved.designAt(q.easting, q.northing) as SurfaceHeight.Value).metres shouldBe (q.expected + 0.3 plusOrMinus 1e-6)
        job.cancel()
    }

    @Test
    fun `S045 RF-10 el sistema de alturas del proyecto se pide al proveedor y la cota se convierte`() = runBlocking<Unit> {
        importRaster("f32-sin-tiras-area.tif", heightSystem = HeightSystem.ORTHOMETRIC)
        val ellipsoidal = provider(projectContext = { flowOf(RasterProjectContext(ProjectCrs.ETRS89_UTM30, HeightSystem.ELLIPSOIDAL)) })
        val q = queries("f32-sin-tiras-area.tif").first { it.kind == "centro_celda" }

        val guide = guideOf(ellipsoidal)
        val value = (valueAfterLoading(guide, q.easting, q.northing) as SurfaceHeight.Value).metres

        // La ondulación N de Madrid ronda los 50 m: h = H + N.
        val undulation = value - q.expected!!
        (undulation > 40.0 && undulation < 60.0) shouldBe true
    }

    @Test
    fun `S045 RF-16 sin superficie activa no hay raster ni guia`() = runBlocking<Unit> {
        val provider = provider()

        withTimeout(5_000) { provider.activeRaster(project).first() }.shouldBeNull()
        withTimeout(5_000) { provider.guide(project).first() }.shouldBeNull()
    }

    @Test
    fun `S045 RF-21 sin el contexto del proyecto el raster no tiene guia`() = runBlocking<Unit> {
        importRaster("f32-sin-tiras-area.tif")
        val provider = provider(projectContext = { flowOf(null) })

        withTimeout(5_000) { provider.guide(project).first() }.shouldBeNull()
    }
}
