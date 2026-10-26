package es.topophone.app.ui.surface

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.app.ui.points.ImportSource
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.map.MapViewport
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.map.RoomMapViewportRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.data.surface.RoomSurfaceRepository
import es.topophone.data.surface.SurfaceImporter
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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

/** Importar un LandXML, parte 1: reconocer el fichero, explorarlo y calcular la extensión, la distancia y los avisos (S042/RF-1 a RF-9, RF-15). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SurfaceImportViewModelTest {

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

    private fun newProject(crs: ProjectCrs, unit: LinearUnit = LinearUnit.METRE): String = runBlocking {
        val name = "Proyecto ${crs.name} ${projectCount++}"
        projects.create(ProjectDefaults().toDraft().copy(name = name, crs = crs, linearUnit = unit))
            .shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun open(source: ImportSource, project: String = projectId): SurfaceImportViewModel {
        val vm = SurfaceImportViewModel(surfaces, SurfaceImporter(surfaces), projects, pointsRepository, viewports, dispatcher)
        vm.start(project, source)
        vm.settled()
        return vm
    }

    private fun open(name: String, project: String = projectId) = open(SurfaceTestFiles.source(name), project)

    @Test
    fun `S042 RF-2 y RF-3 un fichero que no es LandXML se rechaza con su motivo`() {
        val html = open(SurfaceTestFiles.source("pagina.xml", "<html><body/></html>")).state.value
        val json = open(SurfaceTestFiles.source("datos.xml", "{\"a\": 1}")).state.value
        val cut = open("mal-formado.xml").state.value

        html.stage shouldBe SurfaceImportStage.PROBLEM
        html.fileProblem shouldBe SurfaceFileProblem.NOT_LANDXML
        json.fileProblem shouldBe SurfaceFileProblem.NOT_XML
        cut.fileProblem shouldBe SurfaceFileProblem.NOT_XML
    }

    @Test
    fun `S042 RF-2 una version que no es de 1_0 a 1_2 se rechaza diciendo la version`() {
        val state = open(SurfaceTestFiles.source("v2.xml", "<LandXML version=\"2.0\"/>")).state.value

        state.fileProblem shouldBe SurfaceFileProblem.UNSUPPORTED_VERSION
        state.version shouldBe "2.0"
    }

    @Test
    fun `S042 RF-3 un fichero vacio se rechaza`() {
        open(SurfaceTestFiles.source("vacio.xml", "")).state.value.fileProblem shouldBe SurfaceFileProblem.EMPTY
    }

    @Test
    fun `S042 RF-3 un LandXML sin superficies o con solo una malla regular no tiene nada que importar`() {
        open("sin-superficies.xml").state.value.fileProblem shouldBe SurfaceFileProblem.NO_SURFACE
        open("malla.xml").state.value.fileProblem shouldBe SurfaceFileProblem.NO_SURFACE
    }

    @Test
    fun `S042 RF-4 un fichero de mas de 100 MB se rechaza sin leerlo`() {
        val big = open(SurfaceTestFiles.source("dos-superficies.xml", declaredSize = SurfaceImportViewModel.MAX_BYTES + 1))

        big.state.value.fileProblem shouldBe SurfaceFileProblem.TOO_BIG
        big.state.value.stage shouldBe SurfaceImportStage.PROBLEM
    }

    @Test
    fun `S042 RF-4 si el tamano no se conoce y se pasa del limite leyendo, tambien se rechaza`() {
        val endless = ImportSource("enorme.xml", null) {
            object : java.io.InputStream() {
                private val head =
                    "<LandXML version=\"1.2\"><Surfaces><Surface name=\"x\"><Definition surfType=\"TIN\">".toByteArray() +
                        "<Pnts>".toByteArray()
                private val point = "<P id=\"1\">4474257.38189 440290.45805 600.0</P>\n".toByteArray()
                private var position = 0L

                override fun read(): Int {
                    val i = position++
                    return if (i < head.size) head[i.toInt()].toInt() else point[((i - head.size) % point.size).toInt()].toInt()
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    for (k in 0 until len) b[off + k] = read().toByte()
                    return len
                }
            }
        }
        val vm = SurfaceImportViewModel(surfaces, SurfaceImporter(surfaces), projects, pointsRepository, viewports, dispatcher)
        vm.maxBytes = 200_000

        vm.start(projectId, endless)

        vm.settled().fileProblem shouldBe SurfaceFileProblem.TOO_BIG
    }

    @Test
    fun `S042 RF-5 un fichero con dos superficies las ofrece y deja elegir una`() {
        val vm = open("dos-superficies.xml")

        val first = vm.state.value
        first.stage shouldBe SurfaceImportStage.CONFIG
        first.surfaces.map { it.name } shouldBe listOf("terreno", "rasante")
        first.selected shouldBe 0
        first.chosen.shouldNotBeNull().name shouldBe "terreno"

        vm.select(1)

        vm.state.value.chosen.shouldNotBeNull().name shouldBe "rasante"
        vm.select(7) // una posicion que no existe no cambia nada
        vm.state.value.selected shouldBe 1
    }

    @Test
    fun `S042 RF-8 la extension de la superficie elegida sale en el sistema del proyecto y cambia con la superficie`() {
        val vm = open("dos-superficies.xml")
        val terrain = vm.state.value.extent.shouldNotBeNull()

        terrain.minEasting shouldBe (440_290.45805 plusOrMinus 1e-3)
        terrain.maxEasting shouldBe (440_411.72123 plusOrMinus 1e-3)
        vm.state.value.outsideArea shouldBe false

        vm.select(1)

        val rasante = vm.state.value.extent.shouldNotBeNull()
        (rasante.minEasting > terrain.maxEasting) shouldBe true
        (rasante.maxEasting - rasante.minEasting) shouldBe (30.0 plusOrMinus 1e-3)
    }

    @Test
    fun `S042 RF-6 se puede elegir otro sistema y la extension se recalcula`() {
        val vm = open("en-utm29.xml")
        vm.setSource(CadSourceCrs.Utm(GeodeticDatum.ETRS89, 29))

        val converted = vm.state.value.extent.shouldNotBeNull()

        converted.minEasting shouldBe (440_290.0 plusOrMinus 15.0)
        vm.state.value.source shouldBe CadSourceCrs.Utm(GeodeticDatum.ETRS89, 29)
        vm.state.value.canImport shouldBe true
    }

    @Test
    fun `S042 RF-7 se propone la unidad que declara el fichero y si no la del proyecto`() {
        open("en-pies.xml").state.value.unit shouldBe LinearUnit.INTERNATIONAL_FOOT
        open("en-pies.xml").state.value.declaredUnit shouldBe LinearUnit.INTERNATIONAL_FOOT
        val sinUnidad = SurfaceTestFiles.source(
            "sin-unidad.xml",
            "<LandXML version=\"1.2\"><Surfaces><Surface name=\"s\"><Definition surfType=\"TIN\"><Pnts>" +
                "<P id=\"1\">4474257 440290 1</P><P id=\"2\">4474257 440300 1</P><P id=\"3\">4474267 440290 1</P></Pnts>" +
                "<Faces><F>1 2 3</F></Faces></Definition></Surface></Surfaces></LandXML>",
        )
        val inFeet = newProject(ProjectCrs.ETRS89_UTM30, LinearUnit.INTERNATIONAL_FOOT)

        open(sinUnidad, inFeet).state.value.unit shouldBe LinearUnit.INTERNATIONAL_FOOT
        open(sinUnidad).state.value.unit shouldBe LinearUnit.METRE
    }

    @Test
    fun `S042 RF-7 se puede elegir otra unidad y la extension cambia`() {
        val vm = open("en-pies.xml")
        val inFeet = vm.state.value.extent.shouldNotBeNull()

        vm.setUnit(LinearUnit.METRE) // las mismas cifras leídas como metros caen a miles de kilómetros del sistema del proyecto

        vm.state.value.unit shouldBe LinearUnit.METRE
        vm.state.value.outsideArea shouldBe true
        vm.setUnit(LinearUnit.INTERNATIONAL_FOOT)
        vm.state.value.extent.shouldNotBeNull().maxEasting shouldBe (inFeet.maxEasting plusOrMinus 1e-9)
        vm.state.value.canImport shouldBe true
    }

    @Test
    fun `S042 RF-6 una superficie que no cabe en el sistema del proyecto no se puede importar`() {
        val vm = open("dos-superficies.xml")

        vm.setUnit(LinearUnit.US_SURVEY_FOOT)

        vm.state.value.outsideArea shouldBe true
        vm.state.value.extent.shouldBeNull()
        vm.state.value.canImport shouldBe false
        val geographic = newProject(ProjectCrs.ETRS89_GEOGRAPHIC)
        open("dos-superficies.xml", geographic).state.value.outsideArea shouldBe true
    }

    @Test
    fun `S042 RF-9 una superficie a mas de 100 km del proyecto avisa y pide confirmacion`() {
        pointsRepository.points.value = listOf(point(37.3891, -5.9845)) // el proyecto está en Sevilla y la superficie en Madrid

        val vm = open("dos-superficies.xml")

        val far = vm.state.value
        far.farWarning shouldBe true
        (far.distanceKm!! > 300.0) shouldBe true
        far.canImport shouldBe false

        vm.confirmFar()

        vm.state.value.canImport shouldBe true
        vm.setUnit(LinearUnit.INTERNATIONAL_FOOT)
        vm.state.value.farConfirmed shouldBe false
    }

    @Test
    fun `S042 RF-9 con el proyecto en la misma zona no avisa y sin puntos ni encuadre no se conoce el centro`() {
        pointsRepository.points.value = listOf(point(40.4169, -3.7038))
        open("dos-superficies.xml").state.value.farWarning shouldBe false

        pointsRepository.points.value = emptyList()
        val other = newProject(ProjectCrs.ETRS89_UTM30)
        open("dos-superficies.xml", other).state.value.let {
            it.farWarning shouldBe false
            it.distanceKm.shouldBeNull()
        }
    }

    @Test
    fun `S042 RF-9 el centro del proyecto puede salir de su encuadre del mapa`() {
        runBlocking { viewports.save(projectId, MapViewport(37.3891, -5.9845, 15.0)) }

        open("dos-superficies.xml").state.value.farWarning shouldBe true
    }

    @Test
    fun `S042 RF-15 un nombre de fichero ya importado avisa y pide confirmacion`() {
        val first = open("dos-superficies.xml")
        first.import()
        runBlocking { withTimeout(10_000) { first.state.first { it.stage == SurfaceImportStage.RESULT } } }

        val again = open("dos-superficies.xml")

        again.state.value.duplicateName shouldBe true
        again.state.value.canImport shouldBe false
        again.confirmDuplicate()
        again.state.value.canImport shouldBe true
        open("en-pies.xml").state.value.duplicateName shouldBe false
    }

    private fun point(lat: Double, lon: Double) = Point.measured(
        id = "id-p",
        projectId = projectId,
        name = "P1",
        description = "",
        measurement = Measurement(
            antenna = GeodeticPosition(lat, lon, 700.0),
            poleHeightM = 1.8,
            quality = PointQuality("FIX", 20, 0.6, 1.0, 0.01, 0.015, MeasurementMode.INSTANT, 1, 0, false),
            rawNmea = "",
            outOfTolerance = emptySet(),
            measuredFromMs = 0,
            measuredToMs = 0,
            coordinates = PointCoordinates(GeodeticPosition(lat, lon, 698.0), 440_297.0, 4_474_244.0, 650.0),
        ),
        createdAtMs = 0,
    )
}
