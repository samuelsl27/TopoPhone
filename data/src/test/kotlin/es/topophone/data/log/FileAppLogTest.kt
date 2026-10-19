package es.topophone.data.log

import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.log.LogLevel
import io.kotest.matchers.collections.shouldBeSortedWith
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** El log en ficheros: en orden, en disco antes de 1 s, cortado en ficheros y con su límite (S014/RF-1, RF-6, RF-8, RF-9, RNF). */
@OptIn(ExperimentalCoroutinesApi::class)
class FileAppLogTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mb = 1024L * 1024
    private var now = Instant.parse("2026-10-19T09:15:52.123Z").toEpochMilli()
    private val dir by lazy { File(folder.root, "log") }

    @After
    fun close() = scope.cancel()

    private fun log(sizeMb: kotlinx.coroutines.flow.Flow<Int> = flowOf(10)) = FileAppLog(dir, sizeMb, scope, clock = { now++ })

    /** El escritor puede borrar un fichero mientras se lee la lista: ese ya no cuenta. */
    private fun text(log: FileAppLog) = log.files().joinToString("") { runCatching { it.readText() }.getOrDefault("") }

    private fun awaitText(log: FileAppLog, expected: String) = runBlocking {
        withTimeout(5_000) { while (expected !in text(log)) delay(10) }
    }

    @Test
    fun `S014 RF-1 las anotaciones quedan en el fichero en orden`() {
        val log = log()

        log.info(LogCategory.RECEIVER, "Conectando: RS3")
        log.info(LogCategory.RECEIVER, "Conectado: RS3")
        log.record(LogLevel.WARNING, LogCategory.NTRIP, "Cortada")
        awaitText(log, "Cortada")

        text(log).lines().filter { it.isNotEmpty() }.map { it.substring(26) } shouldBe listOf(
            "INFO   receptor  Conectando: RS3",
            "INFO   receptor  Conectado: RS3",
            "AVISO  ntrip     Cortada",
        )
    }

    @Test
    fun `S014 RNF lo anotado esta en disco antes de 1 s, sin cerrar el log`() {
        val log = log()
        val start = System.nanoTime()

        log.error(LogCategory.APP, "Algo ha fallado")
        awaitText(log, "Algo ha fallado")

        ((System.nanoTime() - start) / 1_000_000) shouldBeLessThan 1_000
    }

    @Test
    fun `S014 RF-6 flushNow escribe en el momento aunque el escritor no haya llegado`() {
        val paused = StandardTestDispatcher() // Nunca avanza: el escritor de fondo no escribe nada.
        val log = FileAppLog(dir, flowOf(10), scope, clock = { now++ }, ioDispatcher = paused)

        log.error(LogCategory.APP, "Cierre inesperado: java.lang.IllegalStateException")
        log.flushNow()

        text(log) shouldContain "ERROR  app       Cierre inesperado: java.lang.IllegalStateException"
    }

    @Test
    fun `S014 RF-8 los ficheros se cortan y los mas antiguos se borran al pasar del limite`() {
        val log = log(flowOf(5))
        val filler = "x".repeat(1_000)

        log.info(LogCategory.APP, "primera")
        repeat(7_000) { log.info(LogCategory.APP, "linea $it $filler") }
        log.info(LogCategory.APP, "ultima")
        awaitText(log, "ultima")

        log.files().size shouldBeGreaterThan 1
        log.files().sumOf { it.length() } shouldBeLessThanOrEqual 5 * mb
        text(log) shouldNotContain "primera"
    }

    @Test
    fun `S014 RF-9 al bajar el limite se aplica en el momento`() {
        val size = MutableStateFlow(20)
        val log = log(size)
        val filler = "x".repeat(1_000)
        repeat(9_000) { log.info(LogCategory.APP, "linea $it $filler") }
        log.info(LogCategory.APP, "ultima")
        awaitText(log, "ultima")
        log.files().sumOf { it.length() } shouldBeLessThanOrEqual 20 * mb

        size.value = 5

        runBlocking { withTimeout(5_000) { while (log.files().sumOf { it.length() } > 5 * mb) delay(10) } }
        text(log) shouldContain "ultima"
    }

    @Test
    fun `S014 RF-8 al volver a abrir la app se sigue en el ultimo fichero`() {
        val first = log()
        first.info(LogCategory.APP, "antes")
        first.flushNow()
        val files = first.files().size

        val second = FileAppLog(dir, flowOf(10), scope, clock = { now++ })
        second.info(LogCategory.APP, "despues")
        awaitText(second, "despues")

        second.files().size shouldBe files
        text(second) shouldContain "antes"
    }

    @Test
    fun `S014 RF-11 los ficheros van del mas antiguo al mas reciente`() {
        val log = log(flowOf(5))
        val filler = "x".repeat(1_000)
        repeat(2_000) { log.info(LogCategory.APP, "linea $it $filler") }
        log.info(LogCategory.APP, "ultima")
        awaitText(log, "ultima")

        log.files().map { it.name } shouldBeSortedWith naturalOrder()
        log.files().last().readText() shouldContain "ultima"
    }
}
