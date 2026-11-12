package es.topophone.app.ui.gpkg

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.shp.ShpEncoding
import es.topophone.core.formats.shp.ShpFileKind
import es.topophone.core.model.cad.GpkgLayerKind
import es.topophone.core.model.project.CentralPoint
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.cad.RoomCadRepository
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

/** Importar un Shapefile, parte 1: elegir un ZIP o ficheros sueltos y listar sus capas para marcarlas (S030/RF-1 a RF-7). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ShpImportViewModelTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val viewports = RoomMapViewportRepository(db)
    private val cad = RoomCadRepository(db)
    private val dispatcher = UnconfinedTestDispatcher()
    private val projectId = runBlocking {
        projects.create(
            ProjectDefaults().toDraft().copy(
                name = "Calle Mayor 12",
                crs = ProjectCrs.ETRS89_UTM30,
                centralPoint = CentralPoint(40.4168, -3.7038),
            ),
        ).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        disposeGpkgViewModels()
        Dispatchers.resetMain()
        db.close()
    }

    private val cache: File by lazy { folder.newFolder("cache") }

    private fun open(vararg paths: String): GpkgImportViewModel {
        val viewModel = gpkgViewModel(cache, projects, viewports, dispatcher, cad)
        viewModel.startShapefile(projectId, ShpTestFiles.sources(*paths))
        return viewModel
    }

    private fun problemOf(vararg paths: String): GpkgFileProblem? {
        val state = open(*paths).settled()
        state.stage shouldBe GpkgImportStage.PROBLEM
        return state.problem
    }

    @Test
    fun `S030 RF-1 un ZIP con varias capas abre la pantalla con el nombre del ZIP y el proyecto`() {
        val state = open("zip/completo.zip").settled()

        state.stage shouldBe GpkgImportStage.CONFIG
        state.format shouldBe ImportFormat.SHAPEFILE
        state.fileName shouldBe "completo.zip"
        state.projectName shouldBe "Calle Mayor 12"
        state.layers.map { it.info.table } shouldBe listOf("ejes", "parcelas", "vertices")
    }

    @Test
    fun `S030 RF-1 con ficheros sueltos el nombre es el del primer shp`() {
        val state = open("poligonos-25830/parcelas.shp", "poligonos-25830/parcelas.dbf", "puntos-25830/vertices.shp").settled()

        state.stage shouldBe GpkgImportStage.CONFIG
        state.fileName shouldBe "parcelas.shp"
        state.layers.map { it.info.table } shouldBe listOf("parcelas", "vertices")
    }

    @Test
    fun `S030 RF-2 un shp con otra extension y las mayusculas cambiadas se reconocen`() {
        open("otra-extension/vertices.dat", "otra-extension/vertices.dbf").settled().layers.single().info.table shouldBe "vertices"
        open("mayusculas/PARCELAS.SHP", "mayusculas/PARCELAS.Dbf").settled().layers.single().info.table shouldBe "PARCELAS"
    }

    @Test
    fun `S030 RF-3 un texto, un fichero vacio, un ZIP danado o sin shp no abren la pantalla y dicen por que`() {
        problemOf("zip/texto.zip") shouldBe GpkgFileProblem.NOT_SHAPEFILE
        problemOf("zip/vacio.zip") shouldBe GpkgFileProblem.EMPTY
        problemOf("zip/danado.zip") shouldBe GpkgFileProblem.DAMAGED
        problemOf("zip/sin-shp.zip") shouldBe GpkgFileProblem.NO_SHP
    }

    @Test
    fun `S030 RF-3 unos ficheros sueltos sin el shp piden el shp`() {
        problemOf("puntos-25830/vertices.dbf", "puntos-25830/vertices.prj") shouldBe GpkgFileProblem.NO_SHP
    }

    @Test
    fun `S030 RF-4 un ZIP con otros ficheros o mas de un ZIP se rechaza`() {
        problemOf("zip/completo.zip", "puntos-25830/vertices.shp") shouldBe GpkgFileProblem.MIXED_SELECTION
        problemOf("zip/completo.zip", "zip/huerfanos.zip") shouldBe GpkgFileProblem.MIXED_SELECTION
    }

    @Test
    fun `S030 RF-5 los ficheros sin su shp se avisan por su nombre`() {
        val state = open("zip/huerfanos.zip").settled()

        state.stage shouldBe GpkgImportStage.CONFIG
        state.leftOutTables shouldBe listOf("otro.shx", "solo.dbf", "solo.prj")
        open("puntos-25830/vertices.shp", "puntos-25830/vertices.dbf", "incompletos/dbf_corto.dbf").settled().leftOutTables shouldBe
            listOf("dbf_corto.dbf")
    }

    @Test
    fun `S030 RF-6 mas de 100 MB entre los ficheros elegidos no se abre`() {
        val viewModel = gpkgViewModel(cache, projects, viewports, dispatcher, cad)
        val big = ShpTestFiles.source("zip/completo.zip", declaredSize = ShpFileKind.MAX_PICKED_BYTES + 1)

        viewModel.startShapefile(projectId, listOf(big))

        viewModel.settled().problem shouldBe GpkgFileProblem.TOO_BIG
    }

    @Test
    fun `S030 RF-7 cada capa sale con su tipo y su numero de objetos y todas marcadas`() {
        val state = open("zip/completo.zip").settled()

        val rows = state.layers.associateBy { it.info.table }
        rows.getValue("vertices").info.kind shouldBe GpkgLayerKind.POINTS
        rows.getValue("vertices").info.featureCount shouldBe 5
        rows.getValue("ejes").info.kind shouldBe GpkgLayerKind.LINES
        rows.getValue("ejes").info.featureCount shouldBe 3
        rows.getValue("parcelas").info.kind shouldBe GpkgLayerKind.POLYGONS
        rows.getValue("parcelas").info.featureCount shouldBe 2
        state.layers.all { it.marked } shouldBe true
        state.canImport shouldBe true
    }

    @Test
    fun `S030 RF-7 se puede desmarcar una capa y sin ninguna marcada no se puede importar`() {
        val viewModel = open("zip/completo.zip")
        viewModel.settled()

        viewModel.setMarked("ejes", false)
        viewModel.settled().markedLayers.map { it.info.table } shouldBe listOf("parcelas", "vertices")

        listOf("parcelas", "vertices").forEach { viewModel.setMarked(it, false) }
        val state = viewModel.settled()
        state.markedLayers shouldBe emptyList()
        state.canImport shouldBe false
    }

    @Test
    fun `S030 RF-7 dos capas con el mismo nombre en carpetas distintas llevan la carpeta`() {
        open("zip/carpetas-repetidas.zip").settled().layers.map { it.info.table } shouldBe listOf("norte/parcelas", "sur/parcelas")
    }

    @Test
    fun `S030 RF-18 cada fila lleva la codificacion de su dbf y los objetos sin registro`() {
        val state = open(
            "incompletos/dbf_corto.shp",
            "incompletos/dbf_corto.dbf",
            "codificaciones/w1252_sin_cpg.shp",
            "codificaciones/w1252_sin_cpg.dbf",
        )
            .settled()

        val rows = state.layers.associateBy { it.info.table }
        rows.getValue("dbf_corto").encoding shouldBe ShpEncoding.UTF_8
        rows.getValue("dbf_corto").withoutRecords shouldBe 2
        rows.getValue("w1252_sin_cpg").encoding shouldBe ShpEncoding.WINDOWS_1252
        rows.getValue("w1252_sin_cpg").cpgUnknown shouldBe false
    }

    @Test
    fun `S030 RF-2 un GeoPackage sigue abriendose como siempre`() {
        val viewModel = gpkgViewModel(cache, projects, viewports, dispatcher, cad)
        viewModel.start(projectId, GpkgTestFiles.source("puntos-25830.gpkg"))

        val state = viewModel.settled()

        state.format shouldBe ImportFormat.GEOPACKAGE
        state.layers.single().info.table shouldBe "vertices"
        state.layers.single().encoding shouldBe null
    }
}
