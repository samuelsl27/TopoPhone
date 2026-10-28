package es.topophone.app.ui.alignment

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.alignment.AlignmentIssue
import es.topophone.core.model.alignment.ProfileIssue
import es.topophone.core.model.alignment.ProfileOmission
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Sistema y unidad del eje, ámbito y resumen de lo importado e ignorado (S049/RF-11, RF-12, RF-13). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class AlignmentImportCrsTest {

    @get:Rule
    val compose = createComposeRule()

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

    private fun AlignmentImportViewModel.importAndWait(): AlignmentImportUiState = runBlocking {
        import()
        withTimeout(10_000) { state.first { it.stage == AlignmentImportStage.RESULT || it.failure != null } }
    }

    @Test
    fun `S049 RF-11 en pies el eje se guarda en metros con el mismo trazado`() {
        val vm = open("eje-a-pies.xml")
        vm.state.value.unit shouldBe LinearUnit.INTERNATIONAL_FOOT

        val summary = vm.importAndWait().summary.shouldNotBeNull()

        summary.imported.single().lengthM shouldBe (420.0 plusOrMinus 1e-9)
        val axis = runBlocking { alignments.alignment(summary.imported.single().id) }.shouldNotBeNull()
        axis.elements.first().startEasting shouldBe (440000.0 plusOrMinus 1e-6)
        axis.profile.shouldNotBeNull().vertices[1].elevation shouldBe (103.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S049 RF-12 una unidad equivocada deja el eje fuera del ambito y no se importa`() {
        val vm = open("ejes.xml")
        vm.setUnit(LinearUnit.INTERNATIONAL_FOOT) // las coordenadas del fichero son metros, no pies

        val summary = vm.importAndWait().summary.shouldNotBeNull()

        summary.imported shouldBe emptyList()
        summary.ignored.map { it.issue }.toSet() shouldBe setOf(AlignmentIssue.OutsideCrsArea)
    }

    @Test
    fun `S049 RF-11 otro sistema UTM guarda el eje aproximado y lo avisa en el resumen`() {
        val vm = open("ejes.xml")
        vm.setSource(CadSourceCrs.Utm(GeodeticDatum.ETRS89, 31))

        val summary = vm.importAndWait().summary.shouldNotBeNull()

        summary.imported.all { it.approximated } shouldBe true
        runBlocking { alignments.summaries(projectId).first() }.all { it.approximated } shouldBe true
    }

    @Test
    fun `S049 RF-12 un eje con vertices fuera del ambito no se importa y el resumen dice el motivo`() {
        val vm = open("ejes.xml")
        vm.setSource(CadSourceCrs.Utm(GeodeticDatum.ETRS89, 36))

        val summary = vm.importAndWait().summary.shouldNotBeNull()

        summary.imported shouldBe emptyList()
        summary.ignored.map { it.issue }.toSet() shouldBe setOf(AlignmentIssue.OutsideCrsArea)
        runBlocking { alignments.summaries(projectId).first() } shouldBe emptyList()
    }

    @Test
    fun `S049 RF-13 el resumen cuenta lo importado y lo ignorado de un mismo fichero`() {
        val vm = open("eje-hueco.xml")

        val summary = vm.importAndWait().summary.shouldNotBeNull()

        summary.imported.size + summary.ignored.size shouldBe 1
        summary.ignored.single().name shouldBe "Eje A"
    }

    @Test
    fun `S049 RF-13 importar dos veces el mismo eje crea otro aparte`() {
        open("ejes.xml").also { it.toggle(1) }.also { it.toggle(2) }.importAndWait()
        open("ejes.xml").also { it.toggle(1) }.also { it.toggle(2) }.importAndWait()

        runBlocking { alignments.summaries(projectId).first() }.map { it.name } shouldBe listOf("Eje A", "Eje A")
    }

    @Composable
    private fun Texts(issues: List<AlignmentIssue>, omissions: List<ProfileOmission>) {
        androidx.compose.foundation.layout.Column {
            issues.forEach { androidx.compose.material3.Text(alignmentIssueText(it)) }
            omissions.forEach { androidx.compose.material3.Text(profileOmissionText(it, LinearUnit.METRE)) }
        }
    }

    @Test
    fun `S049 RF-13 cada motivo de ignorar y cada rasante omitida tiene su texto en espanol`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                Texts(
                    listOf(
                        AlignmentIssue.Gap(2, 0.05),
                        AlignmentIssue.UnsupportedElement(3, "IrregularLine"),
                        AlignmentIssue.InconsistentElement(1, 0.02),
                        AlignmentIssue.StationEquation,
                        AlignmentIssue.NonCircularCurve(4),
                        AlignmentIssue.NotAPolyline,
                        AlignmentIssue.GeographicProject,
                        AlignmentIssue.OutsideCrsArea,
                    ),
                    listOf(
                        ProfileOmission.MultipleProfiles,
                        ProfileOmission.UnsupportedCurve("UnsymParaCurve"),
                        ProfileOmission.Invalid(ProfileIssue.RepeatedChainage(3, 100.0)),
                        ProfileOmission.Invalid(ProfileIssue.OutsideAxis(2, 900.0)),
                        ProfileOmission.Invalid(ProfileIssue.CurveDoesNotFit(2, 50.0)),
                    ),
                )
            }
        }

        compose.onNodeWithText("hay 5,0 cm de hueco antes del tramo 2").assertExists()
        compose.onNodeWithText("el tramo 3 es de un tipo no admitido («IrregularLine»)").assertExists()
        compose.onNodeWithText("el final del tramo 1 se aparta 2,0 cm de lo que declara el fichero").assertExists()
        compose.onNodeWithText("tiene ecuaciones de PK, que no se admiten").assertExists()
        compose.onNodeWithText("el trozo que empieza en el vértice 4 no es un arco de circunferencia").assertExists()
        compose.onNodeWithText("no es una polilínea con longitud").assertExists()
        compose.onNodeWithText("el proyecto está en coordenadas geográficas", substring = true).assertExists()
        compose.onNodeWithText("fuera del ámbito del sistema de coordenadas del proyecto", substring = true).assertExists()
        compose.onNodeWithText("La rasante no se ha importado: el fichero trae más de una.").assertExists()
        compose.onNodeWithText("el acuerdo vertical «UnsymParaCurve» no se admite", substring = true).assertExists()
        compose.onNodeWithText("el vértice 3 repite el PK 0+100,00", substring = true).assertExists()
        compose.onNodeWithText("el vértice 2 (PK 0+900,00) está fuera del eje", substring = true).assertExists()
        compose.onNodeWithText("el acuerdo del vértice 2 (PK 0+050,00) no cabe", substring = true).assertExists()
        ProjectCrs.ETRS89_UTM30.utmZone shouldBe 30
    }
}
