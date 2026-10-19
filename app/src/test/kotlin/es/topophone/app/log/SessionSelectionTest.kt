package es.topophone.app.log

import es.topophone.receiver.session.RecordedSession
import es.topophone.receiver.session.SessionSource
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import java.io.File
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Qué sesiones grabadas entran en el informe de sistema (S014/RF-12; plan, A6). */
class SessionSelectionTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var clock = 1_000_000L

    /** Una sesión con partes del receptor y de las correcciones de los tamaños dados, escritas en ese orden. */
    private fun session(
        id: String,
        startedAt: String,
        source: SessionSource = SessionSource.RECORDED,
        receiver: List<Int>,
        corrections: List<Int> = emptyList(),
    ): RecordedSession {
        val dir = folder.newFolder(id)
        File(dir, "session.properties").writeText("source=x\n")
        fun part(prefix: String, ext: String, sizes: List<Int>) = sizes.mapIndexed { i, size ->
            File(dir, "%s%04d%s".format(prefix, i + 1, ext)).apply {
                writeBytes(ByteArray(size))
                setLastModified(clock)
                clock += 1_000
            }
        }
        val receiverFiles = part("receiver-", ".nmea", receiver)
        val correctionFiles = part("corrections-", ".rtcm3", corrections)
        return RecordedSession(id, dir, source, "RS3", Instant.parse(startedAt), null, receiverFiles, correctionFiles)
    }

    private fun names(selected: List<SelectedSession>) = selected.map { it.id to it.files.map(File::getName) }

    @Test
    fun `S014 RF-12 entran las mas recientes que quepan entre todas`() {
        val old = session("20261017-090000", "2026-10-17T09:00:00Z", receiver = listOf(400))
        val middle = session("20261018-090000", "2026-10-18T09:00:00Z", receiver = listOf(300))
        val recent = session("20261019-090000", "2026-10-19T09:00:00Z", receiver = listOf(300))

        val selected = SessionSelection.select(listOf(old, middle, recent), activeId = null, limitBytes = 700)

        selected.map { it.id } shouldBe listOf("20261019-090000", "20261018-090000")
        selected.all { !it.partial } shouldBe true
    }

    @Test
    fun `S014 RF-12 la sesion en curso va la primera`() {
        val active = session("20261019-080000", "2026-10-19T08:00:00Z", receiver = listOf(100))
        val later = session("20261019-090000", "2026-10-19T09:00:00Z", receiver = listOf(100))

        SessionSelection.select(listOf(later, active), activeId = "20261019-080000", limitBytes = 1_000).map { it.id } shouldBe
            listOf("20261019-080000", "20261019-090000")
    }

    @Test
    fun `S014 RF-12 las sesiones importadas no entran`() {
        val imported = session("20261019-100000", "2026-10-19T10:00:00Z", SessionSource.IMPORTED, receiver = listOf(10))
        val recorded = session("20261019-090000", "2026-10-19T09:00:00Z", receiver = listOf(10))

        SessionSelection.select(listOf(imported, recorded), activeId = null, limitBytes = 1_000).map { it.id } shouldBe
            listOf("20261019-090000")
    }

    @Test
    fun `S014 RF-12 si la mas reciente no cabe, entra su parte mas reciente`() {
        val big = session("20261019-090000", "2026-10-19T09:00:00Z", receiver = listOf(400, 400, 400), corrections = listOf(100, 100))
        val older = session("20261018-090000", "2026-10-18T09:00:00Z", receiver = listOf(10))

        val selected = SessionSelection.select(listOf(older, big), activeId = null, limitBytes = 700)

        val only = selected.single()
        only.partial shouldBe true
        // Los metadatos y las partes escritas al final, mientras quepan: las dos de correcciones y la última del receptor.
        only.files.map { it.name }.toSet() shouldBe
            setOf("session.properties", "corrections-0002.rtcm3", "corrections-0001.rtcm3", "receiver-0003.nmea")
        only.files.sumOf { it.length() } shouldBeLessThanOrEqual 700L
    }

    @Test
    fun `S014 RF-12 sin sesiones el informe va sin sesiones`() {
        SessionSelection.select(emptyList(), activeId = null) shouldBe emptyList()
    }

    @Test
    fun `S014 RF-12 una sesion entra con sus metadatos y sus partes`() {
        val one = session("20261019-090000", "2026-10-19T09:00:00Z", receiver = listOf(10, 10), corrections = listOf(5))

        names(SessionSelection.select(listOf(one), activeId = null)) shouldBe listOf(
            "20261019-090000" to listOf("session.properties", "receiver-0001.nmea", "receiver-0002.nmea", "corrections-0001.rtcm3"),
        )
    }
}
