package es.topophone.app.ui.projects

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.FakeMapController
import es.topophone.app.ui.map.FakeMapPreferences
import es.topophone.app.ui.map.RoverMarker
import es.topophone.app.ui.map.fakeMap
import es.topophone.app.ui.receiver.FakePreferences
import es.topophone.app.ui.receiver.nmea
import es.topophone.core.designsystem.theme.StatusTone
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.designsystem.theme.statusPaletteFor
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.transport.StreamTransport
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Rover en el mapa con el color de su solución y su círculo de precisión (S009/RF-16 a RF-18). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class RoverLayerTest {

    @get:Rule
    val compose = createComposeRule()

    private val project = Project(
        id = "p1",
        name = "Calle Mayor 12",
        author = "",
        description = "",
        crs = ProjectCrs.ED50_UTM30,
        linearUnit = LinearUnit.METRE,
        angularUnit = AngularUnit.DMS,
        correctionsDatum = CorrectionsDatum.ETRS89,
        folderId = null,
        createdAtMs = 1_000,
        modifiedAtMs = 1_000,
        openedAtMs = null,
        deletedAtMs = null,
    )

    private val map = FakeMapController()
    private val madrid = GeoPoint(40.416_801_234_5, -3.703_713_456_7)

    private fun color(tone: StatusTone) = statusPaletteFor(EffectiveTheme.LIGHT).colorsFor(tone).container.toArgb()

    @Test
    fun `S009 RF-16 RF-17 el rover se pinta en su posicion con el color de la solucion y su precision`() {
        var rover by mutableStateOf<Rover?>(Rover(madrid, SolutionStatus.FIX, accuracyM = 0.012))
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectViewScreen(ThemeState.DEFAULT, {}, ProjectViewUiState(project, rover = rover), onBack = {}, map = fakeMap(map))
            }
        }
        compose.waitForIdle()
        map.rover shouldBe RoverMarker(madrid, accuracyM = 0.012, colorArgb = color(StatusTone.POSITIVE))

        rover = Rover(madrid, SolutionStatus.FLOAT, accuracyM = null) // S009/RF-17: sin GST no hay círculo
        compose.waitForIdle()
        map.rover shouldBe RoverMarker(madrid, accuracyM = null, colorArgb = color(StatusTone.WARNING))

        rover = null // S009/RF-18
        compose.waitForIdle()
        map.rover.shouldBeNull()
        map.roverCalls shouldBe 3
    }

    // --- ViewModel ---

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(sessionScope, NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE })

    @After
    fun closeSession() {
        session.disconnect()
        sessionScope.cancel()
    }

    @Test
    fun `S009 RF-16 RF-17 RF-18 el ViewModel da el rover de la GGA tal cual, en cualquier SC, y lo quita sin datos`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java).build()
        val toApp = PipedOutputStream()
        try {
            val repository = RoomProjectRepository(db)
            val draft = ProjectDefaults().toDraft().copy(name = "Plano ED50").withCrs(ProjectCrs.ED50_UTM30)
            val id = runBlocking { (repository.create(draft) as SaveResult.Saved).id }
            session.connect(StreamTransport("reach-RS3", PipedInputStream(toApp, 64 * 1024), ByteArrayOutputStream()))
            toApp.write(nmea("GNGGA,101530.00,4025.0080741,N,00342.2228074,W,5,24,0.6,650.123,M,51.234,M,1.0,0001").toByteArray())
            toApp.write(nmea("GNGST,101530.00,0.012,0.010,0.007,45.0,0.008,0.006,0.015").toByteArray())
            toApp.flush()
            val vm =
                ProjectViewModel(repository, session, FakePreferences(), {
                    ProjectPositionCalculator()
                }, FakeMapPreferences(), samplePeriodMs = 0)
            vm.start(id)
            val collector = CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
            try {
                val rover = runBlocking { withTimeout(5_000) { vm.state.first { it.rover?.accuracyM != null } } }.rover.shouldNotBeNull()
                val gga = session.state.value.gga.shouldNotBeNull()
                rover.position shouldBe GeoPoint(gga.latitudeDeg, gga.longitudeDeg) // sin redondear, aunque el proyecto sea ED50
                rover.solution shouldBe SolutionStatus.FLOAT
                rover.accuracyM.shouldNotBeNull() shouldBe (0.010 plusOrMinus 1e-12)

                session.disconnect()

                runBlocking { withTimeout(5_000) { vm.state.first { it.rover == null } } }.rover.shouldBeNull()
            } finally {
                collector.cancel()
            }
        } finally {
            runCatching { toApp.close() }
            db.close()
            Dispatchers.resetMain()
        }
    }
}
