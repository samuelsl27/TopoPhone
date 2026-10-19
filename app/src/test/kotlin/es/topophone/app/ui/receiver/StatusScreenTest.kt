package es.topophone.app.ui.receiver

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.receiver.ReceiverConnections
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.Constellation
import es.topophone.core.gnss.nmea.Dop
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.GsvKey
import es.topophone.core.gnss.nmea.SatellitesInView
import es.topophone.core.gnss.nmea.SkyView
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.format.AngleFormat
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.GroundPointCalculator
import es.topophone.data.project.ProjectPreferences
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.session.ReceiverSnapshot
import es.topophone.receiver.session.ReplayController
import io.kotest.matchers.shouldBe
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pantalla Estado: posición, DOP y satélites por constelación (S015/RF-1 a RF-3). Interfaz en español (es-ES). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class StatusScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val receiving = ConnectionState.Receiving("reach-RS3")
    private val spanish = Locale.forLanguageTag("es-ES")

    private val gga = GgaFix("101530.20", 40.41668, -3.70371, SolutionStatus.FIX, 24, 0.6, 650.123, 51.234, 1.0, "")

    private val sky = SkyView(
        dop = Dop(1.2, 0.5, 1.0),
        used = mapOf(Constellation.GPS to setOf(5, 12), Constellation.GLONASS to setOf(6)),
        usedCounts = mapOf(Constellation.GPS to 2, Constellation.GLONASS to 1),
        inView = mapOf(
            GsvKey(Constellation.GPS, 1) to SatellitesInView(10, emptyList()),
            GsvKey(Constellation.GPS, 8) to SatellitesInView(6, emptyList()),
            GsvKey(Constellation.GLONASS, 1) to SatellitesInView(7, emptyList()),
            GsvKey(Constellation.GALILEO, 7) to SatellitesInView(4, emptyList()),
        ),
        updatedAtMs = 0,
    )

    private fun show(state: ReceiverUiState) = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) {
            ReceiverPageScreen(ReceiverPage.STATUS, ThemeState.DEFAULT, {}, state, ReceiverActions(), {})
        }
    }

    private fun value(tag: String) = compose.onNodeWithTag(tag).performScrollTo()

    @Test
    fun `S015 RF-1 la posicion de la antena con el formato angular de los proyectos nuevos`() {
        show(ReceiverUiState(snapshot = ReceiverSnapshot(receiving, gga = gga), angularUnit = AngularUnit.DMS))

        value(STATUS_LATITUDE_TAG)
            .assert(hasText(text(R.string.coord_latitude)))
            .assert(hasText(AngleFormat.latitude(40.41668, AngularUnit.DMS, spanish)))
        value(STATUS_LONGITUDE_TAG).assert(hasText(AngleFormat.longitude(-3.70371, AngularUnit.DMS, spanish)))
        value(STATUS_ELLIPSOIDAL_HEIGHT_TAG).assert(hasText("701,357 m"))
    }

    @Test
    fun `S015 RF-1 en grados decimales si es el formato de los proyectos nuevos`() {
        show(ReceiverUiState(snapshot = ReceiverSnapshot(receiving, gga = gga), angularUnit = AngularUnit.DECIMAL_DEGREES))

        value(STATUS_LATITUDE_TAG).assert(hasText(AngleFormat.latitude(40.41668, AngularUnit.DECIMAL_DEGREES, spanish)))
    }

    @Test
    fun `S015 RF-1 sin altitud o separacion la altura elipsoidal no esta disponible`() {
        show(ReceiverUiState(snapshot = ReceiverSnapshot(receiving, gga = gga.copy(geoidSeparationM = null))))

        value(STATUS_ELLIPSOIDAL_HEIGHT_TAG).assert(hasText(text(R.string.receiver_value_unavailable)))
    }

    @Test
    fun `S015 RF-1 con la conexion en Sin datos no se ensena una posicion antigua`() {
        show(ReceiverUiState(snapshot = ReceiverSnapshot(ConnectionState.NoData("reach-RS3"), gga = gga, sky = sky)))

        compose.onAllNodesWithTag(STATUS_LATITUDE_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(STATUS_PDOP_TAG).assertCountEquals(0)
    }

    @Test
    fun `S015 RF-2 PDOP, HDOP y VDOP de la GSA`() {
        show(ReceiverUiState(snapshot = ReceiverSnapshot(receiving, gga = gga, sky = sky)))

        value(STATUS_PDOP_TAG).assert(hasText("PDOP")).assert(hasText("1,2"))
        value(STATUS_GSA_HDOP_TAG).assert(hasText("0,5"))
        value(STATUS_VDOP_TAG).assert(hasText("VDOP")).assert(hasText("1,0"))
    }

    @Test
    fun `S015 RF-3 usados y en vista por constelacion`() {
        show(ReceiverUiState(snapshot = ReceiverSnapshot(receiving, gga = gga, sky = sky)))

        value(constellationTag(Constellation.GPS))
            .assert(hasText(text(R.string.status_constellation, text(R.string.gnss_gps), 'G'))) // "GPS (G)"
            .assert(hasText(text(R.string.receiver_satellites_in_view, 2, 10)))
        value(constellationTag(Constellation.GLONASS)).assert(hasText(text(R.string.receiver_satellites_in_view, 1, 7)))
        value(constellationTag(Constellation.GALILEO)).assert(hasText(text(R.string.receiver_satellites_in_view, 0, 4)))
    }

    @Test
    fun `S015 RF-1 el formato angular sale de los proyectos nuevos de Ajustes`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val preferences = FakePreferences()
            val session = ReceiverSession(scope, NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE })
            val bluetooth = FakeBluetooth()
            val projects = object : ProjectPreferences {
                override val defaults = flowOf(ProjectDefaults(angularUnit = AngularUnit.GON))

                override suspend fun saveDefaults(defaults: ProjectDefaults) = Unit
            }
            val vm = ReceiverViewModel(
                session = session,
                bluetooth = bluetooth,
                preferences = preferences,
                calculator = { GroundPointCalculator() },
                network = { true },
                alerts = NoAlerts,
                clock = { System.nanoTime() / 1_000_000 },
                samplePeriodMs = 0,
                replay = ReplayController(session) { emptyList() },
                connections = ReceiverConnections(session, bluetooth, preferences, CoroutineScope(Dispatchers.Default)),
                projectPreferences = projects,
            )

            val state = runBlocking {
                val collector = CoroutineScope(Dispatchers.Unconfined).launch { vm.uiState.collect {} }
                try {
                    withTimeout(5_000) { vm.uiState.first { it.angularUnit == AngularUnit.GON } }
                } finally {
                    collector.cancel()
                }
            }

            state.angularUnit shouldBe AngularUnit.GON
        } finally {
            scope.cancel()
            Dispatchers.resetMain()
        }
    }
}
