package es.topophone.app.ui.surface

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.log.RecordingLog
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.app.ui.points.ImportSource
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.log.LogLevel
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.map.RoomMapViewportRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.data.surface.RoomSurfaceRepository
import es.topophone.data.surface.SurfaceImportFailure
import es.topophone.data.surface.SurfaceImporter
import es.topophone.data.surface.SurfaceRepository
import es.topophone.data.surface.SurfaceTileWrite
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Importar un LandXML, parte 2: importar con progreso, cancelar, fallar, el resumen y el log (S042/RF-10, RF-11, RF-13, RF-17). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SurfaceImportRunTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val surfaces = RoomSurfaceRepository(db)
    private val viewports = RoomMapViewportRepository(db)
    private val points = FakePointRepository()
    private val dispatcher = UnconfinedTestDispatcher()
    private val log = RecordingLog()
    private val projectId = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun open(source: ImportSource, repository: SurfaceRepository = surfaces): SurfaceImportViewModel {
        val vm = SurfaceImportViewModel(surfaces, SurfaceImporter(repository), projects, points, viewports, dispatcher, log)
        vm.start(projectId, source)
        vm.settled()
        return vm
    }

    private fun open(name: String, repository: SurfaceRepository = surfaces) = open(SurfaceTestFiles.source(name), repository)

    private fun done(vm: SurfaceImportViewModel): SurfaceImportUiState = runBlocking {
        withTimeout(10_000) { vm.state.first { it.stage != SurfaceImportStage.IMPORTING && it.stage != SurfaceImportStage.LOADING } }
    }

    private fun count(table: String): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
        it.moveToFirst()
        it.getInt(0)
    }

    @Test
    fun `S042 RF-13 importar da el resumen y deja la superficie activa en el proyecto`() {
        val vm = open("dos-superficies.xml")

        vm.import()
        val state = done(vm)

        state.stage shouldBe SurfaceImportStage.RESULT
        val outcome = state.result.shouldNotBeNull()
        outcome.summary.name shouldBe "terreno"
        outcome.summary.summary.vertexCount shouldBe 42
        outcome.summary.summary.triangleCount shouldBe 58
        outcome.summary.summary.holeCount shouldBe 2
        outcome.summary.activated shouldBe true
        runBlocking { surfaces.surfaces(projectId).first() }.single().id shouldBe outcome.surfaceId
        runBlocking { surfaces.activeSurfaceId(projectId).first() } shouldBe outcome.surfaceId
    }

    @Test
    fun `S042 RF-5 se importa la superficie elegida y la segunda no pasa a ser la activa`() {
        val first = open("dos-superficies.xml")
        first.import()
        done(first).stage shouldBe SurfaceImportStage.RESULT
        val second = open("dos-superficies.xml")
        second.confirmDuplicate()
        second.select(1)

        second.import()
        val state = done(second)

        state.result.shouldNotBeNull().summary.name shouldBe "rasante"
        state.result.shouldNotBeNull().summary.activated shouldBe false
        runBlocking { surfaces.activeSurfaceId(projectId).first() } shouldBe first.state.value.result.shouldNotBeNull().surfaceId
    }

    @Test
    fun `S042 RF-10 el progreso avisa de los bytes leidos mientras se importa`() {
        val vm = open("dos-superficies.xml")

        vm.import()
        val state = done(vm)

        state.importBytes shouldBe SurfaceTestFiles.bytes("dos-superficies.xml").size.toLong()
    }

    @Test
    fun `S042 RF-10 la importacion no empieza si algo la impide`() {
        val vm = open("sin-superficies.xml")

        vm.import()

        vm.state.value.stage shouldBe SurfaceImportStage.PROBLEM
        count("surfaces") shouldBe 0
    }

    @Test
    fun `S042 RF-11 cancelar durante la lectura no deja nada`() {
        lateinit var vm: SurfaceImportViewModel
        val data = SurfaceTestFiles.bytes("dos-superficies.xml")
        var opens = 0
        val source = ImportSource("dos-superficies.xml", data.size.toLong()) {
            opens++
            if (opens <= 1) {
                ByteArrayInputStream(data) // la exploración
            } else {
                object : ByteArrayInputStream(data) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        vm.cancel() // el usuario pulsa «Cancelar» con la importación a medias
                        return super.read(b, off, len)
                    }
                }
            }
        }
        vm = open(source)

        vm.import()
        val state = done(vm)

        state.stage shouldBe SurfaceImportStage.CONFIG
        state.cancelled shouldBe true
        state.result.shouldBeNull()
        count("surfaces") shouldBe 0
        count("surface_tiles") shouldBe 0
        log.entries.last { it.second == LogCategory.APP }.third shouldContain "cancelada"
    }

    @Test
    fun `S042 RF-11 un fallo de escritura vuelve a la configuracion sin guardar nada`() {
        val failing = object : SurfaceRepository by surfaces {
            override suspend fun addTiles(surfaceId: String, tiles: List<SurfaceTileWrite>) = throw IOException("disco lleno")
        }
        val vm = open("dos-superficies.xml", failing)

        vm.import()
        val state = done(vm)

        state.stage shouldBe SurfaceImportStage.CONFIG
        state.failure shouldBe SurfaceImportFailure.WRITE_ERROR
        state.cancelled shouldBe false
        count("surfaces") shouldBe 0
        log.entries.last { it.first == LogLevel.ERROR }.third shouldContain "fallida"
    }

    @Test
    fun `S042 RF-11 tras un fallo se puede volver a intentar`() {
        var fail = true
        val flaky = object : SurfaceRepository by surfaces {
            override suspend fun addTiles(surfaceId: String, tiles: List<SurfaceTileWrite>) {
                if (fail) throw IOException("disco lleno")
                surfaces.addTiles(surfaceId, tiles)
            }
        }
        val vm = open("dos-superficies.xml", flaky)
        vm.import()
        done(vm).failure shouldBe SurfaceImportFailure.WRITE_ERROR

        fail = false
        vm.import()

        done(vm).stage shouldBe SurfaceImportStage.RESULT
        count("surfaces") shouldBe 1
    }

    @Test
    fun `S042 RF-13 el log lleva los recuentos y no el nombre del fichero ni coordenadas`() {
        val vm = open("dos-superficies.xml")

        vm.import()
        done(vm)

        val line = log.entries.last { it.second == LogCategory.APP }.third
        line shouldContain "42 puntos"
        line shouldContain "58 triángulos"
        line shouldContain "2 huecos"
        line shouldNotContain "dos-superficies"
        line shouldNotContain "4474257"
    }
}
