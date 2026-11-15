package es.topophone.core.survey.guide

import es.topophone.core.gnss.nmea.Constellation
import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.gnss.nmea.EpochAssembler
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.GstAccuracy
import es.topophone.core.gnss.nmea.GsvKey
import es.topophone.core.gnss.nmea.NmeaFramer
import es.topophone.core.gnss.nmea.RmcData
import es.topophone.core.gnss.nmea.SatellitesInView
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.gnss.nmea.ZdaData
import es.topophone.core.model.guide.CheckCause
import es.topophone.core.model.guide.CheckId
import es.topophone.core.model.guide.CheckStatus
import es.topophone.core.model.guide.ReceiverCheck
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import java.time.LocalDate
import org.junit.jupiter.api.Test

/**
 * Las comprobaciones de NMEA de la guía: frecuencia de la GGA, frases, solución,
 * edad de las correcciones y hora (S043/RF-5, RF-6, RF-9, RF-10).
 */
class ReceiverCheckerNmeaTest {

    private val date = LocalDate.of(2026, 11, 15)

    private val checker = ReceiverChecker()

    private fun ggaFix(status: SolutionStatus, age: Double?) = GgaFix(
        utcTime = "000000.00",
        latitudeDeg = 40.0,
        longitudeDeg = -3.0,
        status = status,
        satellites = 20,
        hdop = 0.6,
        altitudeM = 650.0,
        geoidSeparationM = 50.0,
        correctionAgeS = age,
        rawSentence = "",
    )

    private val gsv = mapOf(GsvKey(Constellation.GPS, 1) to SatellitesInView(8, emptyList()))

    /** Una época con lo que se pide; las que no se piden no llevan esa frase. */
    private fun epoch(
        utcMs: Int,
        atMs: Long = utcMs.toLong(),
        gga: Boolean = true,
        gst: Boolean = false,
        rmc: Boolean = false,
        zda: Boolean = false,
        satellites: Boolean = false,
        status: SolutionStatus = SolutionStatus.FIX,
        age: Double? = null,
        withDate: Boolean = false,
    ) = Epoch(
        utcMillisOfDay = utcMs,
        date = if (withDate) date else null,
        gga = if (gga) ggaFix(status, age) else null,
        gst = if (gst) GstAccuracy("000000.00", 0.01, 0.01, 0.02) else null,
        rmc = if (rmc) RmcData(utcMs, date, true, 0.0, null) else null,
        vtg = null,
        zda = if (zda) ZdaData(utcMs, date) else null,
        basePosition = null,
        tilt = null,
        gsa = emptyMap(),
        gsv = if (satellites) gsv else emptyMap(),
        completedAtMs = atMs,
    )

    private fun snapshot(vararg epochs: Epoch) = WindowSnapshot(epochs.toList(), rtcmDelta = null)

    private fun List<ReceiverCheck>.of(id: CheckId): ReceiverCheck? = firstOrNull { it.id == id }

    /** Diez épocas a 1 Hz con todas las frases. */
    private fun healthy(): List<Epoch> = (0 until 10).map {
        epoch(it * 1000, gst = true, rmc = true, zda = true, satellites = true, withDate = true)
    }

    @Test
    fun `S043 RF-10 sin ninguna epoca todas las comprobaciones salen Sin datos y remiten al diagnostico`() {
        val checks = checker.check(WindowSnapshot(emptyList(), null))

        checks shouldHaveSize 4
        checks.all { it.status == CheckStatus.NO_DATA && it.causes == listOf(CheckCause.NO_DATA) } shouldBe true
        checker.check(WindowSnapshot(emptyList(), null), CheckContext(ntripOpen = true)) shouldHaveSize 5
    }

    @Test
    fun `S043 RF-6 con la salida completa a 1 Hz todo sale Correcto`() {
        val checks = checker.check(snapshot(*healthy().toTypedArray()))

        checks.of(CheckId.GGA_RATE)!!.status shouldBe CheckStatus.OK
        checks.of(CheckId.GGA_RATE)!!.measured shouldBe "1.0 Hz"
        checks.of(CheckId.SENTENCES)!!.status shouldBe CheckStatus.OK
        checks.of(CheckId.SENTENCES)!!.measured shouldBe "GST, GSV, ZDA, RMC"
        checks.of(CheckId.SOLUTION)!!.status shouldBe CheckStatus.OK
        checks.of(CheckId.UTC_TIME)!!.status shouldBe CheckStatus.OK
        checks.of(CheckId.UTC_TIME)!!.measured shouldBe "2026-11-15"
        checks.of(CheckId.CORRECTION_AGE).shouldBeNull() // sin NTRIP ni edad no hay correcciones que mirar
    }

    @Test
    fun `S043 RF-6 sin ninguna GGA falla la frecuencia y la solucion no tiene datos`() {
        val checks = checker.check(snapshot(*(0 until 10).map { epoch(it * 1000, gga = false, gst = true) }.toTypedArray()))

        checks.of(CheckId.GGA_RATE)!!.status shouldBe CheckStatus.FAIL
        checks.of(CheckId.GGA_RATE)!!.causes shouldBe listOf(CheckCause.NO_GGA)
        checks.of(CheckId.SOLUTION)!!.status shouldBe CheckStatus.NO_DATA
    }

    @Test
    fun `S043 RF-6 una GGA cada dos segundos avisa de que va por debajo de 1 Hz`() {
        val checks = checker.check(snapshot(*(0 until 10).map { epoch(it * 1000, gga = it % 2 == 0) }.toTypedArray()))

        checks.of(CheckId.GGA_RATE)!!.status shouldBe CheckStatus.WARNING
        checks.of(CheckId.GGA_RATE)!!.measured shouldBe "0.5 Hz"
        checks.of(CheckId.GGA_RATE)!!.causes shouldBe listOf(CheckCause.GGA_BELOW_1HZ)
    }

    @Test
    fun `S043 RF-9 la GGA a 1 Hz con el resto de frases a 5 Hz avisa de que la salida va mas lenta que el GNSS`() {
        val epochs = (0 until 50).map { epoch(it * 200, gga = it % 5 == 0, rmc = true) }

        val check = checker.check(snapshot(*epochs.toTypedArray())).of(CheckId.GGA_RATE)!!

        check.status shouldBe CheckStatus.WARNING
        check.measured shouldBe "1.0 Hz"
        check.causes shouldBe listOf(CheckCause.GGA_SLOWER_THAN_GNSS)
    }

    @Test
    fun `S043 RF-6 la GGA a 5 Hz con las demas a 1 Hz es correcta`() {
        val epochs = (0 until 50).map { epoch(it * 200, gst = it % 5 == 0, rmc = it % 5 == 0, zda = it % 5 == 0) }

        val check = checker.check(snapshot(*epochs.toTypedArray())).of(CheckId.GGA_RATE)!!

        check.status shouldBe CheckStatus.OK
        check.measured shouldBe "5.0 Hz"
    }

    @Test
    fun `S043 RF-6 con una sola GGA, a los 6 s de datos avisa y a los 2 s todavia no se sabe`() {
        val long = checker.check(snapshot(epoch(0), *(1..6).map { epoch(it * 1000, gga = false, gst = true) }.toTypedArray()))
        val short = checker.check(snapshot(epoch(0), epoch(1000, gga = false, gst = true), epoch(2000, gga = false, gst = true)))

        long.of(CheckId.GGA_RATE)!!.status shouldBe CheckStatus.WARNING
        short.of(CheckId.GGA_RATE)!!.status shouldBe CheckStatus.NO_DATA
    }

    @Test
    fun `S043 RF-6 cada frase que falta es una causa de aviso y la medida dice las que si llegan`() {
        val epochs = (0 until 10).map { epoch(it * 1000, gst = false, rmc = true, zda = false, satellites = true) }

        val check = checker.check(snapshot(*epochs.toTypedArray())).of(CheckId.SENTENCES)!!

        check.status shouldBe CheckStatus.WARNING
        check.causes shouldBe listOf(CheckCause.MISSING_GST, CheckCause.MISSING_ZDA)
        check.measured shouldBe "GSV, RMC"
    }

    @Test
    fun `S043 RF-6 sin ninguna de las cuatro frases todas faltan`() {
        val check = checker.check(snapshot(*(0 until 10).map { epoch(it * 1000) }.toTypedArray())).of(CheckId.SENTENCES)!!

        check.causes shouldBe listOf(CheckCause.MISSING_GST, CheckCause.MISSING_GSV, CheckCause.MISSING_ZDA, CheckCause.MISSING_RMC)
        check.measured.shouldBeNull()
    }

    @Test
    fun `S043 RF-6 la solucion se mira en la ultima GGA y solo el FIX es correcto`() {
        fun solution(status: SolutionStatus) = checker
            .check(snapshot(epoch(0), epoch(1000, status = status))).of(CheckId.SOLUTION)!!

        solution(SolutionStatus.FIX).status shouldBe CheckStatus.OK
        solution(SolutionStatus.FLOAT).status shouldBe CheckStatus.WARNING
        solution(SolutionStatus.FLOAT).measured shouldBe "FLOAT"
        solution(SolutionStatus.FLOAT).causes shouldBe listOf(CheckCause.SOLUTION_NOT_FIX)
        solution(SolutionStatus.SINGLE).status shouldBe CheckStatus.WARNING
        solution(SolutionStatus.NONE).status shouldBe CheckStatus.FAIL
        solution(SolutionStatus.NONE).causes shouldBe listOf(CheckCause.NO_POSITION)
    }

    @Test
    fun `S043 RF-6 la edad de las correcciones es correcta hasta 5 s, avisa hasta 30 s y falla despues`() {
        fun age(value: Double?, ntrip: Boolean = false) = checker
            .check(snapshot(epoch(0), epoch(1000, age = value)), CheckContext(ntripOpen = ntrip)).of(CheckId.CORRECTION_AGE)

        age(1.0)!!.status shouldBe CheckStatus.OK
        age(5.0)!!.status shouldBe CheckStatus.OK
        age(12.0)!!.status shouldBe CheckStatus.WARNING
        age(12.0)!!.causes shouldBe listOf(CheckCause.CORRECTIONS_OLD)
        age(30.0)!!.status shouldBe CheckStatus.WARNING
        age(45.0)!!.status shouldBe CheckStatus.FAIL
        age(45.0)!!.causes shouldBe listOf(CheckCause.CORRECTIONS_STALE)
        age(12.0)!!.measured shouldBe "12.0 s"
    }

    @Test
    fun `S043 RF-6 sin edad de correcciones solo hay comprobacion si el NTRIP esta abierto y entonces falla`() {
        val without = checker.check(snapshot(epoch(0), epoch(1000, age = null))).of(CheckId.CORRECTION_AGE)
        val ntrip = checker.check(snapshot(epoch(0), epoch(1000, age = null)), CheckContext(ntripOpen = true)).of(CheckId.CORRECTION_AGE)

        without.shouldBeNull()
        ntrip.shouldNotBeNull().status shouldBe CheckStatus.FAIL
    }

    @Test
    fun `S043 RF-6 sin fecha en ZDA ni RMC la hora avisa y con la fecha es correcta`() {
        val noDate = checker.check(snapshot(*(0 until 10).map { epoch(it * 1000) }.toTypedArray())).of(CheckId.UTC_TIME)!!
        val withDate = checker.check(snapshot(*(0 until 10).map { epoch(it * 1000, zda = true, withDate = true) }.toTypedArray()))

        noDate.status shouldBe CheckStatus.WARNING
        noDate.causes shouldBe listOf(CheckCause.NO_TIME)
        withDate.of(CheckId.UTC_TIME)!!.status shouldBe CheckStatus.OK
    }

    @Test
    fun `S043 RF-6 una hora que retrocede falla y el paso de medianoche no`() {
        val goesBack = checker.check(snapshot(epoch(10_000, withDate = true), epoch(5_000, withDate = true))).of(CheckId.UTC_TIME)!!
        val midnight = checker.check(snapshot(epoch(86_399_000, withDate = true), epoch(0, withDate = true), epoch(1_000, withDate = true)))

        goesBack.status shouldBe CheckStatus.FAIL
        goesBack.causes shouldBe listOf(CheckCause.TIME_NOT_ADVANCING)
        midnight.of(CheckId.UTC_TIME)!!.status shouldBe CheckStatus.OK
    }

    @Test
    fun `S043 RF-9 un fallo o un aviso trae el paso que lo resuelve y lo correcto no`() {
        val withSteps = ReceiverChecker { id -> "paso_${id.name.lowercase()}" }

        val epochs = (0 until 10).map { epoch(it * 1000, gga = it % 2 == 0, status = SolutionStatus.FLOAT) }
        val checks = withSteps.check(snapshot(*epochs.toTypedArray()))

        checks.of(CheckId.GGA_RATE)!!.stepId shouldBe "paso_gga_rate"
        checks.of(CheckId.SOLUTION)!!.stepId shouldBe "paso_solution"
        withSteps.check(snapshot(*healthy().toTypedArray())).of(CheckId.GGA_RATE)!!.stepId.shouldBeNull()
        withSteps.check(WindowSnapshot(emptyList(), null)).all { it.stepId != null } shouldBe true
    }

    @Test
    fun `S043 RF-11 comprobar no cambia la ventana ni lo que contiene`() {
        val window = snapshot(*healthy().toTypedArray())

        checker.check(window)

        window.epochs shouldHaveSize 10
    }

    // ----- Con sesiones grabadas (RNF-T12) -----

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
        ?: error("No se encuentra la raíz del repositorio")

    /** Las épocas de una sesión grabada; llegan con la hora de la grabación como reloj, sin saltos de medianoche. */
    private fun recorded(name: String): List<Epoch> {
        val assembler = EpochAssembler()
        val epochs = mutableListOf<Epoch>()
        var offset = 0L
        var previous = -1
        for (sentence in NmeaFramer().feed(File(root, "fixtures/nmea/sintetico/$name.nmea").readBytes())) {
            val completed = assembler.feed(sentence, 0)
            if (completed != null) {
                if (previous >= 0 && completed.utcMillisOfDay < previous) offset += 86_400_000L
                previous = completed.utcMillisOfDay
                epochs += completed.copy(completedAtMs = completed.utcMillisOfDay + offset)
            }
        }
        return epochs
    }

    private fun lastSeconds(epochs: List<Epoch>, seconds: Int = 10): WindowSnapshot {
        val end = epochs.last().completedAtMs
        return WindowSnapshot(epochs.filter { it.completedAtMs >= end - seconds * 1000L }, null)
    }

    @Test
    fun `S043 RNF-T12 la sesion completa del RS3 cruza la medianoche y da todo correcto a 5 Hz`() {
        val checks = checker.check(lastSeconds(recorded("rs3-completo")))

        checks.of(CheckId.GGA_RATE)!!.status shouldBe CheckStatus.OK
        checks.of(CheckId.SOLUTION)!!.status shouldBe CheckStatus.OK
        checks.of(CheckId.UTC_TIME)!!.status shouldBe CheckStatus.OK
    }

    @Test
    fun `S043 RNF-T12 la sesion con cortes del RS3 va a 5 Hz y no emite satelites en vista`() {
        val checks = checker.check(lastSeconds(recorded("rs3-corte")))

        checks.of(CheckId.GGA_RATE)!!.status shouldBe CheckStatus.OK
        checks.of(CheckId.SENTENCES)!!.causes shouldBe listOf(CheckCause.MISSING_GSV)
        checks.of(CheckId.SOLUTION)!!.status shouldBe CheckStatus.OK
    }

    @Test
    fun `S043 RNF-T12 la sesion con inclinacion tampoco emite satelites en vista`() {
        val checks = checker.check(lastSeconds(recorded("rs3-inclinacion")))

        checks.of(CheckId.SENTENCES)!!.causes shouldBe listOf(CheckCause.MISSING_GSV)
        checks.of(CheckId.UTC_TIME)!!.status shouldBe CheckStatus.OK
    }

    @Test
    fun `S043 RNF una comprobacion con los datos ya recibidos tarda menos de 1 s`() {
        val window = lastSeconds(recorded("rs3-corte"))
        repeat(3) { checker.check(window) } // calentamiento de la JVM

        val from = System.nanoTime()
        repeat(100) { checker.check(window) }
        val perCheckMs = (System.nanoTime() - from) / 100 / 1_000_000

        (perCheckMs < 1_000) shouldBe true
    }
}
