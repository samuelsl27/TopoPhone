package es.topophone.app.ui.sessions

import es.topophone.app.log.RecordingLog
import es.topophone.core.gnss.nmea.NmeaChecksum
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.log.LogLevel
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.session.ReplayController
import es.topophone.receiver.session.SessionLogStore
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Lo que la lista de sesiones saca del registro (S003/RF-29, S003/RF-31, S003/RF-33, S003/RF-34). */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModelTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var now = Instant.parse("2026-10-15T08:30:00Z")
    private val store by lazy { SessionLogStore(folder.root, clock = { now }, freeSpaceBytes = { Long.MAX_VALUE }) }
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val replay by lazy {
        ReplayController(ReceiverSession(sessionScope, NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE })) { store.sessions() }
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        sessionScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `S003 RF-29 las filas tienen inicio, duracion, tamano, receptor y si esta en curso, de la mas reciente a la mas antigua`() {
        store.start("reach-RS3")!!.apply {
            write(ByteArray(100), 0, 100)
            now = now.plusSeconds(3_720)
            close()
        }
        now = now.plusSeconds(60)
        store.start("reach-RS3")
        val viewModel = SessionsViewModel(store, Dispatchers.Unconfined, replay)

        val rows = viewModel.state.value.rows
        rows.map { it.id } shouldBe listOf("20261015-093300", "20261015-083000")
        rows.map { it.inProgress } shouldBe listOf(true, false)
        rows.last().durationS shouldBe 3_720L
        rows.last().receiver shouldBe "reach-RS3"
        rows.last().imported shouldBe false
        rows.last().sizeBytes shouldBe store.sessions().last().sizeBytes
    }

    @Test
    fun `S003 RF-31 al borrar una sesion desaparece de la lista`() {
        store.start("reach-RS3")!!.close()
        val viewModel = SessionsViewModel(store, Dispatchers.Unconfined, replay)
        val id = viewModel.state.value.rows.single().id

        viewModel.delete(id)

        viewModel.state.value.rows shouldBe emptyList()
    }

    @Test
    fun `S003 RF-33 al importar un fichero NMEA aparece en la lista como Importada`() {
        val viewModel = SessionsViewModel(store, Dispatchers.Unconfined, replay)
        val body = "GNGGA,101530.00,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001"
        val line = "$" + body + "*" + "%02X".format(NmeaChecksum.compute(body)) + "\r\n"

        viewModel.import("log.nmea", sizeHint = null) { ByteArrayInputStream(line.toByteArray()) }

        val row = viewModel.state.value.rows.single()
        row.imported shouldBe true
        row.receiver shouldBe "log.nmea"
        viewModel.state.value.message shouldBe null
    }

    @Test
    fun `S003 RF-34 si no se puede importar el estado indica el motivo`() {
        val viewModel = SessionsViewModel(store, Dispatchers.Unconfined, replay)

        viewModel.import("binario.ubx", sizeHint = null) { ByteArrayInputStream(ByteArray(100)) }

        viewModel.state.value.message shouldBe ImportMessage.NO_NMEA
        viewModel.state.value.rows shouldBe emptyList()
    }

    @Test
    fun `S014 RF-5 un fichero NMEA rechazado queda en el log`() {
        val log = RecordingLog()
        val viewModel = SessionsViewModel(store, Dispatchers.Unconfined, replay, log)

        viewModel.import("fotos.zip", sizeHint = null) { ByteArrayInputStream("no es NMEA".toByteArray()) }

        log.entries.single() shouldBe
            Triple(LogLevel.WARNING, LogCategory.RECEIVER, "Fichero NMEA rechazado, sin frases válidas con hora: fotos.zip")
    }
}
