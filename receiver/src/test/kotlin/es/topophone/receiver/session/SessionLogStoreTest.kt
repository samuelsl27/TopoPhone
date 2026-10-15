package es.topophone.receiver.session

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Registro de sesiones del receptor (S002/RF-42 a RF-44, S003/RF-24). */
class SessionLogStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var now = Instant.parse("2026-10-15T08:30:00Z")
    private fun store(max: Int = 10, partSize: Long = SessionLogStore.PART_SIZE_BYTES) =
        SessionLogStore(folder.root, clock = { now }, maxSessions = max, partSizeBytes = partSize)

    private fun RecordedSession.receiverBytes() = receiverFiles.flatMap { it.readBytes().toList() }

    private fun RecordedSession.correctionBytes() = correctionFiles.flatMap { it.readBytes().toList() }

    @Test
    fun `S002 RF-42 los datos recibidos se guardan tal como llegan, binario incluido`() {
        val bytes = "\$GNGGA,1*00\r\n".toByteArray() + byteArrayOf(0xD3.toByte(), 0x00, 0x7F) + "\$GNGST,2*00\r\n".toByteArray()
        val store = store()

        store.start("RS3").shouldNotBeNull().apply {
            write(bytes, 0, 5)
            write(bytes, 5, bytes.size - 5)
            close()
        }

        store.latest().shouldNotBeNull().receiverBytes() shouldBe bytes.toList()
    }

    @Test
    fun `S003 RF-24 las correcciones enviadas al receptor se guardan en la sesion tal como se envian`() {
        val rtcm = byteArrayOf(0xD3.toByte(), 0x00, 0x13, 0x3E, 0xD0.toByte(), 0x00, 0x03)
        val store = store()

        store.start("RS3")!!.apply {
            write(byteArrayOf(1, 2), 0, 2)
            writeCorrections(rtcm.copyOfRange(0, 3))
            writeCorrections(rtcm.copyOfRange(3, rtcm.size))
            close()
        }

        val session = store.latest().shouldNotBeNull()
        session.correctionBytes() shouldBe rtcm.toList()
        session.correctionFiles.single().name shouldBe "corrections-0001.rtcm3"
    }

    @Test
    fun `S003 RF-24 sin correcciones no hay fichero de correcciones`() {
        val store = store()

        store.start("RS3")!!.close()

        store.latest()!!.correctionFiles shouldHaveSize 0
    }

    @Test
    fun `S002 RF-42 cada sesion tiene su carpeta con la fecha y hora de inicio en UTC y sus metadatos`() {
        val store = store()

        store.start("reach-RS3")!!.apply {
            now = now.plusSeconds(90)
            close()
        }

        val session = store.latest().shouldNotBeNull()
        session.id shouldBe "20261015-083000"
        session.source shouldBe SessionSource.RECORDED
        session.receiver shouldBe "reach-RS3"
        session.startedAt shouldBe Instant.parse("2026-10-15T08:30:00Z")
        session.endedAt shouldBe Instant.parse("2026-10-15T08:31:30Z")
        session.receiverFiles.single().name shouldBe "receiver-0001.nmea"
    }

    @Test
    fun `S003 RF-24 una sesion larga se reparte en partes que juntas dan los bytes tal como llegaron`() {
        val store = store(partSize = 10)
        val bytes = ByteArray(25) { it.toByte() }

        store.start("RS3")!!.apply {
            write(bytes, 0, 7)
            write(bytes, 7, 18)
            close()
        }

        val session = store.latest()!!
        session.receiverFiles.map { it.name } shouldBe listOf("receiver-0001.nmea", "receiver-0002.nmea", "receiver-0003.nmea")
        session.receiverBytes() shouldBe bytes.toList()
        session.sizeBytes shouldBe 25L + File(session.directory, SessionLogStore.METADATA_FILE).length()
    }

    @Test
    fun `S002 RF-43 la ultima sesion es la mas reciente y sin sesiones no hay ninguna`() {
        val store = store()
        store.latest().shouldBeNull()

        store.start("RS3")!!.close()
        now = now.plusSeconds(65)
        store.start("RS3")!!.close()

        store.latest()!!.id shouldBe "20261015-083105"
        store.sessions().map { it.id } shouldBe listOf("20261015-083105", "20261015-083000")
    }

    @Test
    fun `S002 RF-44 se conservan como maximo las 10 ultimas sesiones`() {
        val store = store()

        repeat(13) {
            store.start("RS3")!!.close()
            now = now.plusSeconds(1)
        }

        store.sessions() shouldHaveSize 10
        store.sessions().first().id shouldBe "20261015-083012"
        store.sessions().last().id shouldBe "20261015-083003"
    }

    @Test
    fun `S002 RF-42 dos sesiones en el mismo segundo no se sobrescriben`() {
        val store = store()

        store.start("RS3")!!.apply {
            write(byteArrayOf(1), 0, 1)
            close()
        }
        store.start("RS3")!!.apply {
            write(byteArrayOf(2), 0, 1)
            close()
        }

        store.sessions().map { it.receiverBytes().single() }.toSet() shouldBe setOf<Byte>(1, 2)
    }

    @Test
    fun `S002 RF-42 los ficheros sueltos de la version anterior pasan a ser sesiones con su carpeta`() {
        File(folder.root, "20261014-070000.nmea").writeBytes(byteArrayOf(7, 8, 9))

        val session = store().sessions().single()

        session.id shouldBe "20261014-070000"
        session.startedAt shouldBe Instant.parse("2026-10-14T07:00:00Z")
        session.source shouldBe SessionSource.RECORDED
        session.receiverBytes() shouldBe listOf<Byte>(7, 8, 9)
        File(folder.root, "20261014-070000.nmea").exists() shouldBe false
    }
}
