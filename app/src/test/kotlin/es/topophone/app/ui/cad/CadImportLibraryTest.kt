package es.topophone.app.ui.cad

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.app.ui.points.ImportSource
import es.topophone.app.ui.projects.ProjectCenterProvider
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.LibraryCrs
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
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
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

/**
 * Importar un DXF con un SC de origen de la biblioteca: se elige en el selector, queda como origen con su unidad, su extensión coincide con la de
 * pyproj y la importación espera al aviso de precisión y a las rejillas (S057/RF-1, RF-2, RF-3, RF-8 a RF-10, RF-13).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CadImportLibraryTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val cad = RoomCadRepository(db)
    private val viewports = RoomMapViewportRepository(db)
    private val dispatcher = UnconfinedTestDispatcher()
    private val catalog = CrsCatalog.bundled
    private var projectCount = 0

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun library(epsg: Int, corrections: CorrectionsDatum, at: GeoPoint): LibraryCrs {
        val result = catalog.build(epsg, corrections, at)
        val definition = when (result) {
            is CrsBuildResult.Ready -> result.definition
            is CrsBuildResult.NeedsGrids -> result.definition
            is CrsBuildResult.Unavailable -> error("EPSG:$epsg: $result")
        }
        return LibraryCrs(definition, corrections)
    }

    private fun newProject(crs: ProjectCrs): String = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Proyecto ${projectCount++}", crs = crs, linearUnit = LinearUnit.METRE))
            .shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private fun open(source: ImportSource, project: String): CadImportViewModel {
        val vm = CadImportViewModel(
            cad,
            CadDrawingImporter(cad),
            projects,
            ProjectCenterProvider(FakePointRepository(), viewports),
            dispatcher,
        )
        vm.start(project, source)
        vm.settled()
        return vm
    }

    /** Un DXF con un punto en cada par (X, Y). */
    private fun dxf(vararg points: Pair<Double, Double>): ImportSource {
        val text = buildString {
            append("0\nSECTION\n2\nENTITIES\n")
            points.forEach { (x, y) -> append("0\nPOINT\n8\nL\n10\n$x\n20\n$y\n") }
            append("0\nENDSEC\n0\nEOF\n")
        }.toByteArray()
        return ImportSource("plano.dxf", text.size.toLong()) { ByteArrayInputStream(text) }
    }

    @Test
    fun `S057 RF-1 el SC de la biblioteca queda como origen con su unidad y el DXF sale como en el UTM de España a 1 mm`() {
        val project = newProject(ProjectCrs.ETRS89_UTM30)
        val viaUtm = open(DxfTestFiles.source("origen-etrs89-utm29.dxf"), project)
        viaUtm.setSource(CadSourceCrs.Utm(GeodeticDatum.ETRS89, 29))
        val expected = viaUtm.state.value.extent.shouldNotBeNull()

        val vm = open(DxfTestFiles.source("origen-etrs89-utm29.dxf"), project)
        vm.chooseLibrary(library(25829, CorrectionsDatum.ETRS89, GeoPoint(40.4, -3.7)))

        val state = vm.state.value
        state.options.source.shouldBeInstanceOf<CadSourceCrs.Library>()
        state.options.unit shouldBe LinearUnit.METRE
        val extent = state.extent.shouldNotBeNull()
        extent.minEasting shouldBe (expected.minEasting plusOrMinus 1e-3)
        extent.maxNorthing shouldBe (expected.maxNorthing plusOrMinus 1e-3)
        state.library.shouldNotBeNull().transformation.shouldBeNull() // ETRS89 con ETRS89: sin cambio de datum
        state.canImport shouldBe true
    }

    @Test
    fun `S057 RF-9 con un cambio de datum de mas de 1 m la importacion espera a la confirmacion`() {
        val centre = GeoPoint(44.43, 26.1)
        val project = newProject(library(25835, CorrectionsDatum.ETRS89, centre))
        val vm = open(dxf(587_581.703122051 to 326_143.04251196247), project)

        vm.chooseLibrary(library(3844, CorrectionsDatum.ETRS89, centre))

        vm.state.value.library.shouldNotBeNull().transformation.shouldNotBeNull().needsConfirmation shouldBe true
        vm.state.value.outsideArea shouldBe false
        vm.state.value.canImport shouldBe false
        vm.confirmLibrary(true)
        vm.state.value.canImport shouldBe true
        val extent = vm.state.value.extent.shouldNotBeNull()
        extent.minEasting shouldBe (428_246.9291308614 plusOrMinus 1e-3) // pyproj: Stereo 70 → ETRS89 / UTM 35N
        extent.minNorthing shouldBe (4_919_996.427955405 plusOrMinus 1e-3)
    }

    @Test
    fun `S057 RF-8 con la rejilla de la app y una precision de menos de 1 m no hace falta confirmar`() {
        val project = newProject(ProjectCrs.ETRS89_UTM30)
        val vm = open(dxf(440_485.97294641007 + 109.4 to 4_472_259.7338854475 + 207.5), project)

        vm.chooseLibrary(library(23030, CorrectionsDatum.ETRS89, GeoPoint(40.4, -3.7)))

        val transformation = vm.state.value.library.shouldNotBeNull().transformation.shouldNotBeNull()
        transformation.needsConfirmation shouldBe false
        transformation.needsGrids shouldBe false
        vm.state.value.canImport shouldBe true
    }

    @Test
    fun `S057 RF-10 con una rejilla sin instalar no se importa aunque se confirme`() {
        val centre = GeoPoint(50.0, 8.5)
        val project = newProject(library(25832, CorrectionsDatum.ETRS89, centre))
        val vm = open(dxf(3_460_000.0 to 5_540_000.0), project)

        vm.chooseLibrary(library(31467, CorrectionsDatum.ETRS89, centre))
        vm.confirmLibrary(true)

        val transformation = vm.state.value.library.shouldNotBeNull().transformation.shouldNotBeNull()
        transformation.needsGrids shouldBe true
        transformation.missingGrids.isNotEmpty() shouldBe true
        vm.state.value.canImport shouldBe false
        vm.refreshLibrary() // sigue sin estar instalada
        vm.state.value.canImport shouldBe false
    }

    @Test
    fun `S057 RF-27 volver al SC del proyecto o a un UTM de España vuelve a permitir importar sin aviso`() {
        val project = newProject(ProjectCrs.ETRS89_UTM30)
        val vm = open(dxf(440_000.0 to 4_474_000.0), project)
        vm.chooseLibrary(library(3844, CorrectionsDatum.ETRS89, GeoPoint(40.4, -3.7)))

        vm.setSource(CadSourceCrs.Project)

        vm.state.value.options.source shouldBe CadSourceCrs.Project
        vm.state.value.canImport shouldBe true
    }
}
