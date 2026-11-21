package es.topophone.app.ui.alignment

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.alignment.AlignmentImporter
import es.topophone.data.alignment.RoomAlignmentRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.doubles.plusOrMinus
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

/** Importar los ejes de un LandXML con un SC de origen de la biblioteca: origen, eje en el SC del proyecto y aviso (S057/RF-1, RF-3, RF-14). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AlignmentImportLibraryTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val alignments = RoomAlignmentRepository(db)
    private val dispatcher = UnconfinedTestDispatcher()
    private val projectId = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Obra")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun open(name: String): AlignmentImportViewModel {
        val vm = AlignmentImportViewModel(AlignmentImporter(alignments), projects, dispatcher)
        vm.start(projectId, AlignmentTestFiles.source(name))
        vm.settled()
        return vm
    }

    private fun library(epsg: Int, at: GeoPoint = GeoPoint(40.4, -3.7)): LibraryCrs {
        val definition = (CrsCatalog.bundled.build(epsg, CorrectionsDatum.ETRS89, at) as CrsBuildResult.Ready).definition
        return LibraryCrs(definition, CorrectionsDatum.ETRS89)
    }

    private fun AlignmentImportViewModel.importAndWait(): AlignmentImportUiState = runBlocking {
        import()
        withTimeout(10_000) { state.first { it.stage == AlignmentImportStage.RESULT || it.failure != null } }
    }

    @Test
    fun `S057 RF-14 el eje en un SC de la biblioteca se guarda en el del proyecto igual que con el UTM de Espana`() {
        val viaUtm = open("ejes.xml")
        viaUtm.setSource(CadSourceCrs.Utm(GeodeticDatum.ETRS89, 31))
        val expected = viaUtm.importAndWait().summary.shouldNotBeNull().imported

        val vm = open("ejes.xml")
        vm.chooseLibrary(library(25831))
        val summary = vm.importAndWait().summary.shouldNotBeNull()

        vm.state.value.source.shouldBeInstanceOf<CadSourceCrs.Library>()
        summary.imported.all { it.approximated } shouldBe true
        summary.imported.size shouldBe expected.size
        summary.imported.zip(expected).forEach { (lib, utm) -> lib.lengthM shouldBe (utm.lengthM plusOrMinus 1e-6) }
    }

    @Test
    fun `S057 RF-9 el aviso de precision bloquea la importacion hasta confirmarlo`() {
        val vm = open("ejes.xml")

        vm.chooseLibrary(library(3844, GeoPoint(44.43, 26.1)))

        vm.state.value.canImport shouldBe false
        vm.confirmLibrary(true)
        vm.state.value.canImport shouldBe true
    }

    @Test
    fun `S057 RF-27 volver al SC del proyecto permite importar sin aviso`() {
        val vm = open("ejes.xml")
        vm.chooseLibrary(library(3844, GeoPoint(44.43, 26.1)))

        vm.setSource(CadSourceCrs.Project)

        vm.state.value.canImport shouldBe true
    }
}
