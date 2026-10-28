package es.topophone.app.ui.raster

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.app.ui.points.ImportSource
import es.topophone.app.ui.projects.ProjectCenterProvider
import es.topophone.core.formats.raster.RasterCrsDeclaration
import es.topophone.core.formats.raster.RasterLimits
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.CentralPoint
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.map.RoomMapViewportRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.data.surface.RasterImportFailure
import es.topophone.data.surface.RasterImporter
import es.topophone.data.surface.RoomSurfaceRepository
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
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

/** Los ficheros de `fixtures/raster/` como fuentes de la importación, sin pasar por el selector de ficheros (S045). */
object RasterTestFiles {
    private val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
    }

    fun bytes(name: String): ByteArray = File(root, "fixtures/raster/$name").readBytes()

    fun table(name: String): List<Map<String, String>> {
        val lines = File(root, "fixtures/raster/$name").readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        val head = lines.first().split(",")
        return lines.drop(1).map { line -> head.zip(line.split(",")).toMap() }
    }

    /** Una fuente que sirve los bytes del fichero [name] cada vez que se abre; [declaredSize] puede mentir o ser null. */
    fun source(name: String, declaredSize: Long? = bytes(name).size.toLong()): ImportSource {
        val data = bytes(name)
        return ImportSource(name, declaredSize) { ByteArrayInputStream(data) }
    }
}

/** Espera a que el fichero esté copiado, explorado y medido: Room contesta desde otro hilo. Un test no debe acabar con la carga en vuelo. */
fun RasterImportViewModel.settled(): RasterImportUiState = runBlocking {
    withTimeout(20_000) {
        state.first { it.stage != RasterImportStage.LOADING && it.stage != RasterImportStage.IMPORTING && !it.scanning }
    }
}

/** Importar un ráster, parte 1: reconocer el fichero, explorarlo y calcular la extensión, las cotas, la distancia y los avisos (S045/RF-1 a RF-12, RF-15). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class RasterImportViewModelTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val surfaces = RoomSurfaceRepository(db)
    private val viewports = RoomMapViewportRepository(db)
    private val pointsRepository = FakePointRepository()
    private val dispatcher = UnconfinedTestDispatcher()
    private val projectId = newProject(ProjectCrs.ETRS89_UTM30)

    private var projectCount = 0

    private fun newProject(crs: ProjectCrs, unit: LinearUnit = LinearUnit.METRE, height: HeightSystem = HeightSystem.ORTHOMETRIC): String =
        runBlocking {
            val name = "Proyecto ${crs.name} ${projectCount++}"
            projects.create(ProjectDefaults().toDraft().copy(name = name, crs = crs, linearUnit = unit, heightSystem = height))
                .shouldBeInstanceOf<SaveResult.Saved>().id
        }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun importer(maxBytes: Long = RasterLimits.MAX_FILE_BYTES) =
        RasterImporter(surfaces, folder.newFolder(), maxFileBytes = maxBytes)

    private fun open(source: ImportSource, project: String = projectId, importer: RasterImporter = importer()): RasterImportViewModel {
        val vm = RasterImportViewModel(surfaces, importer, projects, ProjectCenterProvider(pointsRepository, viewports), dispatcher)
        vm.start(project, source)
        vm.settled()
        return vm
    }

    private fun open(name: String, project: String = projectId) = open(RasterTestFiles.source(name), project)

    @Test
    fun `S045 RF-1 un GeoTIFF se copia se explora y se mide antes de importar`() {
        val vm = open("f32-sin-tiras-area.tif")

        val state = vm.state.value
        state.stage shouldBe RasterImportStage.CONFIG
        state.problem.shouldBeNull()
        state.header.shouldNotBeNull().columns shouldBe 40
        state.header!!.rows shouldBe 36
        state.stats.shouldNotBeNull().dataCells shouldBe 1436
        state.canImport shouldBe true
    }

    @Test
    fun `S045 RF-3 un fichero que no es un raster o esta vacio no se abre y dice por que`() {
        open("no-es-raster.txt").state.value.problem shouldBe RasterImportFailure.NOT_A_RASTER
        open("vacio.tif").state.value.problem shouldBe RasterImportFailure.EMPTY
        open("sin-georreferencia.tif").state.value.problem shouldBe RasterImportFailure.NOT_GEOREFERENCED
        val noData = open("todo-sin-dato.tif").state.value
        noData.stage shouldBe RasterImportStage.PROBLEM
        noData.problem shouldBe RasterImportFailure.NO_DATA_CELLS
    }

    @Test
    fun `S045 RF-4 BigTIFF compresion que no se lee girado y de color dicen su motivo`() {
        open("bigtiff.tif").state.value.problem shouldBe RasterImportFailure.BIGTIFF
        open("compresion-jpeg.tif").state.value.problem shouldBe RasterImportFailure.UNSUPPORTED_COMPRESSION
        open("girado.tif").state.value.problem shouldBe RasterImportFailure.ROTATED
        open("color-rgb.tif").state.value.problem shouldBe RasterImportFailure.COLOUR
    }

    @Test
    fun `S045 RF-5 mas de 250 MB declarados no se leen y los que se pasan leyendo tampoco`() {
        open(RasterTestFiles.source("f32-sin-tiras-area.tif", declaredSize = RasterLimits.MAX_FILE_BYTES + 1)).state.value.problem shouldBe
            RasterImportFailure.TOO_LARGE
        open(
            RasterTestFiles.source("f32-sin-tiras-area.tif", declaredSize = null),
            importer = importer(maxBytes = 1_000),
        ).state.value.problem shouldBe
            RasterImportFailure.TOO_LARGE
    }

    @Test
    fun `S045 RF-6 un GeoTIFF con varias bandas dice cuantas tiene`() {
        open("f32-multibanda.tif").state.value.header.shouldNotBeNull().bandCount shouldBe 2
        open("f32-sin-tiras-area.tif").state.value.header.shouldNotBeNull().bandCount shouldBe 1
    }

    @Test
    fun `S045 RF-8 se propone el sistema que declara el fichero y el del proyecto si coincide`() {
        val sameAsProject = open("f32-sin-tiras-area.tif").state.value
        val other = open("f32-utm29.tif").state.value
        val geographic = open("f32-geograficas.tif").state.value

        sameAsProject.declaration.shouldBeInstanceOf<RasterCrsDeclaration.Supported>().epsg shouldBe 25830
        sameAsProject.source shouldBe CadSourceCrs.Project
        other.source shouldBe CadSourceCrs.Utm(GeodeticDatum.ETRS89, 29)
        geographic.source shouldBe CadSourceCrs.Geographic(GeodeticDatum.ETRS89)
    }

    @Test
    fun `S045 RF-8 un ASCII Grid sin sistema se propone en el del proyecto y se puede cambiar`() {
        val vm = open("esquina.asc")

        vm.state.value.declaration shouldBe RasterCrsDeclaration.Undeclared
        vm.state.value.source shouldBe CadSourceCrs.Project

        vm.setSource(CadSourceCrs.Utm(GeodeticDatum.ETRS89, 29))

        vm.state.value.source shouldBe CadSourceCrs.Utm(GeodeticDatum.ETRS89, 29)
        vm.state.value.extent.shouldNotBeNull() // se convierte desde UTM 29: unas decenas de metros de diferencia
        vm.state.value.canImport shouldBe true
    }

    @Test
    fun `S045 RF-9 la unidad de la cota es metros o la que declara el fichero y se puede cambiar`() {
        val feetProject = newProject(ProjectCrs.ETRS89_UTM30, LinearUnit.INTERNATIONAL_FOOT)
        val plain = open(RasterTestFiles.source("f32-sin-tiras-area.tif"), feetProject)
        val declared = open("f32-pies.tif")

        plain.state.value.unit shouldBe LinearUnit.METRE // no es la del proyecto: es la del fichero
        declared.state.value.unit shouldBe LinearUnit.INTERNATIONAL_FOOT
        declared.state.value.declaredUnit shouldBe LinearUnit.INTERNATIONAL_FOOT

        declared.setUnit(LinearUnit.US_SURVEY_FOOT)

        declared.state.value.unit shouldBe LinearUnit.US_SURVEY_FOOT
    }

    @Test
    fun `S045 RF-10 se propone el sistema de alturas del proyecto y la cota se convierte con el geoide`() {
        val ellipsoidalProject = newProject(ProjectCrs.ETRS89_UTM30, height = HeightSystem.ELLIPSOIDAL)
        val vm = open(RasterTestFiles.source("f32-sin-tiras-area.tif"), ellipsoidalProject)

        vm.state.value.heightSystem shouldBe HeightSystem.ELLIPSOIDAL
        val plain = vm.state.value.minElevationM.shouldNotBeNull()
        plain shouldBe (600.0 plusOrMinus 1e-9)

        vm.setHeightSystem(HeightSystem.ORTHOMETRIC) // el fichero está en altitud: el proyecto, en altura elipsoidal

        val converted = vm.state.value.minElevationM.shouldNotBeNull()
        (converted - plain > 40.0 && converted - plain < 60.0) shouldBe true // h = H + N, con N de Madrid cerca de 50 m
        vm.state.value.geoidShiftM.shouldNotBeNull()
    }

    @Test
    fun `S045 RF-11 la pantalla da la extension el tamano de celda las celdas y los porcentajes y las cotas`() {
        val vm = open("f32-lzw-teselas-area.tif")
        val state = vm.state.value

        state.extent.shouldNotBeNull().minEasting shouldBe (440005.0 plusOrMinus 1e-3)
        state.extent!!.maxNorthing shouldBe (4474005.0 plusOrMinus 1e-3)
        state.header!!.stepX shouldBe 10.0
        state.header!!.cellCount shouldBe 1440L
        state.noDataPercent.shouldNotBeNull() shouldBe (100.0 * 4 / 1440 plusOrMinus 1e-9)
        state.minElevationM shouldBe (600.0 plusOrMinus 1e-9)
        (state.maxElevationM!! > 646.0) shouldBe true
    }

    @Test
    fun `S045 RF-11 al cambiar la unidad las cotas se recalculan en metros`() {
        val vm = open("f32-pies.tif")
        val feet = vm.state.value.minElevationM.shouldNotBeNull()

        vm.setUnit(LinearUnit.METRE)

        feet shouldBe (600.0 * 0.3048 plusOrMinus 1e-9)
        vm.state.value.minElevationM shouldBe (600.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S045 RF-11 al cambiar de sistema la extension se recalcula`() {
        val vm = open("f32-utm29.tif")
        val own = vm.state.value.extent.shouldNotBeNull()
        val expected = RasterTestFiles.table("extensiones.csv").first { it["fichero"] == "f32-utm29.tif" }

        own.minEasting shouldBe (expected.getValue("min_este").toDouble() plusOrMinus 1e-3)

        vm.setSource(CadSourceCrs.Project) // lo mismo que leer sus coordenadas como las del proyecto: queda lejos
        (vm.state.value.extent == null || vm.state.value.extent!!.minEasting != own.minEasting) shouldBe true
    }

    @Test
    fun `S045 RF-12 un raster lejos del centro del proyecto pide confirmar`() {
        runBlocking {
            projects.update(projectId, projects.project(projectId)!!.toDraft().copy(centralPoint = CentralPoint(37.3891, -5.9845)))
        }
        val far = open("f32-sin-tiras-area.tif")

        far.state.value.farWarning shouldBe true
        (far.state.value.distanceKm!! > 300.0) shouldBe true
        far.state.value.canImport shouldBe false

        far.confirmFar()

        far.state.value.canImport shouldBe true
    }

    @Test
    fun `S045 RF-12 un raster cerca del centro del proyecto no avisa`() {
        runBlocking {
            projects.update(projectId, projects.project(projectId)!!.toDraft().copy(centralPoint = CentralPoint(40.4169, -3.7038)))
        }

        val near = open("f32-sin-tiras-area.tif").state.value

        near.farWarning shouldBe false
        (near.distanceKm!! < 5.0) shouldBe true
        near.canImport shouldBe true
    }

    @Test
    fun `S045 RF-3 un raster fuera del ambito del sistema del proyecto no se puede importar`() {
        val vm = open("f32-sin-tiras-area.tif")

        vm.setSource(CadSourceCrs.Geographic(GeodeticDatum.ETRS89))

        vm.state.value.outsideArea shouldBe true
        vm.state.value.outsideReason shouldBe RasterImportFailure.OUTSIDE_CRS_AREA
        vm.state.value.canImport shouldBe false
    }

    @Test
    fun `S045 RF-15 un nombre de fichero ya importado avisa y pide confirmar`() {
        val first = open("esquina.asc")
        first.import()
        first.awaitDone()

        val again = open("esquina.asc")

        again.state.value.duplicateName shouldBe true
        again.state.value.canImport shouldBe false

        again.confirmDuplicate()

        again.state.value.canImport shouldBe true
    }

    private fun RasterImportViewModel.awaitDone() = runBlocking {
        withTimeout(20_000) { state.first { it.stage == RasterImportStage.RESULT || it.failure != null } }
    }
}
