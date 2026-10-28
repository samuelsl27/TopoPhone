package es.topophone.app.ui.alignment

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.alignment.AlignmentImporter
import es.topophone.data.alignment.RoomAlignmentRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.hypot
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

/** Calcular y guardar un punto sobre un eje con un PK y un desplazamiento (S049/RF-20 a RF-22). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AlignmentPointViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val alignments = RoomAlignmentRepository(db)
    private val points = RoomPointRepository(db)
    private val dispatcher = UnconfinedTestDispatcher()
    private val projectId = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Obra")).shouldBeInstanceOf<SaveResult.Saved>().id
    }
    private val ids: Map<String, String> = runBlocking {
        val result = AlignmentImporter(alignments).importLandXml(
            projectId,
            ProjectCrs.ETRS89_UTM30,
            "ejes.xml",
            setOf(0, 1, 2),
            CadSourceCrs.Project,
            LinearUnit.METRE,
            { ByteArrayInputStream(AlignmentTestFiles.bytes("ejes.xml")) },
        )
        result.shouldBeInstanceOf<es.topophone.data.alignment.AlignmentImportResult.Done>().summary.imported.associate { it.name to it.id }
    }
    private val expected: List<List<String>> = run {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
        File(root, "fixtures/ejes/esperado-puntos.csv").readLines().drop(1).filter { it.isNotBlank() }.map { it.split(",") }
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun open(axis: String): AlignmentPointViewModel {
        val vm = AlignmentPointViewModel(alignments, projects, points, dispatcher)
        vm.start(projectId, ids.getValue(axis))
        runBlocking { withTimeout(10_000) { vm.state.first { it.loaded } } }
        return vm
    }

    private fun AlignmentPointViewModel.type(chainage: String, offset: String = "0") {
        setChainage(chainage)
        setOffset(offset)
    }

    @Test
    fun `S049 RF-20 al abrir se ve el eje con su PK inicial y final y un nombre propuesto`() {
        val state = open("Eje B").state.value

        state.alignmentName shouldBe "Eje B"
        state.startChainageM shouldBe 1000.5
        state.endChainageM shouldBe (1380.5 plusOrMinus 1e-9)
        state.hasProfile shouldBe true
        state.name shouldBe "1"
        state.reading.shouldBeNull()
    }

    @Test
    fun `S049 RF-20 el Este y el Norte de cada PK y desplazamiento coinciden con tools a menos de 1 mm`() {
        val vm = open("Eje A")
        var checked = 0
        for (row in expected.filter { it[0] == "Eje A" }) {
            vm.type(row[1], row[2])

            val reading = vm.state.value.reading.shouldNotBeNull()
            hypot(reading.point.easting - row[3].toDouble(), reading.point.northing - row[4].toDouble()) shouldBe (0.0 plusOrMinus 1e-6)
            Math.toDegrees(reading.point.azimuth) shouldBe (row[5].toDouble() plusOrMinus 1e-9)
            row[7].toDouble() shouldBe (reading.elevationM.shouldNotBeNull() plusOrMinus 1e-6)
            row[8].toDouble() shouldBe (reading.slopePercent.shouldNotBeNull() plusOrMinus 1e-6)
            if (row[6] != "inf") reading.point.radius shouldBe (row[6].toDouble() plusOrMinus row[6].toDouble() * 1e-9)
            checked++
        }
        (checked > 30) shouldBe true
    }

    @Test
    fun `S049 RF-20 un eje sin rasante no da cota ni pendiente`() {
        val vm = open("Eje C")

        vm.type("100", "2")

        vm.state.value.hasProfile shouldBe false
        vm.state.value.reading.shouldNotBeNull().elevationM.shouldBeNull()
        vm.state.value.reading!!.slopePercent.shouldBeNull()
    }

    @Test
    fun `S049 RF-20 el PK se puede escribir con kilometros y metros y el desplazamiento con coma`() {
        val vm = open("Eje B")

        vm.type("1+200", "-3,5")

        val reading = vm.state.value.reading.shouldNotBeNull()
        reading.point.chainage shouldBe 1200.0
        reading.point.offset shouldBe -3.5
    }

    @Test
    fun `S049 RF-21 un PK fuera del eje no calcula nada y se marca para decir el PK inicial y el final`() {
        val vm = open("Eje A")

        vm.type("500")
        vm.state.value.outside shouldBe true
        vm.state.value.reading.shouldBeNull()

        vm.type("-0.5")
        vm.state.value.outside shouldBe true
        vm.type("420")
        vm.state.value.outside shouldBe false
        vm.state.value.reading.shouldNotBeNull()
    }

    @Test
    fun `S049 RF-20 un PK o un desplazamiento que no son numeros se marcan y no calculan`() {
        val vm = open("Eje A")

        vm.type("abc", "0")
        vm.state.value.chainageInvalid shouldBe true
        vm.state.value.reading.shouldBeNull()

        vm.type("100", "x")
        vm.state.value.offsetInvalid shouldBe true
        vm.state.value.chainageInvalid shouldBe false
        vm.state.value.reading.shouldBeNull()

        vm.type("", "")
        vm.state.value.chainageInvalid shouldBe false
        vm.state.value.reading.shouldBeNull()
        vm.state.value.offsetInvalid shouldBe false
    }

    @Test
    fun `S049 RF-20 desplazamiento 0 es sobre el eje`() {
        val vm = open("Eje A")

        vm.type("200", "0")

        val point = vm.state.value.reading.shouldNotBeNull().point
        point.easting shouldBe point.axisEasting
        point.northing shouldBe point.axisNorthing
    }

    @Test
    fun `S049 RF-22 guardar punto lo guarda como Calculado sobre eje con su PK y su desplazamiento`() {
        val vm = open("Eje A")
        vm.type("200", "3,5")
        vm.state.value.canSave shouldBe true

        vm.save()
        runBlocking { withTimeout(10_000) { vm.state.first { it.savedName != null } } }

        val saved = runBlocking { points.points(projectId).first() }.single()
        saved.origin shouldBe PointOrigin.ON_ALIGNMENT
        saved.name shouldBe "1"
        saved.onAxis.shouldNotBeNull().alignmentName shouldBe "Eje A"
        saved.onAxis!!.chainageM shouldBe 200.0
        saved.onAxis!!.offsetM shouldBe (3.5 plusOrMinus 1e-12)
        saved.coordinates.easting.shouldNotBeNull()
        saved.hasHeight shouldBe true // el eje A trae rasante
    }

    @Test
    fun `S049 RF-22 un punto sobre un eje sin rasante se guarda sin altura`() {
        val vm = open("Eje C")
        vm.type("100", "0")

        vm.save()
        runBlocking { withTimeout(10_000) { vm.state.first { it.savedName != null } } }

        runBlocking { points.points(projectId).first() }.single().hasHeight shouldBe false
    }

    @Test
    fun `S049 RF-22 un nombre repetido no guarda y otro nombre si`() {
        val vm = open("Eje A")
        vm.type("100")
        vm.save()
        runBlocking { withTimeout(10_000) { vm.state.first { it.savedName != null } } }
        vm.another()
        runBlocking { withTimeout(10_000) { vm.state.first { it.savedName == null && it.name == "2" } } }

        vm.setName("1")
        vm.save()
        runBlocking { withTimeout(10_000) { vm.state.first { it.duplicateName } } }
        vm.setName("2")
        vm.save()
        runBlocking { withTimeout(10_000) { vm.state.first { it.savedName != null } } }

        runBlocking { points.points(projectId).first() }.map { it.name } shouldBe listOf("1", "2")
    }

    @Test
    fun `S049 RF-22 sin resultado no se puede guardar y un nombre en blanco tampoco`() {
        val vm = open("Eje A")
        vm.state.value.canSave shouldBe false

        vm.type("100")
        vm.setName("  ")

        vm.state.value.canSave shouldBe false
        vm.save()
        runBlocking { points.points(projectId).first() } shouldBe emptyList()
    }

    @Test
    fun `S049 RF-22 el punto guardado se puede replantear como cualquier otro porque es un punto del proyecto`() {
        val vm = open("Eje A")
        vm.type("200", "3,5")
        vm.save()
        runBlocking { withTimeout(10_000) { vm.state.first { it.savedName != null } } }

        val saved = runBlocking { points.points(projectId).first() }.single()

        saved.coordinates.easting.shouldNotBeNull()
        saved.coordinates.northing.shouldNotBeNull()
        saved.hidden shouldBe false
        saved.deletedAtMs.shouldBeNull()
    }
}
