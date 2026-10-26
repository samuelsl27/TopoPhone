package es.topophone.data.surface

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.landxml.LandXmlReader
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.surface.SurfaceExtent
import es.topophone.core.model.surface.SurfaceHeight
import es.topophone.core.model.surface.SurfaceTileCodec
import es.topophone.core.survey.TinTiler
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** La superficie activa en memoria: índice de teselas, caché, Loading y cambio de superficie (S042/RF-16, RF-22, RF-23, RF-27). */
@RunWith(AndroidJUnit4::class)
class ActiveSurfaceTest {

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
    fun close() = db.close()

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }

    private class Query(val kind: String, val easting: Double, val northing: Double, val height: Double?)

    private val queries: List<Query> = File(root, "fixtures/superficies/consultas.csv").readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .drop(1)
        .map { line ->
            val c = line.split(",")
            Query(c[0], c[1].toDouble(), c[2].toDouble(), c.getOrNull(3)?.takeIf { it.isNotEmpty() }?.toDouble())
        }

    /** Escribe el terreno de los ejemplos repartido en muchas teselas (hasta [maxTriangles] triángulos cada una). */
    private fun write(id: String, maxTriangles: Int = 6, at: Long = 1_000L) = runBlocking {
        val surface = File(root, "fixtures/superficies/dos-superficies.xml").inputStream().use { LandXmlReader.read(it, 0)!! }
        val cells = TinTiler.tile(surface.easting, surface.northing, surface.height, surface.triangles, maxTriangles)
        repository.begin(SurfaceDraft(id, project, "terreno", "obra.xml", at, CadSourceCrs.Project, LinearUnit.METRE))
        repository.addTiles(id, cells.map { SurfaceTileWrite(it.region, it.tile.triangleCount, SurfaceTileCodec.encode(it.tile)) })
        repository.finish(id, SurfaceSummary(SurfaceExtent(440290.0, 440412.0, 4474256.0, 4474360.0), 600.0, 608.7, 42, 58, 2))
        cells.size
    }

    /** Pregunta hasta que la respuesta deja de ser Loading: las teselas se leen de Room, que contesta desde otro hilo. */
    private fun settled(surface: ActiveSurface, easting: Double, northing: Double): SurfaceHeight = runBlocking {
        withTimeout(5_000) {
            var height = surface.heightAt(easting, northing)
            while (height == SurfaceHeight.Loading) {
                delay(5)
                height = surface.heightAt(easting, northing)
            }
            height
        }
    }

    private fun active(
        id: String = "s1",
        cacheSize: Int = ActiveSurface.CACHE_TILES,
        loader: suspend (Long) -> es.topophone.core.model.surface.SurfaceTile? = { repository.tile(it) },
    ): ActiveSurface = runBlocking {
        val surface = repository.surfaces(project).first().first { it.id == id }
        ActiveSurface(surface, repository.cells(id), loader, Dispatchers.Unconfined, cacheSize)
    }

    @Test
    fun `S042 RF-22 la primera consulta de una tesela sin cargar es Loading y despues da la cota`() {
        write("s1")
        val gate = CompletableDeferred<Unit>()
        val loads = AtomicInteger()
        val surface = active(loader = {
            loads.incrementAndGet()
            gate.await()
            repository.tile(it)
        })
        val inside = queries.first { it.kind == "dentro" }

        surface.heightAt(inside.easting, inside.northing) shouldBe SurfaceHeight.Loading
        (loads.get() > 0) shouldBe true
        gate.complete(Unit)

        val value = settled(surface, inside.easting, inside.northing)
        value.shouldBeInstanceOf<SurfaceHeight.Value>().metres shouldBe (inside.height!! plusOrMinus 1e-3)
    }

    @Test
    fun `S042 RF-23 una posicion sin cargar nunca se presenta como fuera de la superficie`() {
        write("s1")
        val gate = CompletableDeferred<Unit>()
        val surface = active(loader = {
            gate.await()
            repository.tile(it)
        })

        for (q in queries.filter { it.kind == "dentro" || it.kind == "hueco" }) {
            surface.heightAt(q.easting, q.northing) shouldBe SurfaceHeight.Loading
        }
    }

    @Test
    fun `S042 RF-23 fuera del contorno es Outside al momento y sin leer ninguna tesela`() {
        write("s1")
        val loads = AtomicInteger()
        val surface = active(loader = {
            loads.incrementAndGet()
            repository.tile(it)
        })

        for (q in queries.filter { it.kind == "fuera" }) surface.heightAt(q.easting, q.northing) shouldBe SurfaceHeight.Outside
        loads.get() shouldBe 0
    }

    @Test
    fun `S042 RF-22 y RF-23 con las teselas en memoria cada consulta da la cota o Outside como la referencia`() {
        write("s1")
        val surface = active()

        for (q in queries) {
            val got = settled(surface, q.easting, q.northing)
            if (q.height == null) {
                got shouldBe SurfaceHeight.Outside
            } else {
                got.shouldBeInstanceOf<SurfaceHeight.Value>().metres shouldBe (q.height plusOrMinus 1e-3)
            }
        }
    }

    @Test
    fun `S042 RF-22 al consultar se piden tambien las teselas vecinas y la cache tiene un maximo`() {
        val tiles = write("s1")
        val loads = AtomicInteger()
        val surface = active(cacheSize = 4, loader = {
            loads.incrementAndGet()
            repository.tile(it)
        })
        val first = queries.first { it.kind == "dentro" }

        surface.heightAt(first.easting, first.northing)

        (tiles > 4) shouldBe true
        (loads.get() > 1) shouldBe true
        for (q in queries) settled(surface, q.easting, q.northing)
        (surface.cachedTiles <= 4) shouldBe true
        // Con la cache llena se sigue respondiendo bien: lo expulsado se vuelve a leer.
        val q = queries.first { it.kind == "dentro" }
        settled(surface, q.easting, q.northing).shouldBeInstanceOf<SurfaceHeight.Value>().metres shouldBe (q.height!! plusOrMinus 1e-3)
    }

    @Test
    fun `S042 RF-22 una tesela ilegible no deja la consulta cargando para siempre ni tumba la app`() {
        write("s1", maxTriangles = 30)
        val loadsOf = java.util.concurrent.ConcurrentHashMap<Long, AtomicInteger>()
        val surface = active(cacheSize = 256, loader = {
            loadsOf.getOrPut(it) { AtomicInteger() }.incrementAndGet()
            error("bloque ilegible")
        })
        val inside = queries.first { it.kind == "dentro" }

        surface.heightAt(inside.easting, inside.northing)

        settled(surface, inside.easting, inside.northing) shouldBe SurfaceHeight.Outside
        settled(surface, inside.easting, inside.northing) shouldBe SurfaceHeight.Outside
        loadsOf.mapValues { it.value.get() }.filter { it.value != 1 } shouldBe emptyMap()
    }

    @Test
    fun `S042 RF-27 cerrar la superficie vacia la cache y no lee nada mas`() {
        write("s1")
        val loads = AtomicInteger()
        val surface = active(loader = {
            loads.incrementAndGet()
            repository.tile(it)
        })
        val q = queries.first { it.kind == "dentro" }
        surface.heightAt(q.easting, q.northing)
        val before = loads.get()

        surface.close()

        surface.isClosed shouldBe true
        surface.cachedTiles shouldBe 0
        surface.heightAt(q.easting, q.northing) shouldBe SurfaceHeight.Loading
        loads.get() shouldBe before
    }

    private suspend fun awaitUntil(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }

    @Test
    fun `S042 RF-16 y RF-27 el proveedor sigue la superficie activa y cierra la anterior al cambiar o desactivar`() = runBlocking<Unit> {
        write("s1")
        write("s2", at = 2_000L)
        val provider = ActiveSurfaceProvider(repository, Dispatchers.Unconfined)
        val seen = CopyOnWriteArrayList<ActiveSurface?>()
        val job = launch(Dispatchers.Default) { provider.active(project).collect { seen += it } }

        awaitUntil { seen.isNotEmpty() && seen.last()?.surface?.id == "s1" }
        val first = seen.last().shouldNotBeNull()
        first.isClosed shouldBe false

        repository.activate(project, "s2")
        awaitUntil { seen.last()?.surface?.id == "s2" }
        first.isClosed shouldBe true
        val second = seen.last().shouldNotBeNull()

        repository.activate(project, null)
        awaitUntil { seen.last() == null }
        second.isClosed shouldBe true

        repository.activate(project, "s1")
        awaitUntil { seen.last()?.surface?.id == "s1" }
        repository.delete("s1")
        awaitUntil { seen.last() == null }
        job.cancel()
    }

    @Test
    fun `S042 RF-19 el desplazamiento vertical cambia sin recrear la superficie y se suma a la cota`() = runBlocking<Unit> {
        write("s1")
        val provider = ActiveSurfaceProvider(repository, Dispatchers.Unconfined)
        val q = queries.first { it.kind == "dentro" }
        val seen = CopyOnWriteArrayList<es.topophone.core.model.surface.SurfaceGuide?>()
        val job = launch(Dispatchers.Default) { provider.guide(project).collect { seen += it } }

        awaitUntil { seen.lastOrNull() != null }
        val first = seen.last().shouldNotBeNull()
        first.verticalOffsetM shouldBe 0.0
        first.heightAt(q.easting, q.northing)
        delay(100)
        val plain = first.designAt(q.easting, q.northing).shouldBeInstanceOf<SurfaceHeight.Value>().metres

        repository.setVerticalOffset(project, 0.3)
        awaitUntil { seen.last()?.verticalOffsetM == 0.3 }
        val moved = seen.last().shouldNotBeNull()

        moved.surface.id shouldBe first.surface.id
        moved.designAt(q.easting, q.northing).shouldBeInstanceOf<SurfaceHeight.Value>().metres shouldBe (plain + 0.3 plusOrMinus 1e-9)
        moved.heightAt(q.easting, q.northing).shouldBeInstanceOf<SurfaceHeight.Value>().metres shouldBe (plain plusOrMinus 1e-9)
        job.cancel()
    }

    @Test
    fun `S042 RF-16 sin superficie activa no hay guia`() = runBlocking<Unit> {
        val provider = ActiveSurfaceProvider(repository, Dispatchers.Unconfined)

        withTimeout(5_000) { provider.guide(project).first() }.shouldBeNull()
    }
}
