package es.topophone.core.ntrip

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Descarga de la tabla de fuentes frente a un caster falso local (S007/RF-14, RF-20). */
class SourcetableClientTest {

    /** Caster de prueba: lee la petición, la anota y responde con [response] (o lo que haga [script]). */
    private class FakeCaster(private val script: (Socket) -> Unit) : AutoCloseable {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
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
                    script(socket)
                }
            }
        }

        override fun close() {
            server.close()
            worker.join(2_000)
        }
    }

    private val casters = mutableListOf<FakeCaster>()

    private fun caster(response: ByteArray) = caster { it.getOutputStream().apply { write(response) }.flush() }

    private fun caster(script: (Socket) -> Unit) = FakeCaster(script).also(casters::add)

    @AfterEach
    fun closeCasters() = casters.forEach(FakeCaster::close)

    private val client = SourcetableClient(userAgent = "NTRIP TopoPhone/test", timeoutMs = 2_000)

    private fun fetch(caster: FakeCaster, username: String = "", password: String = "") =
        runBlocking { client.fetch("127.0.0.1", caster.server.localPort, username, password) }

    private fun fixture(name: String): ByteArray {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
        return File(root, "fixtures/ntrip/sourcetables/$name.sourcetable").readBytes()
    }

    @Test
    fun `S007 RF-14 descarga la tabla de un caster 1_0 y la lee`() {
        val caster = caster(fixture("ign-ergnss-2101"))

        val result = fetch(caster)

        result.shouldBeInstanceOf<SourcetableResult.Ok>().streams shouldHaveSize 8
        caster.request.first() shouldBe "GET / HTTP/1.1"
        caster.request shouldContain "Ntrip-Version: Ntrip/2.0"
        caster.request.none { it.startsWith("Authorization") } shouldBe true
    }

    @Test
    fun `S007 RF-14 descarga la tabla de un caster 2_0 que responde por bloques`() {
        val body = fixture("rgapa-2101").toString(Charsets.ISO_8859_1).substringAfter("\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
        val caster = caster { socket ->
            val out = socket.getOutputStream()
            out.write("HTTP/1.1 200 OK\r\nContent-Type: gnss/sourcetable\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
            body.toList().chunked(1000).forEach { part ->
                out.write("${Integer.toHexString(part.size)}\r\n".toByteArray())
                out.write(part.toByteArray())
                out.write("\r\n".toByteArray())
            }
            out.write("0\r\n\r\n".toByteArray())
            out.flush()
        }

        fetch(caster).shouldBeInstanceOf<SourcetableResult.Ok>().streams shouldHaveSize 42
    }

    @Test
    fun `S007 RF-14 envia el usuario y la contrasena si los hay`() {
        val caster = caster(fixture("ign-ergnss-2101"))

        fetch(caster, username = "u", password = "p")

        caster.request shouldContain "Authorization: Basic dTpw"
    }

    @Test
    fun `S007 RF-14 cada linea se lee en UTF-8 o, si no lo es, en ISO-8859-1`() {
        val streams = fetch(caster(fixture("ign-ergnss-2102"))).shouldBeInstanceOf<SourcetableResult.Ok>().streams

        streams.single { it.mountpoint == "ESPU3M" }.identifier shouldBe "Sierra Espuña"
        streams.single { it.mountpoint == "GMSR3M" }.identifier shouldBe "Gomezserracín"
    }

    @Test
    fun `S007 RF-20 si el caster rechaza las credenciales lo dice`() {
        fetch(caster("HTTP/1.1 401 Unauthorized\r\n\r\n".toByteArray())) shouldBe SourcetableResult.Failed(SourcetableFailure.UNAUTHORIZED)
        fetch(caster("ERROR - Bad Password\r\n".toByteArray())) shouldBe SourcetableResult.Failed(SourcetableFailure.UNAUTHORIZED)
    }

    @Test
    fun `S007 RF-20 si la respuesta no es una tabla de fuentes lo dice`() {
        val html = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n<html><body>Hola</body></html>".toByteArray()

        fetch(caster(html)) shouldBe SourcetableResult.Failed(SourcetableFailure.NOT_A_SOURCETABLE)
        fetch(caster("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())) shouldBe
            SourcetableResult.Failed(SourcetableFailure.NOT_A_SOURCETABLE)
    }

    @Test
    fun `S007 RF-20 una tabla sin puntos de montaje es una tabla vacia`() {
        val empty = listOf("SOURCETABLE 200 OK", "Content-Type: text/plain", "", "CAS;x;2101;;;0;ESP;0;0;0.0.0.0;0;", "ENDSOURCETABLE", "")
            .joinToString("\r\n")
            .toByteArray()

        fetch(caster(empty)) shouldBe SourcetableResult.Failed(SourcetableFailure.EMPTY)
    }

    @Test
    fun `S007 RF-20 si no se puede contactar con el caster lo dice`() {
        val closedPort = ServerSocket(0).use { it.localPort }

        val result = runBlocking { client.fetch("127.0.0.1", closedPort, "", "") }

        result shouldBe SourcetableResult.Failed(SourcetableFailure.UNREACHABLE)
    }

    @Test
    fun `S007 RF-20 si el caster no responde a tiempo no se puede contactar`() {
        val silent = caster { Thread.sleep(3_000) }

        fetch(silent) shouldBe SourcetableResult.Failed(SourcetableFailure.UNREACHABLE)
    }
}
