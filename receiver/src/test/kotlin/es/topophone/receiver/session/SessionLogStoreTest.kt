package es.topophone.receiver.session

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Registro de sesiones del receptor (S002/RF-42, S002/RF-44). */
class SessionLogStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var now = Instant.parse("2026-10-15T08:30:00Z")
    private fun store(max: Int = 10) = SessionLogStore(folder.root, clock = { now }, maxSessions = max)

    @Test
    fun `S002 RF-42 los datos recibidos se guardan tal como llegan, binario incluido`() {
        val bytes = "\$GNGGA,1*00\r\n".toByteArray() + byteArrayOf(0xD3.toByte(), 0x00, 0x7F) + "\$GNGST,2*00\r\n".toByteArray()
        val store = store()

        store.start("RS3").shouldNotBeNull().apply {
            write(bytes, 0, 5)
            write(bytes, 5, bytes.size - 5)
            close()
        }

        store.latest().shouldNotBeNull().readBytes().toList() shouldBe bytes.toList()
    }

    @Test
    fun `S002 RF-42 cada sesion tiene su fichero con la fecha y hora de inicio en UTC`() {
        val store = store()

        store.start("RS3")!!.close()

        store.latest()!!.name shouldBe "20261015-083000.nmea"
    }

    @Test
    fun `S002 RF-43 la ultima sesion es la mas reciente y sin sesiones no hay ninguna`() {
        val store = store()
        store.latest().shouldBeNull()

        store.start("RS3")!!.close()
        now = now.plusSeconds(65)
        store.start("RS3")!!.close()

        store.latest()!!.name shouldBe "20261015-083105.nmea"
    }

    @Test
    fun `S002 RF-44 se conservan como maximo las 10 ultimas sesiones`() {
        val store = store()

        repeat(13) {
            store.start("RS3")!!.close()
            now = now.plusSeconds(1)
        }

        store.sessions() shouldHaveSize 10
        store.sessions().first().name shouldBe "20261015-083012.nmea"
        store.sessions().last().name shouldBe "20261015-083003.nmea"
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

        store.sessions().map { it.readBytes().single() }.toSet() shouldBe setOf<Byte>(1, 2)
    }
}
