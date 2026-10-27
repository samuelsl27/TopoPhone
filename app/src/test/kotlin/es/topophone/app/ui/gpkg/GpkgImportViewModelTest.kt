package es.topophone.app.ui.gpkg

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.gpkg.GpkgFileKind
import es.topophone.core.model.cad.GpkgLayerKind
import es.topophone.core.model.project.CentralPoint
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.map.RoomMapViewportRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Importar un GeoPackage, parte 1: reconocer el fichero y listar sus capas para marcarlas (S048/RF-1 a RF-7). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class GpkgImportViewModelTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val viewports = RoomMapViewportRepository(db)
    private val dispatcher = UnconfinedTestDispatcher()
    private val projectId = runBlocking {
        projects.create(
            ProjectDefaults().toDraft().copy(
                name = "Calle Mayor 12",
                crs = ProjectCrs.ETRS89_UTM30,
                centralPoint = CentralPoint(40.4168, -3.7038),
            ),
        )
            .shouldBeInstanceOf<SaveResult.Saved>().id
    }
    private val opened = mutableListOf<GpkgImportViewModel>()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private val cache: File by lazy { folder.newFolder("cache") }

    private fun viewModel(): GpkgImportViewModel = gpkgViewModel(cache, projects, viewports, dispatcher)

    private fun open(name: String, size: Long? = GpkgTestFiles.bytes(name).size.toLong()): GpkgImportViewModel {
        val viewModel = viewModel()
        opened += viewModel
        viewModel.start(projectId, GpkgTestFiles.source(name, size))
        return viewModel
    }

    private fun problemOf(name: String, size: Long? = GpkgTestFiles.bytes(name).size.toLong()): GpkgFileProblem? {
        val state = open(name, size).settled()
        state.stage shouldBe GpkgImportStage.PROBLEM
        return state.problem
    }

    @Test
    fun `S048 RF-1 abre el fichero y muestra el nombre y el proyecto`() {
        val state = open("puntos-25830.gpkg").settled()

        state.stage shouldBe GpkgImportStage.CONFIG
        state.fileName shouldBe "puntos-25830.gpkg"
        state.projectName shouldBe "Calle Mayor 12"
        state.projectCrs shouldBe ProjectCrs.ETRS89_UTM30
    }

    @Test
    fun `S048 RF-2 se reconoce por su contenido aunque tenga otra extension`() {
        val state = open("lineas-con-otra-extension.dat").settled()

        state.stage shouldBe GpkgImportStage.CONFIG
        state.layers.single().info.table shouldBe "ejes"
    }

    @Test
    fun `S048 RF-3 un texto, un SQLite comun, un fichero vacio o danado no abren la pantalla`() {
        problemOf("texto.gpkg") shouldBe GpkgFileProblem.NOT_GEOPACKAGE
        problemOf("sqlite-comun.gpkg") shouldBe GpkgFileProblem.NOT_GEOPACKAGE
        problemOf("vacio.gpkg") shouldBe GpkgFileProblem.EMPTY
        problemOf("danado.gpkg") shouldBe GpkgFileProblem.DAMAGED
    }

    @Test
    fun `S048 RF-3 un GeoPackage sin ninguna capa con geometria se rechaza`() {
        problemOf("sin-capas.gpkg") shouldBe GpkgFileProblem.NO_LAYERS
    }

    @Test
    fun `S048 RF-4 mas de 100 MB no se abre`() {
        problemOf("puntos-25830.gpkg", size = GpkgFileKind.MAX_BYTES + 1) shouldBe GpkgFileProblem.TOO_BIG
    }

    @Test
    fun `S048 RF-4 con el tamano desconocido se abre igual`() {
        open("puntos-25830.gpkg", size = null).settled().stage shouldBe GpkgImportStage.CONFIG
    }

    @Test
    fun `S048 RF-1 un proyecto que no existe no deja abrir el fichero`() {
        val viewModel = viewModel()
        viewModel.start("no-existe", GpkgTestFiles.source("puntos-25830.gpkg"))

        val state = viewModel.settled()

        state.stage shouldBe GpkgImportStage.PROBLEM
        state.problem shouldBe GpkgFileProblem.UNREADABLE
    }

    @Test
    fun `S048 RF-5 lista las capas con su tipo, su numero de objetos y todas marcadas`() {
        val state = open("poligonos-25830.gpkg").settled()

        state.layers.map { it.info.table } shouldBe listOf("parcelas", "manzanas")
        state.layers.map { it.info.kind } shouldBe listOf(GpkgLayerKind.POLYGONS, GpkgLayerKind.POLYGONS)
        state.layers.map { it.info.featureCount } shouldBe listOf(2L, 2L)
        state.layers.all { it.marked } shouldBe true
        state.canImport shouldBe true
    }

    @Test
    fun `S048 RF-5 se puede marcar y desmarcar cada capa y sin ninguna no se importa`() {
        val viewModel = open("multiples-25830.gpkg")
        viewModel.settled()

        viewModel.setMarked("mixta", false)
        viewModel.state.value.markedLayers.map { it.info.table } shouldBe listOf("multipuntos", "multilineas")
        viewModel.settled().canImport shouldBe true

        viewModel.setMarked("multipuntos", false)
        viewModel.setMarked("multilineas", false)
        viewModel.state.value.markedLayers shouldBe emptyList()
        viewModel.settled().canImport shouldBe false

        viewModel.setMarked("mixta", true)
        viewModel.state.value.markedLayers.map { it.info.table } shouldBe listOf("mixta")
    }

    @Test
    fun `S048 RF-6 avisa de las tablas sin geometria o de teselas que deja fuera`() {
        val state = open("con-ajenas-25830.gpkg").settled()

        state.layers.map { it.info.table } shouldBe listOf("puntos")
        state.leftOutTables shouldBe listOf("censo", "teselas", "notas")
        open("puntos-25830.gpkg").settled().leftOutTables shouldBe emptyList()
    }

    @Test
    fun `S048 RF-7 un fichero en modo WAL sin su wal se lee consolidado`() {
        val wal = GpkgTestFiles.bytes("puntos-25830.gpkg").also {
            it[18] = 2
            it[19] = 2
        }
        val viewModel = viewModel()
        viewModel.start(projectId, es.topophone.app.ui.points.ImportSource("wal.gpkg", wal.size.toLong()) { wal.inputStream() })

        val state = viewModel.settled()

        state.stage shouldBe GpkgImportStage.CONFIG
        state.layers.single().info.featureCount shouldBe 5
    }

    @Test
    fun `S048 RNF-T5 al salir de la pantalla se borra la copia temporal del fichero`() {
        val viewModel = open("puntos-25830.gpkg")
        viewModel.settled()
        cache.listFiles().orEmpty().size shouldBe 1

        viewModel.clearForTest()

        cache.listFiles().orEmpty().size shouldBe 0
    }

    @Test
    fun `S048 RF-1 un fichero que no abre no deja la copia temporal`() {
        open("danado.gpkg").settled()
        open("texto.gpkg").settled()

        cache.listFiles().orEmpty().size shouldBe 0
    }

    @Test
    fun `S048 RF-1 start solo prepara la importacion la primera vez`() {
        val viewModel = open("puntos-25830.gpkg")
        viewModel.settled()

        viewModel.start(projectId, GpkgTestFiles.source("poligonos-25830.gpkg"))

        viewModel.state.value.fileName shouldBe "puntos-25830.gpkg"
    }
}

/** `onCleared` es protegido: los tests lo llaman por reflexión, como lo hace el ciclo de vida. */
private fun GpkgImportViewModel.clearForTest() {
    val method = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared")
    method.isAccessible = true
    method.invoke(this)
}
