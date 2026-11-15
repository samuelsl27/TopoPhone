package es.topophone.receiver.transport

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test

/** El segundo socket de correcciones, con su cola, su descarte y su reintento, sin cortar la posición (S021/RF-11, RF-13). */
class CorrectionsLinkTest {

    private val servers = mutableListOf<FakeTcpReceiver>()
    private val links = mutableListOf<CorrectionsLink>()
    private val transports = mutableListOf<TcpTransport>()
    private val fastBackoff = listOf(40L, 80L, 120L)

    @After
    fun tearDown() {
        transports.forEach { runCatching { it.close() } }
        links.forEach { runCatching { it.close() } }
        servers.forEach { runCatching { it.close() } }
    }

    private fun server(port: Int = 0) = FakeTcpReceiver(nmea = ByteArray(0), port = port).also { servers += it }

    private fun link(host: String, port: Int, backoff: List<Long> = fastBackoff) =
        CorrectionsLink(host, port, backoffMs = backoff).also { links += it }

    private suspend fun CorrectionsLink.await(timeoutMs: Long = 5_000, what: (CorrectionsLinkStatus) -> Boolean) =
        withTimeout(timeoutMs) { status.first(what) }

    private fun awaitReceived(receiver: FakeTcpReceiver, size: Int): ByteArray {
        val deadline = System.nanoTime() + 4_000_000_000L
        while (receiver.receivedBytes().size < size && System.nanoTime() < deadline) Thread.sleep(10)
        return receiver.receivedBytes()
    }

    private fun awaitConnections(receiver: FakeTcpReceiver, count: Int) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (receiver.connections < count && System.nanoTime() < deadline) Thread.sleep(10)
    }

    private val rtcm = ByteArray(600) { (if (it == 0) 0xD3 else it * 13).toByte() }

    @Test
    fun `S021 RF-11 con el puerto distinto se abre un segundo socket y lo escrito llega intacto`() = runBlocking<Unit> {
        val corrections = server()
        val link = link(corrections.host, corrections.port)

        link.status.value shouldBe CorrectionsLinkStatus.Connecting
        link.start()
        link.await { it == CorrectionsLinkStatus.Ready }
        link.write(rtcm)
        link.flush()

        awaitReceived(corrections, rtcm.size) shouldBe rtcm
        corrections.connections shouldBe 1
    }

    @Test
    fun `S021 RF-13 con el servidor de correcciones caido se descarta lo escrito y se reintenta`() = runBlocking<Unit> {
        val down = FakeTcpReceiver(nmea = ByteArray(0))
        val port = down.port
        down.close()
        val link = link(down.host, port, backoff = listOf(300L, 300L))

        link.start()
        val failed = link.await { it is CorrectionsLinkStatus.Failed }.shouldBeInstanceOf<CorrectionsLinkStatus.Failed>()
        failed.attempt shouldBe 1
        link.write(rtcm) // no lanza: se descarta
        val second = link.await { it is CorrectionsLinkStatus.Failed && it.attempt >= 2 }
        second.shouldBeInstanceOf<CorrectionsLinkStatus.Failed>().attempt shouldBe 2
    }

    @Test
    fun `S021 RF-13 al volver el servidor se conecta solo y lo descartado no llega`() = runBlocking<Unit> {
        val first = FakeTcpReceiver(nmea = ByteArray(0))
        val port = first.port
        first.close()
        val link = link(first.host, port)
        link.start()
        link.await { it is CorrectionsLinkStatus.Failed }
        link.write(ByteArray(50) { 1 }) // con el enlace caido: descartado

        val back = server(port)
        link.await(10_000) { it == CorrectionsLinkStatus.Ready }
        link.write(rtcm)

        awaitReceived(back, rtcm.size) shouldBe rtcm
        back.receivedBytes().size shouldBe rtcm.size
    }

    @Test
    fun `S021 RF-13 un corte con el enlace abierto se nota sin escribir y se vuelve a abrir`() = runBlocking<Unit> {
        val corrections = server()
        val link = link(corrections.host, corrections.port)
        link.start()
        link.await { it == CorrectionsLinkStatus.Ready }

        corrections.dropClients() // sin escribir nada: el corte se nota leyendo el socket y se vuelve a abrir
        awaitConnections(corrections, 2)
        link.await(10_000) { it == CorrectionsLinkStatus.Ready }
        link.write(rtcm)

        awaitReceived(corrections, rtcm.size) shouldBe rtcm
        corrections.connections shouldBe 2
    }

    @Test
    fun `S021 RF-13 escribir con el enlace cortado no lanza y luego se recupera`() = runBlocking<Unit> {
        val corrections = server()
        val link = link(corrections.host, corrections.port)
        link.start()
        link.await { it == CorrectionsLinkStatus.Ready }

        corrections.dropClients()
        repeat(20) { link.write(rtcm) } // alguna de estas escrituras falla en mitad: ninguna lanza
        // Ready puede llegar antes de que el servidor cuente la conexion: se espera a las dos cosas.
        awaitConnections(corrections, 2)
        link.await(10_000) { it == CorrectionsLinkStatus.Ready }
        val before = corrections.receivedBytes().size
        link.write(ByteArray(10) { 7 })

        awaitReceived(corrections, before + 10).size shouldBe before + 10
    }

    @Test
    fun `S021 RF-13 las esperas entre reintentos son 1, 2, 4, 8, 16 y 30 segundos`() {
        CorrectionsLink.DEFAULT_BACKOFF_MS.shouldContainExactly(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
    }

    @Test
    fun `S021 RF-13 cada fallo seguido avisa del siguiente intento con la espera de la lista`() = runBlocking<Unit> {
        val down = FakeTcpReceiver(nmea = ByteArray(0))
        val port = down.port
        down.close()
        var now = 1_000L
        val link = CorrectionsLink(down.host, port, backoffMs = listOf(300L, 600L), clock = { now }).also { links += it }

        link.start()
        val first = link.await { it is CorrectionsLinkStatus.Failed }.shouldBeInstanceOf<CorrectionsLinkStatus.Failed>()
        first.nextAttemptAtMs shouldBe 1_300L
        now = 5_000L
        val second = link.await { it is CorrectionsLinkStatus.Failed && it.attempt == 2 }.shouldBeInstanceOf<CorrectionsLinkStatus.Failed>()
        second.nextAttemptAtMs shouldBe 5_600L
    }

    @Test
    fun `S021 RF-13 cerrar el enlace deja de reintentar`() {
        val down = FakeTcpReceiver(nmea = ByteArray(0))
        val port = down.port
        down.close()
        val link = link(down.host, port)
        link.start()
        Thread.sleep(150)

        link.close()
        val back = server(port)
        Thread.sleep(400)

        back.connections shouldBe 0
    }

    @Test
    fun `S021 RF-11 con un puerto de correcciones distinto el transporte las manda por el segundo socket y la posicion no se toca`() =
        runBlocking<Unit> {
            val position = FakeTcpReceiver()
            val corrections = server()
            servers += position
            val tcp = TcpTransport(
                position.host,
                position.port,
                "RS3",
                correctionsPort = corrections.port,
                correctionsBackoffMs = fastBackoff,
            ).also { transports += it }

            tcp.open()
            tcp.acceptsCorrections shouldBe true
            val status = tcp.correctionsStatus.shouldBeInstanceOf<kotlinx.coroutines.flow.StateFlow<CorrectionsLinkStatus>>()
            withTimeout(5_000) { status.first { it == CorrectionsLinkStatus.Ready } }
            tcp.output.write(rtcm)
            tcp.output.flush()

            awaitReceived(corrections, rtcm.size) shouldBe rtcm
            position.receivedBytes().size shouldBe 0
            tcp.input.bufferedReader().readLine().startsWith("\$GNGGA,") shouldBe true
        }

    @Test
    fun `S021 RF-13 con el servidor de correcciones caido la posicion sigue llegando`() = runBlocking<Unit> {
        val position = FakeTcpReceiver().also { servers += it }
        val down = FakeTcpReceiver(nmea = ByteArray(0))
        val downPort = down.port
        down.close()
        val tcp = TcpTransport(position.host, position.port, "RS3", correctionsPort = downPort, correctionsBackoffMs = fastBackoff)
            .also { transports += it }

        tcp.open() // un puerto de correcciones caido no impide abrir la posicion
        withTimeout(5_000) { tcp.correctionsStatus!!.first { it is CorrectionsLinkStatus.Failed } }
        tcp.output.write(rtcm) // descartado sin error

        val lines = generateSequence { tcp.input.bufferedReader().readLine() }.take(1).toList()
        lines.single().startsWith("\$GNGGA,") shouldBe true
    }

    @Test
    fun `S021 RF-11 con el mismo puerto o sin puerto no hay segundo enlace`() {
        val position = FakeTcpReceiver().also { servers += it }

        TcpTransport(position.host, position.port, "RS3", correctionsPort = position.port).correctionsStatus shouldBe null
        TcpTransport(position.host, position.port, "RS3", correctionsPort = null).correctionsStatus shouldBe null
    }
}
