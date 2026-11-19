package es.topophone.receiver.experimental

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

/** S044/RF-20, RF-21, RF-22: iniciar y parar los logs y comprobar el estado resultante por el directo; plan D11, A3. */
class ReachWriterLogsTest {

    private class FakeLogsObserver(connected: Boolean = true, writing: Boolean? = false) : LogsObserver {
        private val current = MutableStateFlow(ReachLiveState(connected = connected, logsWriting = writing))
        private val events = MutableSharedFlow<Boolean>(extraBufferCapacity = 8)
        override val state: StateFlow<ReachLiveState> = current
        override val logEvents: SharedFlow<Boolean> = events

        fun emit(writing: Boolean) {
            current.value = current.value.copy(logsWriting = writing)
            events.tryEmit(writing)
        }
    }

    private class RecordingTrail : ReachTrail {
        val writes = mutableListOf<List<String?>>()

        override fun configRead(settingCount: Int) = Unit

        override fun write(operation: String, before: String?, sent: String, after: String?, result: String) {
            writes += listOf(operation, before, sent, after, result)
        }
    }

    private lateinit var receiver: FakeReachServer
    private val trail = RecordingTrail()

    @Before
    fun start() {
        receiver = FakeReachServer().start()
    }

    @After
    fun stop() {
        receiver.stop()
    }

    private fun setLogs(started: Boolean, observer: LogsObserver, waitMs: Long = 300): WriteResult<Boolean> =
        runBlocking { ReachWriter(receiver.client(), trail).setLogs(started, observer, waitMs) }

    @Test
    fun `S044 RF-20 iniciar envia el cuerpo y se confirma con el siguiente evento de logs`() {
        val live = FakeLogsObserver(writing = false)
        receiver.onLogsPost = { live.emit(it) }

        setLogs(started = true, live) shouldBe WriteResult.Confirmed(true)

        receiver.logBodies shouldBe listOf("""{"started":true}""")
        trail.writes shouldBe listOf(listOf("logs", "parados", "iniciar", "escribiendo", "confirmado"))
    }

    @Test
    fun `S044 RF-20 parar envia started false y se confirma`() {
        val live = FakeLogsObserver(writing = true)
        receiver.onLogsPost = { live.emit(it) }

        setLogs(started = false, live) shouldBe WriteResult.Confirmed(false)

        receiver.logBodies shouldBe listOf("""{"started":false}""")
        trail.writes.single()[4] shouldBe "confirmado"
    }

    @Test
    fun `S044 RF-22 solo se envia el POST de los logs`() {
        val live = FakeLogsObserver()
        receiver.onLogsPost = { live.emit(it) }

        setLogs(started = true, live)

        receiver.requests shouldBe listOf("POST /configuration/logging/logs")
    }

    @Test
    fun `S044 RF-21 si el estado resultante no coincide con lo pedido se informa del que se ve`() {
        val live = FakeLogsObserver(writing = false)
        receiver.onLogsPost = { live.emit(false) }

        setLogs(started = true, live) shouldBe WriteResult.NotConfirmed(read = false, requestFailed = false)

        receiver.logBodies.size shouldBe 1
        trail.writes.single()[4] shouldBe "no coincide"
    }

    @Test
    fun `S044 RF-21 si no llega ningun evento y el estado no es el pedido no se confirma`() {
        val live = FakeLogsObserver(writing = false)

        setLogs(started = true, live) shouldBe WriteResult.NotConfirmed(read = false, requestFailed = false)

        trail.writes.single()[4] shouldBe "sin confirmacion"
    }

    @Test
    fun `S044 RF-20 si no llega ningun evento pero el estado ya es el pedido se confirma`() {
        val live = FakeLogsObserver(writing = true)

        setLogs(started = true, live) shouldBe WriteResult.Confirmed(true)
    }

    @Test
    fun `S044 RF-21 si el envio falla se informa del estado visto y no se reintenta`() {
        val live = FakeLogsObserver(writing = false)
        receiver.logWriteStatus = 500

        setLogs(started = true, live) shouldBe WriteResult.NotConfirmed(read = false, requestFailed = true)

        receiver.logBodies.size shouldBe 1
        trail.writes.single()[4] shouldBe "fallo del envio"
    }

    @Test
    fun `S044 RF-20 sin conexion en directo no se envia nada`() {
        val live = FakeLogsObserver(connected = false, writing = null)

        setLogs(started = true, live) shouldBe WriteResult.Failed(WriteFailure.NO_LIVE)

        receiver.requests shouldBe emptyList()
    }

    @Test
    fun `S044 RF-20 con el estado de los logs aun sin conocer, iniciar sin evento no se confirma`() {
        val live = FakeLogsObserver(connected = true, writing = null)

        setLogs(started = true, live) shouldBe WriteResult.NotConfirmed(read = null, requestFailed = false)

        trail.writes.single()[1] shouldBe null
    }
}
