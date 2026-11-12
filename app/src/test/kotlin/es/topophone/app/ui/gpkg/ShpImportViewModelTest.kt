package es.topophone.app.ui.gpkg

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.gpkg.GpkgCrs
import es.topophone.core.formats.gpkg.GpkgCrsUnit
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

    @Test
    fun `S030 RF-8 el sistema del prj se propone y su unidad la fija el sistema`() {
        val state = open("puntos-25830/vertices.shp", "puntos-25830/vertices.prj", "lineas-4258/ejes.shp", "lineas-4258/ejes.prj").settled()

        val rows = state.layers.associateBy { it.info.table }
        rows.getValue("vertices").crs.shouldBeInstanceOf<GpkgCrs.Supported>().epsg shouldBe 25830
        rows.getValue("vertices").effective!!.unit shouldBe GpkgCrsUnit.METRE
        rows.getValue("ejes").crs.shouldBeInstanceOf<GpkgCrs.Supported>().epsg shouldBe 4258
        rows.getValue("ejes").effective!!.unit shouldBe GpkgCrsUnit.DEGREE
        state.canImport shouldBe true
    }

    @Test
    fun `S030 RF-9 una capa sin prj no se puede importar hasta elegirle un sistema de la app`() {
        val viewModel = open("sistemas/sin_prj.shp", "sistemas/sin_prj.dbf")

        val state = viewModel.settled()

        state.layers.single().crs shouldBe GpkgCrs.Undeclared
        state.layers.single().effective shouldBe null
        state.canImport shouldBe false

        viewModel.setCrs("sin_prj", ProjectCrs.ETRS89_UTM30)
        val chosen = viewModel.settled()
        chosen.layers.single().effective!!.epsg shouldBe 25830
        chosen.canImport shouldBe true
    }

    @Test
    fun `S030 RF-9 un sistema que la app no ofrece lleva el nombre del prj y tambien espera a que se elija uno`() {
        val viewModel = open("sistemas/utm33.shp", "sistemas/utm33.prj", "sistemas/prj_ilegible.shp", "sistemas/prj_ilegible.prj")

        val state = viewModel.settled()

        val rows = state.layers.associateBy { it.info.table }
        rows.getValue("utm33").crs.shouldBeInstanceOf<GpkgCrs.Unavailable>().name shouldBe "ETRS_1989_UTM_Zone_33N"
        rows.getValue("prj_ilegible").crs shouldBe GpkgCrs.Undeclared
        state.canImport shouldBe false
        viewModel.setCrs("utm33", ProjectCrs.ETRS89_UTM31)
        viewModel.setCrs("prj_ilegible", ProjectCrs.ETRS89_UTM30)
        viewModel.settled().canImport shouldBe true
    }

    @Test
    fun `S030 RF-10 la extension de cada capa y su distancia al centro del proyecto`() {
        val state = open("puntos-25830/vertices.shp", "puntos-25830/vertices.prj").settled()

        val row = state.layers.single()
        (row.extent != null) shouldBe true
        (row.distanceKm!! < 1.0) shouldBe true
        row.outsideArea shouldBe false
        state.farWarning shouldBe false
    }

    @Test
    fun `S030 RF-10 a mas de 100 km del centro del proyecto hay que confirmar antes de importar`() {
        val seville = runBlocking {
            projects.create(
                ProjectDefaults().toDraft().copy(
                    name = "Sevilla",
                    crs = ProjectCrs.ETRS89_UTM30,
                    centralPoint = CentralPoint(37.3891, -5.9845),
                ),
            ).shouldBeInstanceOf<SaveResult.Saved>().id
        }
        val viewModel = gpkgViewModel(cache, projects, viewports, dispatcher, cad)
        viewModel.startShapefile(seville, ShpTestFiles.sources("puntos-25830/vertices.shp", "puntos-25830/vertices.prj"))

        val state = viewModel.settled()

        state.farWarning shouldBe true
        state.canImport shouldBe false
        viewModel.confirmFar()
        viewModel.settled().canImport shouldBe true
    }

    @Test
    fun `S030 RF-18 cambiar la codificacion relee la capa y conserva el campo de nombre`() {
        val viewModel = open("codificaciones/w1252_sin_cpg.shp", "codificaciones/w1252_sin_cpg.dbf")
        viewModel.settled().layers.single().encoding shouldBe ShpEncoding.WINDOWS_1252

        viewModel.setEncoding("w1252_sin_cpg", ShpEncoding.UTF_8)

        val row = viewModel.settled().layers.single()
        row.encoding shouldBe ShpEncoding.UTF_8
        row.nameField shouldBe "nombre"
        row.info.columns.map { it.name } shouldBe listOf("nombre")
    }

    @Test
    fun `S030 RF-18 cambiar la codificacion de una capa de GeoPackage no hace nada`() {
        val viewModel = gpkgViewModel(cache, projects, viewports, dispatcher, cad)
        viewModel.start(projectId, GpkgTestFiles.source("puntos-25830.gpkg"))
        viewModel.settled()

        viewModel.setEncoding("vertices", ShpEncoding.UTF_8)

        viewModel.settled().layers.single().encoding shouldBe null
    }
}
