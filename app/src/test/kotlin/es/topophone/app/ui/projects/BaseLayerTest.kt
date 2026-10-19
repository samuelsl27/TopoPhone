package es.topophone.app.ui.projects

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.FakeMapController
import es.topophone.app.ui.map.fakeMap
import es.topophone.app.ui.receiver.nmea
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.geodesy.GeoPoint
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

/** La base en el mapa del proyecto, con un símbolo propio, mientras haya posición de la base (S015/RF-19). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class BaseLayerTest {

    @get:Rule
    val compose = createComposeRule()

    private val project = Project(
        id = "p1",
        name = "Calle Mayor 12",
        author = "",
        description = "",
        crs = ProjectCrs.ETRS89_UTM30,
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

    @Test
    fun `S015 RF-19 la base se pinta en el mapa mientras hay posicion de la base`() {
        var base by mutableStateOf<GeoPoint?>(GeoPoint(40.4166667, -3.7))
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectViewScreen(ThemeState.DEFAULT, {}, ProjectViewUiState(project, base = base), onBack = {}, map = fakeMap(map))
            }
        }
        compose.waitForIdle()
        map.base shouldBe GeoPoint(40.4166667, -3.7)

        base = null
        compose.waitForIdle()
        map.base.shouldBeNull()
    }

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(sessionScope, NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE })

    @After
    fun closeSession() {
        session.disconnect()
        sessionScope.cancel()
    }

    @Test
    fun `S015 RF-19 el ViewModel da la base de la sesion y la quita sin base`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java).build()
        val toApp = PipedOutputStream()
        try {
            val repository = RoomProjectRepository(db)
            val id = runBlocking { (repository.create(ProjectDefaults().toDraft().copy(name = "Obra")) as SaveResult.Saved).id }
            session.connect(StreamTransport("reach-RS3", PipedInputStream(toApp, 64 * 1024), ByteArrayOutputStream()))
            toApp.write(nmea("GNGGA,101530.00,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001").toByteArray())
            toApp.write(nmea("GNEBP,4025.0000000,N,00342.0000000,W,700.000,M").toByteArray())
            toApp.write(nmea("GNGGA,101531.00,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001").toByteArray())
            toApp.flush()
            val vm = projectViewModel(repository, session)
            vm.start(id)
            val collector = CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
            try {
                val base = runBlocking { withTimeout(5_000) { vm.state.first { it.base != null } } }.base.shouldNotBeNull()
                base.latitudeDeg shouldBe (40.4166666667 plusOrMinus 1e-9)
                base.longitudeDeg shouldBe (-3.7 plusOrMinus 1e-9)

                session.disconnect()

                runBlocking { withTimeout(5_000) { vm.state.first { it.base == null } } }.base.shouldBeNull()
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
