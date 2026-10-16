package es.topophone.core.ntrip

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.Collections
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Conexión segura (TLS) con el caster, para las correcciones y la tabla de fuentes (S007/RF-27, RF-28). */
class NtripClientTlsTest {

    /** Caster TLS de prueba con el certificado [alias]: anota la petición y responde [response]. */
    private class TlsCaster(alias: String, private val response: String) : AutoCloseable {
        val server = TestCertificates.server(alias).createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket
        val request: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private val worker = thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        request += line
                    }
                    socket.getOutputStream().apply { write(response.toByteArray(Charsets.ISO_8859_1)) }.flush()
                }
            }
        }

        val port get() = server.localPort

        override fun close() {
            server.close()
            worker.join(2_000)
        }
    }

    private val casters = mutableListOf<TlsCaster>()

    private fun caster(alias: String, response: String) = TlsCaster(alias, response).also(casters::add)

    @AfterEach
    fun closeCasters() = casters.forEach(TlsCaster::close)

    private fun config(port: Int) = NtripConfig("127.0.0.1", port, "MP", "u", "p", useTls = true)

    private fun stream(port: Int, sslSocketFactory: SSLSocketFactory): Pair<NtripFailure, ByteArray> {
        val client = NtripClient(userAgent = "NTRIP TopoPhone/test", sslSocketFactory = sslSocketFactory, noDataTimeoutMs = 2_000)
        val received = ByteArrayOutputStream()
        val failure = runBlocking {
            withTimeout(10_000) { client.run(config(port), latestGga = { null }, onConnected = {}) { received.write(it) } }
        }
        return failure to received.toByteArray()
    }

    @Test
    fun `S007 RF-27 con conexion segura las correcciones llegan cifradas con TLS`() {
        val caster = caster("local", "ICY 200 OK\r\nRTCM")

        val (failure, received) = stream(caster.port, TestCertificates.trustingClient())

        received.toString(Charsets.ISO_8859_1) shouldBe "RTCM"
        failure shouldBe NtripFailure.CONNECTION_LOST // el caster de prueba corta al terminar
        caster.request.first() shouldBe "GET /MP HTTP/1.1"
    }

    @Test
    fun `S007 RF-28 un certificado de otro servidor no se acepta`() {
        val caster = caster("other", "ICY 200 OK\r\nRTCM")

        val (failure, received) = stream(caster.port, TestCertificates.trustingClient())

        failure shouldBe NtripFailure.TLS_INVALID
        received.size shouldBe 0
    }

    @Test
    fun `S007 RF-28 un certificado en el que no se confia no se acepta`() {
        val caster = caster("local", "ICY 200 OK\r\nRTCM")

        val (failure, _) = stream(caster.port, SSLSocketFactory.getDefault() as SSLSocketFactory)

        failure shouldBe NtripFailure.TLS_INVALID
    }

    @Test
    fun `S007 RF-27 la tabla de fuentes tambien se descarga con TLS`() {
        val caster =
            caster(
                "local",
                "SOURCETABLE 200 OK\r\n\r\nSTR;MP;Madrid;RTCM 3.2;;2;GPS;RED;ESP;40.4;-3.7;0;0;X;none;B;N;9600;\r\nENDSOURCETABLE\r\n",
            )
        val client =
            SourcetableClient(userAgent = "NTRIP TopoPhone/test", sslSocketFactory = TestCertificates.trustingClient(), timeoutMs = 5_000)

        val result = runBlocking { client.fetch("127.0.0.1", caster.port, "u", "p", useTls = true) }

        result.shouldBeInstanceOf<SourcetableResult.Ok>().streams.single().mountpoint shouldBe "MP"
        caster.request shouldContain "GET / HTTP/1.1"
    }

    @Test
    fun `S007 RF-28 la tabla de fuentes con un certificado no valido lo dice`() {
        val caster = caster("other", "SOURCETABLE 200 OK\r\n\r\nENDSOURCETABLE\r\n")
        val client =
            SourcetableClient(userAgent = "NTRIP TopoPhone/test", sslSocketFactory = TestCertificates.trustingClient(), timeoutMs = 5_000)

        val result = runBlocking { client.fetch("127.0.0.1", caster.port, "", "", useTls = true) }

        result shouldBe SourcetableResult.Failed(SourcetableFailure.TLS_INVALID)
    }
}
