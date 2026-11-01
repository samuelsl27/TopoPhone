package es.topophone.app.ui.alignment

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.receiver.CurrentGear
import es.topophone.app.ui.receiver.nmea
import es.topophone.app.ui.receiver.repositoryRoot
import es.topophone.app.ui.stakeout.GuidanceOff
import es.topophone.core.model.alignment.section.SectionTemplate
import es.topophone.core.model.alignment.section.TemplateSegment
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.point.AxisHeightRef
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.surface.Surface
import es.topophone.core.model.surface.SurfaceExtent
import es.topophone.core.model.surface.SurfaceGuide
import es.topophone.core.model.surface.SurfaceHeight
import es.topophone.core.model.surface.SurfaceHeightSource
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.AxisAnalysisValue
import es.topophone.core.survey.AxisHeightDiff
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.alignment.AlignmentImportResult
import es.topophone.data.alignment.AlignmentImporter
import es.topophone.data.alignment.RoomAlignmentRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.CollectorPreferences
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.data.surface.SurfaceGuides
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.transport.StreamTransport
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Analizar la posición del jalón respecto a un eje: el PK, el desplazamiento y la diferencia de cota con la rasante o con la superficie
 * activa, a elegir, y el aviso de que no hay proyección sobre el eje (S054/RF-12, RF-14; constitución nº 2).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AxisAnalysisViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val projects = RoomProjectRepository(db)
    private val alignments = RoomAlignmentRepository(db)
    private val sections = es.topophone.data.section.RoomSectionRepository(db)
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(
        sessionScope,
        NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE },
        noDataTimeoutMs = 60_000,
        watchdogPeriodMs = 1_000,
    )
    private val toApp = PipedOutputStream()
    private val calculator = ProjectPositionCalculator()
    private val surface = MutableStateFlow<SurfaceGuide?>(null)
    private val viewModels = mutableListOf<AxisAnalysisViewModel>()

    private class FakeCollectorPreferences : CollectorPreferences {
        override val settings = MutableStateFlow(CollectorSettings())

        override suspend fun save(settings: CollectorSettings) {
            this.settings.value = settings
        }
    }

    private val projectId: String = runBlocking {
        // Alturas elipsoidales: la altura de la GGA es la de la punta más el jalón, sin ondulación del geoide de por medio.
        (projects.create(ProjectDefaults().toDraft().copy(name = "Obra", heightSystem = HeightSystem.ELLIPSOIDAL)) as SaveResult.Saved).id
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
        result.shouldBeInstanceOf<AlignmentImportResult.Done>().summary.imported.associate { it.name to it.id }
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        runCatching { toApp.close() }
        session.disconnect()
        sessionScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun surfaceGuide(height: SurfaceHeight, offset: Double = 0.0) = SurfaceGuide(
        Surface(
            "s1", projectId, "terreno", "obra.xml", 1L, CadSourceCrs.Project, LinearUnit.METRE,
            SurfaceExtent(0.0, 1.0e7, 0.0, 1.0e7), 90.0, 120.0, 10, 8, 0,
        ),
        SurfaceHeightSource { _, _ -> height },
        offset,
    )

    private fun viewModel(): AxisAnalysisViewModel {
        val vm = AxisAnalysisViewModel(
            projects,
            alignments,
            sections,
            FakeCollectorPreferences(),
            CurrentGear.NONE,
            session,
            { calculator },
            surfaces = SurfaceGuides { surface },
        )
        vm.intervalMs = 0
        viewModels += vm
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        return vm
    }

    private fun await(vm: AxisAnalysisViewModel, predicate: (AxisAnalysisUiState) -> Boolean): AxisAnalysisUiState = runBlocking {
        try {
            withTimeout(10_000) { vm.state.first(predicate) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("El análisis no llegó al estado esperado; se quedó en ${vm.state.value}", e)
        }
    }

    private fun open(axis: String): AxisAnalysisViewModel {
        val vm = viewModel()
        vm.open(projectId, ids.getValue(axis))
        await(vm) { it.loaded && it.project != null }
        return vm
    }

    private class ReplayEpoch(
        val lat: String,
        val lon: String,
        val altitude: String,
        val pk: Double,
        val offset: Double,
        val height: Double,
    )

    /** Las épocas de `fixtures/ejes/replanteo/reproduccion-eje-a.csv`: su posición, y el PK y el desplazamiento con que se construyó. */
    private fun replay(): List<ReplayEpoch> =
        File(repositoryRoot, "fixtures/ejes/replanteo/reproduccion-eje-a.csv").readLines().drop(1).filter { it.isNotBlank() }.map {
            val c = it.split(",")
            ReplayEpoch(
                angle(c[3].toDouble(), 2),
                angle(-c[4].toDouble(), 3),
                "%.3f".format(Locale.ROOT, c[5].toDouble() + 1.934),
                c[1].toDouble(),
                c[2].toDouble(),
                c[5].toDouble(),
            )
        }

    private fun angle(degrees: Double, degreeDigits: Int): String {
        val whole = degrees.toInt()
        return "%0${degreeDigits}d%010.7f".format(Locale.ROOT, whole, (degrees - whole) * 60.0)
    }

    private fun sendEpoch(second: Int, lat: String, lon: String, altitude: String) {
        val t = 36_930 + second
        val time = "%02d%02d%02d.00".format(t / 3_600, t / 60 % 60, t % 60)
        toApp.write(nmea("GNGGA,$time,$lat,N,$lon,W,4,24,0.6,$altitude,M,0.000,M,1.0,0001").toByteArray())
        toApp.write(nmea("GNGST,$time,0.012,0.010,0.007,45.0,0.008,0.006,0.015").toByteArray())
        toApp.flush()
    }

    private fun connect(lat: String = "4025.0080000", lon: String = "00342.2228000", altitude: String = "701.357") {
        session.connect(StreamTransport("reach-RS3", PipedInputStream(toApp, 64 * 1024), ByteArrayOutputStream()))
        sendEpoch(0, lat, lon, altitude)
        sendEpoch(1, lat, lon, altitude)
        runBlocking { withTimeout(5_000) { session.state.first { it.connection is ConnectionState.Receiving && it.epoch != null } } }
    }

    @Test
    fun `S054 RF-12 al abrir se ve el eje y se elige la rasante si la tiene`() {
        val state = open("Eje A").state.value

        state.alignmentName shouldBe "Eje A"
        state.hasProfile shouldBe true
        state.hasSurface shouldBe false
        state.reference shouldBe AxisHeightRef.PROFILE
        state.canChoose shouldBe false
    }

    @Test
    fun `S054 RF-12 un eje sin rasante y sin superficie solo da PK y desplazamiento`() {
        val state = open("Eje C").state.value

        state.reference.shouldBeNull()
        state.canChoose shouldBe false
    }

    @Test
    fun `S054 RF-12 sin rasante la referencia es la superficie activa`() {
        surface.value = surfaceGuide(SurfaceHeight.Value(100.0))
        val vm = open("Eje C")

        await(vm) { it.hasSurface }.reference shouldBe AxisHeightRef.SURFACE
    }

    @Test
    fun `S054 RF-12 con rasante y superficie se ofrece elegir y empieza la rasante`() {
        surface.value = surfaceGuide(SurfaceHeight.Value(100.0))
        val vm = open("Eje A")

        val state = await(vm) { it.hasSurface }

        state.canChoose shouldBe true
        state.reference shouldBe AxisHeightRef.PROFILE
        vm.setReference(AxisHeightRef.SURFACE) shouldBe true
        await(vm) { it.reference == AxisHeightRef.SURFACE }
        vm.setReference(AxisHeightRef.PROFILE) shouldBe true
        await(vm) { it.reference == AxisHeightRef.PROFILE }
    }

    @Test
    fun `S054 RF-12 elegir una referencia que no existe no se aplica`() {
        val vm = open("Eje C")

        vm.setReference(AxisHeightRef.PROFILE) shouldBe false
        vm.setReference(AxisHeightRef.SURFACE) shouldBe false
        vm.state.value.reference.shouldBeNull()
    }

    @Test
    fun `S054 RF-12 el PK el desplazamiento y la diferencia de cota de la punta siguen a la sesion`() {
        val vm = open("Eje A")
        val epoch = replay()[16] // PK 160 y 3,5 + 0,8 sen(16/3) m a la derecha
        connect(epoch.lat, epoch.lon, epoch.altitude)

        val state = await(vm) { it.analysis is AxisAnalysisValue.Projected }

        val value = state.analysis.shouldBeInstanceOf<AxisAnalysisValue.Projected>()
        value.chainageM shouldBe (epoch.pk plusOrMinus 2e-3)
        value.offsetM shouldBe (epoch.offset plusOrMinus 2e-3)
        value.heightRef shouldBe AxisHeightRef.PROFILE
        val diff = value.height.shouldBeInstanceOf<AxisHeightDiff.Diff>()
        diff.dHeightM.shouldNotBeNull() shouldBe (epoch.height - diff.referenceM plusOrMinus 2e-3)
        state.off.shouldBeNull()
    }

    @Test
    fun `S054 RF-12 con la superficie la diferencia es la cota de la punta menos la de la superficie con su desplazamiento`() {
        surface.value = surfaceGuide(SurfaceHeight.Value(100.0), offset = 0.25)
        val vm = open("Eje A")
        vm.setReference(AxisHeightRef.SURFACE)
        val epoch = replay()[16]
        connect(epoch.lat, epoch.lon, epoch.altitude)

        val state = await(vm) { it.analysis is AxisAnalysisValue.Projected && it.reference == AxisHeightRef.SURFACE }

        val diff = state.analysis.shouldBeInstanceOf<AxisAnalysisValue.Projected>().height.shouldBeInstanceOf<AxisHeightDiff.Diff>()
        diff.referenceM shouldBe 100.25
        diff.dHeightM.shouldNotBeNull() shouldBe (epoch.height - 100.25 plusOrMinus 2e-3)
    }

    @Test
    fun `S054 RF-12 fuera de la superficie lo dice y no da diferencia de cota`() {
        surface.value = surfaceGuide(SurfaceHeight.Outside)
        val vm = open("Eje A")
        vm.setReference(AxisHeightRef.SURFACE)
        val epoch = replay()[16]
        connect(epoch.lat, epoch.lon, epoch.altitude)

        await(vm) { it.analysis is AxisAnalysisValue.Projected && it.reference == AxisHeightRef.SURFACE }
            .analysis.shouldBeInstanceOf<AxisAnalysisValue.Projected>().height shouldBe AxisHeightDiff.OutsideSurface
    }

    @Test
    fun `S054 RF-14 una punta antes del inicio del eje no tiene PK ni desplazamiento`() {
        val vm = open("Eje C") // empieza en el Este 441000; la punta de las épocas está en el 440290
        connect()

        await(vm) { it.analysis == AxisAnalysisValue.NoProjection }.off.shouldBeNull()
    }

    @Test
    fun `S054 RF-12 sin datos de la sesion no hay analisis y se dice por que`() {
        val vm = open("Eje A")

        val state = vm.state.value

        state.analysis.shouldBeNull()
        state.off shouldBe GuidanceOff.NO_POSITION
    }

    @Test
    fun `S054 RF-12 cerrar el analisis lo deja sin eje`() {
        val vm = open("Eje A")

        vm.close()

        await(vm) { !it.loaded }.analysis.shouldBeNull()
    }

    // El eje A en el PK 150 y a 3,5 m a la derecha, de esperado-puntos.csv de tools: el Este, el Norte y la cota de la rasante.
    private fun measuredAt(height: Double?) = PointCoordinates(
        GeodeticPosition(40.4168, -3.7038, 700.0),
        440131.7435931394,
        4474071.448550662,
        height,
    )

    @Test
    fun `S054 RF-13 la referencia de una medida lleva el eje, el PK, el desplazamiento y la diferencia de cota con la rasante`() {
        val vm = open("Eje A")

        val axis = vm.referenceProvider.at(measuredAt(102.58333333333333 + 0.12)).shouldNotBeNull()

        axis.alignmentName shouldBe "Eje A"
        axis.chainageM shouldBe (150.0 plusOrMinus 1e-4)
        axis.offsetM shouldBe (3.5 plusOrMinus 1e-4)
        axis.dHeightM.shouldNotBeNull() shouldBe (0.12 plusOrMinus 1e-4)
        axis.heightRef shouldBe AxisHeightRef.PROFILE
    }

    @Test
    fun `S054 RF-13 con la superficie elegida la diferencia es contra la superficie con su desplazamiento vertical`() {
        surface.value = surfaceGuide(SurfaceHeight.Value(100.0), offset = 0.25)
        val vm = open("Eje A")
        vm.setReference(AxisHeightRef.SURFACE)

        val axis = vm.referenceProvider.at(measuredAt(101.0)).shouldNotBeNull()

        axis.dHeightM.shouldNotBeNull() shouldBe (0.75 plusOrMinus 1e-9)
        axis.heightRef shouldBe AxisHeightRef.SURFACE
    }

    @Test
    fun `S054 RF-13 fuera de la superficie o sin altura se guarda el eje con la diferencia y su referencia vacias`() {
        surface.value = surfaceGuide(SurfaceHeight.Outside)
        val vm = open("Eje C")
        vm.setReference(AxisHeightRef.SURFACE)
        val outside = vm.referenceProvider.at(
            PointCoordinates(GeodeticPosition(40.4, -3.7, 700.0), 441100.0, 4474010.0, 5.0),
        ).shouldNotBeNull()
        outside.dHeightM.shouldBeNull()
        outside.heightRef.shouldBeNull()
        outside.chainageM shouldBe (100.0 plusOrMinus 1e-9)
        outside.offsetM shouldBe (-10.0 plusOrMinus 1e-9)

        surface.value = null
        val withoutHeight = open("Eje A").referenceProvider.at(measuredAt(null)).shouldNotBeNull()
        withoutHeight.dHeightM.shouldBeNull()
        withoutHeight.heightRef.shouldBeNull()
        withoutHeight.chainageM shouldBe (150.0 plusOrMinus 1e-4)
    }

    @Test
    fun `S054 RF-14 una medida sin proyeccion sobre el eje no lleva referencia`() {
        val vm = open("Eje C")

        vm.referenceProvider.at(PointCoordinates(GeodeticPosition(40.4, -3.7, 700.0), 440990.0, 4474000.0, 5.0)).shouldBeNull()
    }

    @Test
    fun `S054 RF-13 sin eje abierto no hay referencia`() {
        val vm = open("Eje A")
        vm.close()

        vm.referenceProvider.at(measuredAt(102.0)).shouldBeNull()
    }

    // ---- S050: referencia «Sección»

    private fun addSection(axis: String, width: Double) = runBlocking {
        sections.addTemplate(
            ids.getValue(axis),
            SectionTemplate(
                0.0,
                if (axis == "Eje A") 420.0 else 200.0,
                "EJE",
                listOf(TemplateSegment("BI", width, 0.02, false)),
                listOf(TemplateSegment("BD", width, 0.02, false)),
            ),
        )
    }

    @Test
    fun `S050 RF-20 con rasante y seccion se ofrece elegir Seccion y empieza la rasante`() {
        addSection("Eje A", 8.0)
        val vm = open("Eje A")

        val state = vm.state.value

        state.hasSection shouldBe true
        state.canChoose shouldBe true
        state.reference shouldBe AxisHeightRef.PROFILE
        vm.setReference(AxisHeightRef.SECTION) shouldBe true
        await(vm) { it.reference == AxisHeightRef.SECTION }
    }

    @Test
    fun `S050 RF-20 un eje sin secciones no ofrece Seccion y elegirla no se aplica`() {
        val vm = open("Eje A")

        vm.state.value.hasSection shouldBe false
        vm.setReference(AxisHeightRef.SECTION) shouldBe false
    }

    @Test
    fun `S050 RF-20 sin rasante ni superficie pero con seccion la referencia es la seccion`() {
        addSection("Eje C", 8.0)

        val state = open("Eje C").state.value

        state.hasProfile shouldBe false
        state.reference shouldBe AxisHeightRef.SECTION
    }

    @Test
    fun `S050 RF-20 la diferencia de cota en vivo es contra la cota de la seccion en el desplazamiento de la punta`() {
        addSection("Eje A", 8.0)
        val vm = open("Eje A")
        vm.setReference(AxisHeightRef.SECTION)
        val epoch = replay()[16]
        connect(epoch.lat, epoch.lon, epoch.altitude)

        val state = await(vm) { it.analysis is AxisAnalysisValue.Projected && it.reference == AxisHeightRef.SECTION }

        val value = state.analysis.shouldBeInstanceOf<AxisAnalysisValue.Projected>()
        value.heightRef shouldBe AxisHeightRef.SECTION
        val diff = value.height.shouldBeInstanceOf<AxisHeightDiff.Diff>()
        // La rasante en ese PK menos 2 % del desplazamiento de la punta.
        diff.dHeightM.shouldNotBeNull() shouldBe (epoch.height - diff.referenceM plusOrMinus 2e-3)
        (diff.referenceM < 105.0 && diff.referenceM > 95.0) shouldBe true
    }

    @Test
    fun `S050 RF-20 una medida contra la seccion guarda la diferencia con la cota de la seccion y la referencia Seccion`() {
        addSection("Eje A", 8.0)
        val vm = open("Eje A")
        vm.setReference(AxisHeightRef.SECTION)

        // PK 150, 3,5 m a la derecha: rasante 102,58333 menos 2 % de 3,5 m.
        val axis = vm.referenceProvider.at(measuredAt(102.58333333333333 - 0.07 + 0.12)).shouldNotBeNull()

        axis.dHeightM.shouldNotBeNull() shouldBe (0.12 plusOrMinus 1e-4)
        axis.heightRef shouldBe AxisHeightRef.SECTION
    }

    @Test
    fun `S050 RF-20 una medida mas alla de los puntos exteriores de la seccion se guarda sin diferencia ni referencia`() {
        addSection("Eje A", 3.0) // la medida cae a 3,5 m: fuera de la sección
        val vm = open("Eje A")
        vm.setReference(AxisHeightRef.SECTION)

        val axis = vm.referenceProvider.at(measuredAt(102.0)).shouldNotBeNull()

        axis.dHeightM.shouldBeNull()
        axis.heightRef.shouldBeNull()
        axis.chainageM shouldBe (150.0 plusOrMinus 1e-4)
    }
}
