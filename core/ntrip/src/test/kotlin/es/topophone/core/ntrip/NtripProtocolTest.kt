package es.topophone.core.ntrip

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import java.io.ByteArrayInputStream
import java.util.Base64
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Petición y respuesta NTRIP v1/v2 (S002/RF-19, S002/RF-21, S002/RF-22). */
class NtripProtocolTest {

    private val config = NtripConfig(host = "caster.example.es", port = 2101, mountpoint = "VRS3", username = "topo", password = "s3cr3t:x")

    private fun handshake(text: String, data: ByteArray = ByteArray(0)): Pair<NtripHandshake, ByteArray> {
        val input = ByteArrayInputStream(text.toByteArray(Charsets.ISO_8859_1) + data)
        return NtripResponseParser.read(input) to input.readAllBytes()
    }

    @Test
    fun `S002 RF-19 la peticion es NTRIP v2 compatible con casters v1`() {
        val request = String(NtripRequest.build(config, userAgent = "NTRIP TopoPhone/0.0.1"), Charsets.ISO_8859_1)

        request shouldStartWith "GET /VRS3 HTTP/1.1\r\n"
        request shouldContain "Host: caster.example.es:2101\r\n"
        request shouldContain "Ntrip-Version: Ntrip/2.0\r\n"
        request shouldContain "User-Agent: NTRIP TopoPhone/0.0.1\r\n"
        request shouldContain "Authorization: Basic ${Base64.getEncoder().encodeToString("topo:s3cr3t:x".toByteArray())}\r\n"
        request shouldEndWith "\r\n\r\n"
    }

    @Test
    fun `S002 RF-19 sin usuario no se envia autorizacion y la barra inicial del punto de montaje se ignora`() {
        val request =
            String(NtripRequest.build(config.copy(mountpoint = "/RTCM3", username = "", password = ""), "NTRIP x"), Charsets.ISO_8859_1)

        request shouldStartWith "GET /RTCM3 HTTP/1.1\r\n"
        request.contains("Authorization") shouldBe false
    }

    @Test
    fun `S002 RF-19 ICY 200 OK de un caster v1 da paso a los datos sin consumirlos`() {
        val rtcm = byteArrayOf(0xD3.toByte(), 0x00, 0x05, 1, 2, 3, 4, 5)

        val (result, rest) = handshake("ICY 200 OK\r\n", rtcm)

        result shouldBe NtripHandshake.Streaming(chunked = false)
        rest.toList() shouldBe rtcm.toList()
    }

    @Test
    fun `S002 RF-19 HTTP 200 con gnss-data y chunked de un caster v2`() {
        val (result, rest) = handshake(
            "HTTP/1.1 200 OK\r\nNtrip-Version: Ntrip/2.0\r\nContent-Type: gnss/data\r\nTransfer-Encoding: chunked\r\n\r\n",
            "3\r\nabc\r\n".toByteArray(),
        )

        result shouldBe NtripHandshake.Streaming(chunked = true)
        String(rest) shouldBe "3\r\nabc\r\n"
    }

    @Test
    fun `S002 RF-19 HTTP 200 sin chunked`() {
        handshake("HTTP/1.0 200 OK\r\nContent-Type: gnss/data\r\n\r\n").first shouldBe NtripHandshake.Streaming(chunked = false)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"/VRS3\"\r\n\r\n",
            "HTTP/1.0 401 Unauthorized\r\n\r\n",
            "ERROR - Bad Password\r\n",
        ],
    )
    fun `S002 RF-21 el caster rechaza el usuario o la contrasena`(response: String) {
        handshake(response).first shouldBe NtripHandshake.Rejected(NtripFailure.UNAUTHORIZED)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "SOURCETABLE 200 OK\r\nServer: NTRIP Caster\r\n\r\nSTR;VRS3;...\r\nENDSOURCETABLE\r\n",
            "HTTP/1.1 200 OK\r\nContent-Type: gnss/sourcetable\r\n\r\nSTR;...\r\n",
            "HTTP/1.1 404 Not Found\r\n\r\n",
            "ERROR - Bad Mountpoint\r\n",
        ],
    )
    fun `S002 RF-22 el punto de montaje no existe`(response: String) {
        handshake(response).first shouldBe NtripHandshake.Rejected(NtripFailure.MOUNTPOINT_NOT_FOUND)
    }

    @Test
    fun `S002 RF-19 una respuesta desconocida o vacia es un error de protocolo o de conexion`() {
        handshake("SSH-2.0-OpenSSH_9.0\r\n").first shouldBe NtripHandshake.Rejected(NtripFailure.PROTOCOL)
        handshake("HTTP/1.1 500 Internal Server Error\r\n\r\n").first shouldBe NtripHandshake.Rejected(NtripFailure.PROTOCOL)
        handshake("").first shouldBe NtripHandshake.Rejected(NtripFailure.CONNECTION_LOST)
    }

    @Test
    fun `S002 RF-19 el descodificador chunked entrega los datos tal cual aunque lleguen partidos`() {
        val payload = byteArrayOf(0xD3.toByte(), 0x00, 0x02, 0x0D, 0x0A) // incluye \r\n binario
        val encoded = "5;ext=1\r\n".toByteArray() + payload + "\r\n3\r\nabc\r\n0\r\n\r\n".toByteArray()
        val decoder = ChunkedDecoder()

        val out = encoded.toList().chunked(2).flatMap { decoder.feed(it.toByteArray()).toList() }

        out shouldBe (payload + "abc".toByteArray()).toList()
        decoder.finished shouldBe true
    }

    @Test
    fun `S002 RF-19 un chunked corrupto se detecta`() {
        val decoder = ChunkedDecoder()

        runCatching { decoder.feed("zz\r\nabc".toByteArray()) }.isFailure shouldBe true
    }
}
