package es.topophone.app.ui.gpkg

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.gpkg.GpkgCrs
import es.topophone.core.model.cad.GpkgIgnoredReason
import es.topophone.core.model.cad.ImportArea
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.log.LogLevel
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Importar un KML o KMZ: abrir el fichero y listar sus capas, los problemas, el datum, las altitudes y la importación (S031/RF-1 a RF-19). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class KmlImportViewModelTest {

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
    private val madrid = CentralPoint(40.4168, -3.7038)
    private val projectId = runBlocking {
        projects.create(
            ProjectDefaults().toDraft().copy(name = "Calle Mayor 12", crs = ProjectCrs.ETRS89_UTM30, centralPoint = madrid),
        ).shouldBeInstanceOf<SaveResult.Saved>().id
    }
    private val logged = mutableListOf<String>()
    private val log = object : AppLog {
        override fun record(level: LogLevel, category: LogCategory, message: String) {
            logged += "$level $category $message"
        }
    }
    private val cache: File by lazy { folder.newFolder("cache") }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        disposeGpkgViewModels()
        Dispatchers.resetMain()
        db.close()
    }

    private fun open(
        path: String,
        project: String = projectId,
        declaredSize: Long? = KmlTestFiles.file(path).length(),
    ): GpkgImportViewModel {
        val viewModel = gpkgViewModel(cache, projects, viewports, dispatcher, cad, log = log)
        viewModel.startKml(project, KmlTestFiles.source(path, declaredSize))
        return viewModel
    }

    private fun problemOf(path: String): GpkgFileProblem? {
        val state = open(path).settled()
        state.stage shouldBe GpkgImportStage.PROBLEM
        return state.problem
    }

    private fun GpkgImportViewModel.finished(): GpkgImportUiState = runBlocking {
        withTimeout(15_000) {
            state.first {
                it.stage == GpkgImportStage.RESULT ||
                    (it.stage == GpkgImportStage.CONFIG && !it.zone.counting && (it.failure != null || it.cancelled))
            }
        }
    }

    private fun drawings() = runBlocking { cad.drawings(projectId).first() }

    private var others = 0

    private fun projectIn(crs: ProjectCrs, center: CentralPoint) = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Otro ${others++}", crs = crs, centralPoint = center))
            .shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @Test
    fun `S031 RF-1 un KML abre la pantalla con el nombre del fichero, el proyecto y sus capas con su ruta`() {
        val state = open("carpetas-anidadas.kml").settled()

        state.stage shouldBe GpkgImportStage.CONFIG
        state.format shouldBe ImportFormat.KML
        state.fileName shouldBe "carpetas-anidadas.kml"
        state.projectName shouldBe "Calle Mayor 12"
        state.layers.map { it.info.table } shouldBe listOf(
            "carpetas-anidadas (puntos)",
            "Obra (líneas)",
            "Obra (puntos)",
            "Obra / Parcelas / Norte (puntos)",
            "Obra / Parcelas / Norte (líneas)",
            "Obra / Parcelas / Sur",
            "Obra / Anexo",
            "carpetas-anidadas (líneas)",
        )
    }

    @Test
    fun `S031 RF-2 un KMZ se abre por su contenido y su nombre es el del KMZ`() {
        val state = open("con-doc.kmz").settled()

        state.stage shouldBe GpkgImportStage.CONFIG
        state.fileName shouldBe "con-doc.kmz"
        state.layers.map { it.info.table } shouldBe listOf("con-doc (puntos)", "con-doc (líneas)")
    }

    @Test
    fun `S031 RF-2 un KMZ de varias capas de QGIS enlaza sus capas por vinculos internos`() {
        val state = open("qgis/qgis-libkml-multicapa.kmz").settled()

        state.layers.map { it.info.table } shouldBe listOf("puntos", "lineas", "poligonos")
    }

    @Test
    fun `S031 RF-3 un fichero que no vale no abre la pantalla y dice por que`() {
        problemOf("vacio.kml") shouldBe GpkgFileProblem.EMPTY
        problemOf("texto.kml") shouldBe GpkgFileProblem.NOT_KML
        problemOf("no-es-kml.kml") shouldBe GpkgFileProblem.NOT_KML
        problemOf("danado.kml") shouldBe GpkgFileProblem.DAMAGED
        problemOf("danado.kmz") shouldBe GpkgFileProblem.DAMAGED
        problemOf("sin-kml.kmz") shouldBe GpkgFileProblem.NOT_KML
        problemOf("sin-marcas.kml") shouldBe GpkgFileProblem.NO_PLACEMARKS
        problemOf("solo-ignorados.kml") shouldBe GpkgFileProblem.NO_PLACEMARKS
    }

    @Test
    fun `S031 RF-4 mas de 100 MB no se abre aunque el proveedor no lo diga`() {
        val state = open("carpetas-anidadas.kml", declaredSize = 100L * 1024 * 1024 + 1).settled()

        state.stage shouldBe GpkgImportStage.PROBLEM
        state.problem shouldBe GpkgFileProblem.TOO_BIG
    }

    @Test
    fun `S031 RF-6 todas las capas salen marcadas y se pueden desmarcar`() {
        val viewModel = open("geometrias.kml")
        val state = viewModel.settled()

        state.layers.all { it.marked } shouldBe true
        state.canImport shouldBe true
        viewModel.setMarked("Geometrias (líneas)", false)
        viewModel.settled().markedLayers.map { it.info.table } shouldBe listOf("Geometrias (puntos)")
    }

    @Test
    fun `S031 RF-7 las capas llegan en las geograficas del datum del proyecto sin elegir sistema`() {
        val etrs89 = open("carpetas-anidadas.kml").settled()
        etrs89.layers.all { it.crs is GpkgCrs.Supported && it.effective?.epsg == 4258 && it.effective?.wgs84 == true } shouldBe true

        val regcan = projectIn(ProjectCrs.REGCAN95_UTM28, CentralPoint(28.45, -16.25))
        val canarias = open("canarias.kml", project = regcan).settled()
        canarias.layers.all { it.effective?.epsg == 4081 && it.effective?.wgs84 == true } shouldBe true

        val ed50 = projectIn(ProjectCrs.ED50_UTM30, madrid)
        open("carpetas-anidadas.kml", project = ed50).settled().layers.all { it.effective?.epsg == 4258 } shouldBe true
    }

    @Test
    fun `S031 RF-10 el campo de nombre propuesto es name`() {
        val state = open("atributos.kml").settled()

        state.layers.single().nameField shouldBe "name"
    }

    @Test
    fun `S031 RF-13 las altitudes se preguntan solo si hay absolutas y por defecto no se usan`() {
        val withAltitudes = open("altitudes.kml").settled()
        withAltitudes.askElevations shouldBe true
        withAltitudes.useElevations shouldBe false

        for (name in listOf("carpetas-anidadas.kml", "geometrias.kml", "estilos.kml")) {
            val state = open(name).settled()
            state.askElevations shouldBe false
            state.useElevations shouldBe false
        }
    }

    @Test
    fun `S031 RF-13 un GeoPackage o un Shapefile siguen preguntando por las cotas con Si por defecto`() {
        val viewModel = gpkgViewModel(cache, projects, viewports, dispatcher, cad)
        viewModel.start(projectId, GpkgTestFiles.source("puntos-25830.gpkg"))

        val state = viewModel.settled()

        state.askElevations shouldBe true
        state.useElevations shouldBe true
    }

    @Test
    fun `S031 RF-13 con No las marcas absolutas no guardan cota y con Si la guardan`() {
        val viewModel = open("altitudes.kml")
        viewModel.settled()

        viewModel.import()
        viewModel.finished().stage shouldBe GpkgImportStage.RESULT
        drawings().single().drawing.hasElevations shouldBe false
        runBlocking { cad.deleteDrawing(drawings().single().drawing.id) }

        val yes = open("altitudes.kml")
        yes.settled()
        yes.setUseElevations(true)
        yes.import()
        yes.finished().stage shouldBe GpkgImportStage.RESULT
        drawings().single().drawing.hasElevations shouldBe true
    }

    @Test
    fun `S031 RF-18 el resumen avisa de que WGS84 y el datum se han tomado como iguales y cuenta los elementos ignorados`() {
        val viewModel = open("ignorados.kml")
        viewModel.settled()

        viewModel.import()
        val state = viewModel.finished()

        state.stage shouldBe GpkgImportStage.RESULT
        val summary = state.result!!.summary
        summary.wgs84Layers shouldBe listOf("Mezcla (puntos)", "Mezcla (líneas)")
        summary.ignoredElements[GpkgIgnoredReason.NETWORK_LINK] shouldBe 2
        summary.ignoredElementsTotal shouldBe 10
    }

    @Test
    fun `S031 RF-19 el dibujo guarda el nombre del fichero y el log solo lleva recuentos`() {
        val viewModel = open("carpetas-anidadas.kml")
        viewModel.settled()

        viewModel.import()
        viewModel.finished().stage shouldBe GpkgImportStage.RESULT

        drawings().single().drawing.fileName shouldBe "carpetas-anidadas.kml"
        val line = logged.single { it.contains("importado") }
        line shouldBe "INFO APP KML importado: 10 objetos, 0 ignorados, 8 capas"
        logged.none { it.contains("Obra") || it.contains("Suelto") || it.contains("440") } shouldBe true
    }

    @Test
    fun `S031 RF-3 un fichero que no vale se anota en el log sin su nombre`() {
        open("texto.kml").settled()

        logged.any { it == "INFO APP KML no abierto: not_kml" } shouldBe true
    }

    @Test
    fun `S031 RF-17 una importacion que no deja ningun objeto vuelve a la configuracion y no guarda nada`() {
        val far = projectIn(ProjectCrs.ETRS89_UTM30, CentralPoint(37.0, -6.0))
        val viewModel = open("carpetas-anidadas.kml", project = far)
        viewModel.settled()
        viewModel.confirmFar() // el KML cae a más de 100 km del centro del proyecto (S048/RF-12)

        viewModel.import()
        val state = viewModel.finished()

        state.stage shouldBe GpkgImportStage.CONFIG
        (state.failure != null) shouldBe true
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM cad_drawings").use {
            it.moveToFirst()
            it.getInt(0) shouldBe 0
        }
    }

    @Test
    fun `S031 RF-15 la zona propuesta es un radio alrededor del centro del proyecto`() {
        val state = open("carpetas-anidadas.kml").settled()

        state.areaAll shouldBe false
        state.radiusM shouldBe ImportArea.DEFAULT_RADIUS_M
        state.hasCenter shouldBe true
        state.farWarning shouldBe false
        state.canImport shouldBe true
    }

    @Test
    fun `S031 RF-15 un KML a mas de 100 km del centro del proyecto pide confirmarlo antes de importar`() {
        val far = projectIn(ProjectCrs.ETRS89_UTM30, CentralPoint(37.0, -6.0))
        val viewModel = open("carpetas-anidadas.kml", project = far)

        val before = viewModel.settled()

        before.farWarning shouldBe true
        before.canImport shouldBe false
        viewModel.confirmFar()
        viewModel.settled().canImport shouldBe true
    }

    @Test
    fun `S031 RF-15 un proyecto sin centro pide fijar el punto central o elegir todo el contenido`() {
        val withoutCenter = runBlocking {
            projects.create(ProjectDefaults().toDraft().copy(name = "Sin centro", crs = ProjectCrs.ETRS89_UTM30))
                .shouldBeInstanceOf<SaveResult.Saved>().id
        }
        val viewModel = open("carpetas-anidadas.kml", project = withoutCenter)

        val asked = viewModel.settled()

        asked.needsCenter shouldBe true
        asked.canImport shouldBe false
        viewModel.setAllContent(true)
        viewModel.settled().canImport shouldBe true
        viewModel.setAllContent(false)
        viewModel.setCentralPoint(madrid)
        runBlocking { withTimeout(10_000) { viewModel.state.first { it.hasCenter } } }
        val fixed = viewModel.settled()
        fixed.needsCenter shouldBe false
        fixed.canImport shouldBe true
    }

    @Test
    fun `S031 RF-15 una zona con mas objetos que el limite no deja importar`() {
        val viewModel = gpkgViewModel(cache, projects, viewports, dispatcher, cad, log = log)
        viewModel.maxObjects = 2
        viewModel.startKml(projectId, KmlTestFiles.source("carpetas-anidadas.kml"))

        val state = viewModel.settled()

        state.zone.exceeded shouldBe true
        state.canImport shouldBe false
    }
}
