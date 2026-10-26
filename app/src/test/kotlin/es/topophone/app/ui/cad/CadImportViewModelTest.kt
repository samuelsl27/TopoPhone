package es.topophone.app.ui.cad

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.app.ui.points.ImportSource
import es.topophone.core.model.cad.CadColor
import es.topophone.core.model.cad.CadDrawing
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.cad.DesignLayer
import es.topophone.core.model.cad.DesignLayerKind
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
import es.topophone.data.cad.CadDrawingImporter
import es.topophone.data.cad.RoomCadRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.map.RoomMapViewportRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Importar un DXF, parte 1: reconocer el fichero, explorarlo y calcular la extensión, la distancia y los avisos (S040/RF-1 a RF-10, RF-20). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CadImportViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val cad = RoomCadRepository(db)
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

    private fun open(source: ImportSource, project: String = projectId): CadImportViewModel {
        val vm = CadImportViewModel(cad, CadDrawingImporter(cad), projects, pointsRepository, viewports, dispatcher)
        vm.start(project, source)
        vm.settled()
        return vm
    }

    private fun open(name: String, project: String = projectId) = open(DxfTestFiles.source(name), project)

    @Test
    fun `S040 RF-3 un DWG se rechaza por su contenido aunque se llame dxf`() {
        val dwg = open("dibujo.dwg").state.value
        val renamed = open("dwg-como-dxf.dxf").state.value

        dwg.stage shouldBe CadImportStage.PROBLEM
        dwg.fileProblem shouldBe CadFileProblem.DWG
        dwg.dwgVersion shouldBe "AC1032"
        renamed.fileProblem shouldBe CadFileProblem.DWG
        renamed.dwgVersion shouldBe "AC1027"
    }

    @Test
    fun `S040 RF-4 un DXF binario, un texto y un fichero vacio se rechazan con su motivo`() {
        open("binario.dxf").state.value.fileProblem shouldBe CadFileProblem.BINARY_DXF
        open("texto.dxf").state.value.fileProblem shouldBe CadFileProblem.NOT_DXF
        open("vacio.dxf").state.value.fileProblem shouldBe CadFileProblem.EMPTY
    }

    @Test
    fun `S040 RF-5 un fichero de mas de 100 MB se rechaza sin leerlo`() {
        val big = open(DxfTestFiles.source("r2000.dxf", declaredSize = MAX_BYTES + 1))

        big.state.value.fileProblem shouldBe CadFileProblem.TOO_BIG
    }

    @Test
    fun `S040 RF-5 si el tamano no se conoce y se pasa del limite leyendo, tambien se rechaza`() {
        val endless = ImportSource("enorme.dxf", null) {
            object : java.io.InputStream() {
                private val head = "0\nSECTION\n2\nENTITIES\n".toByteArray()
                private val point = "0\nPOINT\n8\nL\n10\n1.0\n20\n2.0\n".toByteArray()
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
        val vm = CadImportViewModel(cad, CadDrawingImporter(cad), projects, pointsRepository, viewports, dispatcher)
        vm.maxBytes = 200_000

        vm.start(projectId, endless)

        vm.settled().fileProblem shouldBe CadFileProblem.TOO_BIG
    }

    @Test
    fun `S040 RF-1 un fichero que no se puede abrir o un proyecto que no existe son ilegibles`() {
        open(ImportSource("x.dxf", 10) { null }).state.value.fileProblem shouldBe CadFileProblem.UNREADABLE
        open("r2000.dxf", project = "no-existe").state.value.fileProblem shouldBe CadFileProblem.UNREADABLE
    }

    @Test
    fun `S040 RF-13 un DXF sin ningun elemento se rechaza antes de importar`() {
        open("sin-elementos.dxf").state.value.fileProblem shouldBe CadFileProblem.NO_ELEMENTS
    }

    @Test
    fun `S040 RF-6 y RF-8 por defecto el sistema es el del proyecto y se usan las cotas`() {
        val state = open("cotas.dxf").state.value

        state.stage shouldBe CadImportStage.CONFIG
        state.options.source shouldBe CadSourceCrs.Project
        state.options.useElevations shouldBe true
        state.elementCount shouldBe 7
        state.scanComplete shouldBe true
        state.projectName shouldBe "Proyecto ETRS89_UTM30 0"
        state.availableSources.size shouldBe 8
    }

    @Test
    fun `S040 RF-9 la extension se da en el sistema del proyecto`() {
        val state = open("cotas.dxf").state.value

        state.extent!!.minEasting shouldBe 440000.0
        state.extent!!.maxEasting shouldBe 440010.0
        state.extent!!.minNorthing shouldBe 4474000.0
        state.extent!!.maxNorthing shouldBe 4474014.0
        state.outsideArea shouldBe false
    }

    @Test
    fun `S040 RF-7 la unidad se propone con la que declara el DXF y si no con la del proyecto`() {
        open("origen-etrs89-utm30-pies.dxf").state.value.options.unit shouldBe LinearUnit.INTERNATIONAL_FOOT
        open("origen-etrs89-utm30-pies-us.dxf").state.value.options.unit shouldBe LinearUnit.US_SURVEY_FOOT
        open("r2000.dxf").state.value.options.unit shouldBe LinearUnit.METRE
        val feetProject = newProject(ProjectCrs.ETRS89_UTM30, LinearUnit.INTERNATIONAL_FOOT)

        open("r12.dxf", feetProject).state.value.options.unit shouldBe LinearUnit.INTERNATIONAL_FOOT
    }

    @Test
    fun `S040 RF-7 y RF-9 cambiar la unidad recalcula la extension y puede sacar el dibujo del ambito`() {
        val vm = open("origen-etrs89-utm30-pies.dxf")
        vm.state.value.extent!!.minEasting shouldBe (235_778.5 plusOrMinus 1.0)

        vm.setUnit(LinearUnit.METRE) // 1.444.547 m de Este no caben en el huso 30

        vm.state.value.outsideArea shouldBe true
        vm.state.value.extent shouldBe null
        vm.state.value.canImport shouldBe false
        vm.setUnit(LinearUnit.INTERNATIONAL_FOOT)
        vm.state.value.outsideArea shouldBe false
        vm.state.value.canImport shouldBe true
    }

    @Test
    fun `S040 RF-6 elegir otro sistema convierte la extension al del proyecto`() {
        val vm = open("origen-ed50-utm30.dxf")
        val asProject = vm.state.value.extent!!

        vm.setSource(CadSourceCrs.Utm(GeodeticDatum.ED50, 30))

        val converted = vm.state.value.extent!!
        val shift = Math.hypot(converted.minEasting - asProject.minEasting, converted.minNorthing - asProject.minNorthing)
        (shift in 50.0..300.0) shouldBe true
    }

    @Test
    fun `S040 RF-8 las cotas se pueden quitar`() {
        val vm = open("cotas.dxf")

        vm.setUseElevations(false)

        vm.state.value.options.useElevations shouldBe false
    }

    @Test
    fun `S040 RF-10 un dibujo a mas de 100 km del proyecto avisa y pide confirmacion`() {
        pointsRepository.points.value = listOf(point(37.3891, -5.9845)) // el proyecto está en Sevilla y el dibujo en Madrid

        val vm = open("r2000.dxf")

        val far = vm.state.value
        far.farWarning shouldBe true
        (far.distanceKm!! > 300.0) shouldBe true
        far.canImport shouldBe false

        vm.confirmFar()
        vm.state.value.canImport shouldBe true
    }

    @Test
    fun `S040 RF-10 cambiar una opcion pide confirmar otra vez el aviso de lejania`() {
        pointsRepository.points.value = listOf(point(37.3891, -5.9845))
        val vm = open("r2000.dxf")
        vm.confirmFar()

        vm.setUseElevations(false)

        vm.state.value.farWarning shouldBe true
        vm.state.value.farConfirmed shouldBe false
        vm.state.value.canImport shouldBe false
    }

    @Test
    fun `S040 RF-6 y RF-10 elegir el sistema bien mete el dibujo en el ambito y recalcula la distancia`() {
        pointsRepository.points.value = listOf(point(39.52, -3.43)) // el centro de las cuatro ciudades del fichero
        val vm = open("origen-etrs89-utm29.dxf") // como si fuera UTM 30 cae fuera del ámbito

        vm.state.value.outsideArea shouldBe true
        vm.state.value.canImport shouldBe false

        vm.setSource(CadSourceCrs.Utm(GeodeticDatum.ETRS89, 29))

        vm.state.value.outsideArea shouldBe false
        (vm.state.value.distanceKm!! < 100.0) shouldBe true
        vm.state.value.farWarning shouldBe false
        vm.state.value.canImport shouldBe true
    }

    @Test
    fun `S040 RF-10 el centro del proyecto es el de sus puntos`() {
        pointsRepository.points.value = listOf(point(40.416775, -3.703790))

        val state = open("r2000.dxf").state.value

        (state.distanceKm!! < 1.0) shouldBe true
        state.farWarning shouldBe false
    }

    @Test
    fun `S040 RF-10 sin puntos ni encuadre no se conoce la distancia y no se avisa`() {
        val state = open("r2000.dxf").state.value

        state.distanceKm shouldBe null
        state.farWarning shouldBe false
        state.canImport shouldBe true
    }

    @Test
    fun `S040 RF-10 sin puntos el centro es el del encuadre del mapa`() {
        runBlocking { viewports.save(projectId, MapViewport(37.3891, -5.9845, 15.0)) } // Sevilla

        val state = open("r2000.dxf").state.value

        (state.distanceKm!! > 300.0) shouldBe true
        state.farWarning shouldBe true
    }

    @Test
    fun `S040 RF-20 un fichero ya importado con el mismo nombre avisa y pide confirmar`() {
        runBlocking {
            cad.begin(CadDrawing("d1", projectId, "r2000.dxf", 1, CadSourceCrs.Project, LinearUnit.METRE, true, true))
            cad.addLayer(DesignLayer("l1", "d1", "Ejes", DesignLayerKind.LINES, CadColor.rgb(0xFF0000)))
            cad.finish("d1", mapOf("l1" to 1), true, false, null)
        }

        val vm = open("r2000.dxf")

        vm.state.value.duplicateName shouldBe true
        vm.state.value.canImport shouldBe false
        vm.confirmDuplicate()
        vm.state.value.canImport shouldBe true
        open("r2018.dxf").state.value.duplicateName shouldBe false
    }

    @Test
    fun `S040 RF-11 un fichero cortado se explora pero se avisa de que esta incompleto`() {
        val state = open("cortado.dxf").state.value

        state.scanComplete shouldBe false
        state.elementCount shouldBe 3
        state.canImport shouldBe true
    }

    @Test
    fun `S040 RF-11 el progreso llega al tamano del fichero`() {
        val state = open("r2018.dxf").state.value

        state.bytesRead shouldBe DxfTestFiles.bytes("r2018.dxf").size.toLong()
        state.totalBytes shouldBe state.bytesRead
    }

    @Test
    fun `S040 RF-1 abrir otra vez no repite la carga`() {
        var opened = 0
        val data = DxfTestFiles.bytes("r2000.dxf")
        val source = ImportSource("r2000.dxf", data.size.toLong()) {
            opened++
            java.io.ByteArrayInputStream(data)
        }
        val vm = open(source)
        val after = opened

        vm.start(projectId, source)
        vm.settled()

        opened shouldBe after
    }

    @Test
    fun `S040 RF-6 en un proyecto en geograficas un dibujo en metros no cabe`() {
        val geographic = newProject(ProjectCrs.ETRS89_GEOGRAPHIC)

        val state = open("r2000.dxf", geographic).state.value

        state.outsideArea shouldBe true
        state.canImport shouldBe false
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

    private companion object {
        const val MAX_BYTES = 100L * 1024 * 1024
    }
}
