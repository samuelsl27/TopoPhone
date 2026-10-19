package es.topophone.core.gnss.rtcm

import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.time.measureTime
import org.junit.jupiter.api.Test

/** Tramas RTCM 3 y su recuento (S007/RF-32, RF-33), con el flujo real de `fixtures/rtcm/rgapa-AVL13M`. */
class RtcmFramerTest {

    private val stream: ByteArray by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
        File(root, "fixtures/rtcm/rgapa-AVL13M.rtcm3").readBytes()
    }

    /** Lo que dice `rgapa-AVL13M.yaml`. */
    private val expected = mapOf(
        1006 to 2L,
        1008 to 3L,
        1013 to 3L,
        1033 to 3L,
        1075 to 24L,
        1085 to 25L,
        1095 to 25L,
        1125 to 25L,
        1230 to 3L,
    )

    private fun count(bytes: ByteArray, blockSize: Int = bytes.size): RtcmStats {
        val framer = RtcmFramer()
        var stats = RtcmStats()
        for (start in bytes.indices step blockSize) {
            stats += framer.feed(bytes, start, minOf(blockSize, bytes.size - start))
        }
        return stats
    }

    /** Inicio y tipo de cada trama del flujo, recorriendo las longitudes desde la primera (byte 278). */
    private fun frames(bytes: ByteArray): List<Pair<Int, Int>> = buildList {
        var at = FIRST_FRAME
        while (at + 6 <= bytes.size) {
            val length = ((bytes[at + 1].toInt() and 0x03) shl 8) or (bytes[at + 2].toInt() and 0xFF)
            if (at + 6 + length > bytes.size) break
            add(at to (((bytes[at + 3].toInt() and 0xFF) shl 4) or ((bytes[at + 4].toInt() and 0xFF) shr 4)))
            at += 6 + length
        }
    }

    @Test
    fun `S007 RF-32 el flujo real da los 113 mensajes de cada tipo, sin contar el principio ni el final cortados`() {
        val stats = count(stream)

        stats.counts shouldBe expected
        stats.crcErrors shouldBe 0
    }

    @Test
    fun `S007 RF-32 el mismo flujo en bloques de 1 byte da el mismo recuento`() {
        val stats = count(stream, blockSize = 1)

        stats.counts shouldBe expected
        stats.crcErrors shouldBe 0
    }

    @Test
    fun `S007 RF-33 un byte erroneo dentro de una trama cuenta un error y la trama siguiente ya se cuenta`() {
        val (start, type) = frames(stream)[10]
        val corrupted = stream.copyOf().also { it[start + 8] = (it[start + 8].toInt() xor 0x5A).toByte() }

        val stats = count(corrupted)

        stats.crcErrors shouldBe 1
        stats.counts shouldBe expected + (type to expected.getValue(type) - 1)
    }

    @Test
    fun `S007 RF-33 una trama sin su byte de inicio cuenta un error y el resto se recupera`() {
        val (start, type) = frames(stream)[20]
        val corrupted = stream.copyOf().also { it[start] = 0 }

        val stats = count(corrupted, blockSize = 7)

        stats.crcErrors shouldBe 1
        stats.counts shouldBe expected + (type to expected.getValue(type) - 1)
    }

    @Test
    fun `S015 RF-12 onFrame entrega la carga de cada trama buena, sin cambiar el recuento`() {
        val payloads = ArrayList<Pair<Int, ByteArray>>()
        val framer = RtcmFramer { type, payload -> payloads += type to payload }
        var stats = RtcmStats()
        for (start in stream.indices step 700) stats += framer.feed(stream, start, minOf(700, stream.size - start))

        stats.counts shouldBe expected
        payloads.size shouldBe 113
        payloads.groupingBy { it.first }.eachCount().mapValues { it.value.toLong() } shouldBe expected
        // La carga empieza por su tipo y mide lo que dice la cabecera de su trama.
        val starts = frames(stream).map { it.first }
        payloads.forEachIndexed { i, (type, payload) ->
            RtcmBits(payload).unsigned(12) shouldBe type.toLong()
            val length = ((stream[starts[i] + 1].toInt() and 0x03) shl 8) or (stream[starts[i] + 2].toInt() and 0xFF)
            payload.size shouldBe length
        }
    }

    @Test
    fun `S015 RF-12 la carga entregada es una copia que no cambia con lo que llega despues`() {
        val payloads = ArrayList<ByteArray>()
        val framer = RtcmFramer { _, payload -> payloads += payload }
        framer.feed(stream, 0, 2_000)
        val first = payloads.first().copyOf()

        framer.feed(stream, 2_000, stream.size - 2_000)

        payloads.first().contentEquals(first) shouldBe true
    }

    @Test
    fun `S007 RF-33 el CRC24Q coincide con el valor de comprobacion publicado`() {
        val check = "123456789".toByteArray(Charsets.US_ASCII)

        Crc24q.compute(check, 0, check.size) shouldBe 0xCDE703
    }

    @Test
    fun `S007 RF-32 los tipos conocidos se clasifican y el resto queda como desconocido`() {
        RtcmMessages.kind(1077) shouldBe RtcmKind.Msm(RtcmSystem.GPS, 7)
        RtcmMessages.kind(1085) shouldBe RtcmKind.Msm(RtcmSystem.GLONASS, 5)
        RtcmMessages.kind(1094) shouldBe RtcmKind.Msm(RtcmSystem.GALILEO, 4)
        RtcmMessages.kind(1101) shouldBe RtcmKind.Msm(RtcmSystem.SBAS, 1)
        RtcmMessages.kind(1117) shouldBe RtcmKind.Msm(RtcmSystem.QZSS, 7)
        RtcmMessages.kind(1125) shouldBe RtcmKind.Msm(RtcmSystem.BEIDOU, 5)
        RtcmMessages.kind(1137) shouldBe RtcmKind.Msm(RtcmSystem.NAVIC, 7)
        RtcmMessages.kind(1004) shouldBe RtcmKind.LegacyObservables(RtcmSystem.GPS)
        RtcmMessages.kind(1012) shouldBe RtcmKind.LegacyObservables(RtcmSystem.GLONASS)
        RtcmMessages.kind(1005) shouldBe RtcmKind.ReferenceStation
        RtcmMessages.kind(1006) shouldBe RtcmKind.ReferenceStation
        RtcmMessages.kind(1008) shouldBe RtcmKind.AntennaDescriptor
        RtcmMessages.kind(1033) shouldBe RtcmKind.ReceiverAntennaDescriptor
        RtcmMessages.kind(1019) shouldBe RtcmKind.Ephemeris(RtcmSystem.GPS)
        RtcmMessages.kind(1020) shouldBe RtcmKind.Ephemeris(RtcmSystem.GLONASS)
        RtcmMessages.kind(1042) shouldBe RtcmKind.Ephemeris(RtcmSystem.BEIDOU)
        RtcmMessages.kind(1044) shouldBe RtcmKind.Ephemeris(RtcmSystem.QZSS)
        RtcmMessages.kind(1046) shouldBe RtcmKind.Ephemeris(RtcmSystem.GALILEO)
        RtcmMessages.kind(1230) shouldBe RtcmKind.GlonassBiases
        RtcmMessages.kind(1013) shouldBe RtcmKind.Unknown
        RtcmMessages.kind(4091) shouldBe RtcmKind.Unknown
    }

    @Test
    fun `S007 RNF contar 4 KB de correcciones lleva menos de 50 ms`() {
        val block = stream.copyOfRange(FIRST_FRAME, FIRST_FRAME + 4096)
        repeat(50) { count(block) } // calentamiento de la JVM

        val time = measureTime { count(block) }

        time.inWholeMilliseconds shouldBeLessThan 50
    }

    private companion object {
        const val FIRST_FRAME = 278
    }
}
