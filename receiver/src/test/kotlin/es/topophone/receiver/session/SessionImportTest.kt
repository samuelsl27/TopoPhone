package es.topophone.receiver.session

import es.topophone.core.gnss.nmea.NmeaChecksum
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Importar un fichero NMEA como sesión para reproducirlo (S003/RF-33, S003/RF-34). */
class SessionImportTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val now = Instant.parse("2026-10-16T09:00:00Z")
    private fun store(maxImport: Long = SessionLogStore.MAX_IMPORT_BYTES) =
        SessionLogStore(folder.root, clock = { now }, freeSpaceBytes = { Long.MAX_VALUE }, maxImportBytes = maxImport)

    private fun nmea(body: String) = "$" + body + "*" + "%02X".format(NmeaChecksum.compute(body)) + "\r\n"

    private val log = (
        nmea("GNGGA,235958.00,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001") +
            nmea("GNZDA,235958.00,14,10,2026,00,00") +
            nmea("GNGGA,235959.00,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001") +
            nmea("GNGGA,000003.00,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001")
        ).toByteArray()

    @Test
    fun `S003 RF-33 un fichero NMEA de texto se importa como sesion Importada lista para reproducir`() {
        val store = store()

        val result = store.import("log-rs3.nmea", ByteArrayInputStream(log), sizeHint = log.size.toLong())

        val id = result.shouldBeInstanceOf<ImportResult.Imported>().id
        val session = store.sessions().single()
        session.id shouldBe id
        session.source shouldBe SessionSource.IMPORTED
        session.receiver shouldBe "log-rs3.nmea"
        session.receiverFiles.flatMap { it.readBytes().toList() } shouldBe log.toList()
    }

    @Test
    fun `S003 RF-33 el inicio y la duracion salen de las horas NMEA del fichero, tambien al pasar medianoche`() {
        val store = store()

        store.import("log-rs3.nmea", ByteArrayInputStream(log), sizeHint = null)

        val session = store.sessions().single()
        session.startedAt shouldBe Instant.parse("2026-10-14T23:59:58Z")
        session.endedAt shouldBe Instant.parse("2026-10-15T00:00:03Z")
    }

    @Test
    fun `S003 RF-33 sin fecha en el fichero el inicio es la hora de importacion`() {
        val store = store()
        val noDate = nmea("GNGGA,101530.00,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001").toByteArray()

        store.import("sin-fecha.nmea", ByteArrayInputStream(noDate), sizeHint = null)

        store.sessions().single().startedAt shouldBe now
    }

    @Test
    fun `S003 RF-34 un fichero sin frases NMEA validas con hora no se importa`() {
        val store = store()
        val binary = ByteArray(5_000) { (it * 31).toByte() } + nmea("GPGSV,1,1,01,05,62,045,48,1").toByteArray()

        store.import("binario.ubx", ByteArrayInputStream(binary), sizeHint = binary.size.toLong()) shouldBe ImportResult.NoNmea

        store.sessions() shouldHaveSize 0
        folder.root.listFiles().orEmpty().filter { it.isDirectory } shouldHaveSize 0
    }

    @Test
    fun `S003 RF-34 un fichero de mas de 200 MB no se importa`() {
        SessionLogStore.MAX_IMPORT_BYTES shouldBe 200_000_000L
        val store = store(maxImport = log.size - 1L)

        store.import("grande.nmea", ByteArrayInputStream(log), sizeHint = 300_000_000) shouldBe ImportResult.TooLarge
        store.import("grande.nmea", ByteArrayInputStream(log), sizeHint = null) shouldBe ImportResult.TooLarge

        store.sessions() shouldHaveSize 0
    }
}
