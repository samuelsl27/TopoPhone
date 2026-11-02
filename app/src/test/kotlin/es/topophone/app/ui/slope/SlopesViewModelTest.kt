package es.topophone.app.ui.slope

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.alignment.AlignmentTestFiles
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.slope.ManualEdge
import es.topophone.core.model.alignment.slope.SlopeIssue
import es.topophone.core.model.alignment.slope.SlopeSide
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.alignment.AlignmentImporter
import es.topophone.data.alignment.RoomAlignmentRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.data.slope.RoomSlopeRepository
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
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

/** Definir, guardar y rechazar los taludes de un eje (S051/RF-1 a RF-3). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SlopesViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val alignments = RoomAlignmentRepository(db)
    private val slopes = RoomSlopeRepository(db)
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun open(unit: LinearUnit = LinearUnit.METRE): SlopesViewModel {
        val projectId = runBlocking {
            projects.create(ProjectDefaults().toDraft().copy(name = "Obra", linearUnit = unit)).shouldBeInstanceOf<SaveResult.Saved>().id
        }
        val axis = runBlocking {
            AlignmentImporter(alignments).importLandXml(
                projectId,
                ProjectCrs.ETRS89_UTM30,
                "ejes.xml",
                setOf(2),
                CadSourceCrs.Project,
                LinearUnit.METRE,
                { ByteArrayInputStream(AlignmentTestFiles.bytes("ejes.xml")) },
            )
            alignments.summaries(projectId).first().single().id
        }
        val vm = SlopesViewModel(slopes, alignments, projects, dispatcher)
        vm.start(projectId, axis)
        runBlocking { withTimeout(10_000) { vm.state.first { !it.loading } } }
        return vm
    }

    private fun SlopesViewModel.fill(from: String = "0", to: String = "100", cut: String = "1,5", fill: String = "2", code: String = "") {
        startDraft()
        updateDraft { it.copy(from = from, to = to, cut = cut, fill = fill, edgeCode = code) }
    }

    private fun SlopesViewModel.saveAndWait() {
        save()
        runBlocking { withTimeout(10_000) { state.first { !it.saving } } }
    }

    private fun SlopesViewModel.awaitRules(count: Int) {
        runBlocking { withTimeout(10_000) { state.first { it.rules.size == count } } }
    }

    @Test
    fun `S051 RF-1 un talud por lado y rango con sus dos pendientes y el codigo del borde se guarda`() {
        val vm = open()
        vm.fill(code = "CD")

        vm.saveAndWait()
        vm.awaitRules(1)

        vm.state.value.draft.shouldBeNull()
        val rule = vm.state.value.rules.single().rule
        rule.side shouldBe SlopeSide.RIGHT
        rule.cutRatio shouldBe 1.5
        rule.fillRatio shouldBe 2.0
        rule.edgeCode shouldBe "CD"
        rule.manualEdge.shouldBeNull()
    }

    @Test
    fun `S051 RF-1 sin codigo el borde es el exterior de la seccion y los PK se escriben en la unidad del proyecto`() {
        val vm = open(LinearUnit.INTERNATIONAL_FOOT)
        vm.fill(from = "0", to = "100")
        vm.updateDraft { it.copy(side = SlopeSide.LEFT) }

        vm.saveAndWait()
        vm.awaitRules(1)

        val rule = vm.state.value.rules.single().rule
        rule.edgeCode.shouldBeNull()
        rule.side shouldBe SlopeSide.LEFT
        rule.toChainage shouldBe (30.48 plusOrMinus 1e-9)
    }

    @Test
    fun `S051 RF-2 el borde manual lleva el signo del lado y su cota absoluta o relativa`() {
        val vm = open()
        vm.fill(code = "CD")
        vm.updateDraft { it.copy(side = SlopeSide.LEFT, manual = true, manualOffset = "4", manualHeight = "-0,5", manualRelative = true) }

        vm.saveAndWait()
        vm.awaitRules(1)

        val rule = vm.state.value.rules.single().rule
        rule.manualEdge shouldBe ManualEdge(-4.0, -0.5, HeightKind.RELATIVE)
        rule.edgeCode.shouldBeNull() // con borde manual no hay codigo
    }

    @Test
    fun `S051 RF-3 un numero mal escrito dice el campo y no guarda`() {
        val vm = open()
        vm.fill(cut = "abc")

        vm.saveAndWait()

        vm.state.value.problem shouldBe SlopesProblem.InvalidNumber(SlopeField.CUT)
        vm.state.value.draft.shouldNotBeNull()
        vm.state.value.rules shouldBe emptyList()
    }

    @Test
    fun `S051 RF-3 una pendiente cero o negativa se rechaza con el lado y el valor`() {
        val vm = open()
        vm.fill(fill = "0")

        vm.saveAndWait()
        vm.state.value.problem shouldBe SlopesProblem.Rejected(SlopeIssue.InvalidRatio(SlopeSide.RIGHT, 0.0))

        vm.updateDraft { it.copy(fill = "2", cut = "-1") }
        vm.saveAndWait()
        vm.state.value.problem shouldBe SlopesProblem.Rejected(SlopeIssue.InvalidRatio(SlopeSide.RIGHT, -1.0))
    }

    @Test
    fun `S051 RF-3 dos rangos que se pisan en un lado o un PK fuera del eje se rechazan`() {
        val vm = open()
        vm.fill(from = "0", to = "100")
        vm.saveAndWait()
        vm.awaitRules(1)

        vm.fill(from = "50", to = "150")
        vm.saveAndWait()
        vm.state.value.problem shouldBe SlopesProblem.Rejected(SlopeIssue.OverlappingRanges(SlopeSide.RIGHT, 50.0, 150.0))

        vm.fill(from = "100", to = "500")
        vm.saveAndWait()
        vm.state.value.problem shouldBe SlopesProblem.Rejected(SlopeIssue.ChainageOutsideAxis(500.0))
        vm.state.value.rules.size shouldBe 1
    }

    @Test
    fun `S051 RF-1 se pueden tener los dos lados y borrar uno sin tocar el otro`() {
        val vm = open()
        vm.fill()
        vm.saveAndWait()
        vm.awaitRules(1)
        vm.fill()
        vm.updateDraft { it.copy(side = SlopeSide.LEFT) }
        vm.saveAndWait()
        vm.awaitRules(2)

        vm.delete(vm.state.value.rules.first { it.rule.side == SlopeSide.LEFT }.id)

        runBlocking { withTimeout(10_000) { vm.state.first { it.rules.size == 1 } } }
        vm.state.value.rules.single().rule.side shouldBe SlopeSide.RIGHT
    }

    @Test
    fun `S051 RF-3 cancelar descarta lo escrito`() {
        val vm = open()
        vm.fill(cut = "abc")
        vm.saveAndWait()

        vm.cancelDraft()

        vm.state.value.draft.shouldBeNull()
        vm.state.value.problem.shouldBeNull()
    }
}
