package es.topophone.core.survey.guide

import es.topophone.core.gnss.nmea.Constellation
import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.gnss.nmea.EpochAssembler
import es.topophone.core.gnss.nmea.GsvKey
import es.topophone.core.gnss.nmea.NmeaFramer
import es.topophone.core.gnss.nmea.SatellitesInView
import es.topophone.core.gnss.nmea.TiltData
import es.topophone.core.gnss.nmea.TiltNotification
import es.topophone.core.gnss.nmea.TiltState
import es.topophone.core.model.guide.CheckCause
import es.topophone.core.model.guide.CheckId
import es.topophone.core.model.guide.CheckStatus
import es.topophone.core.model.guide.ReceiverCheck
import es.topophone.core.model.point.TiltMode
import es.topophone.core.model.project.CorrectionsDatum
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import org.junit.jupiter.api.Test

/** Las comprobaciones de inclinación, de RTCM y del datum de las correcciones (S043/RF-7, RF-8, RF-9). */
class ReceiverCheckerTiltRtcmTest {

    private val checker = ReceiverChecker()

    private fun tilt(state: TiltState, notification: TiltNotification = TiltNotification.NONE) =
        TiltData(0, state, notification, null, null, null, null)

    private fun epoch(atMs: Long, tilt: TiltData? = null, glonass: Boolean = false) = Epoch(
        utcMillisOfDay = atMs.toInt(),
        date = null,
        gga = null,
        gst = null,
        rmc = null,
        vtg = null,
        zda = null,
        basePosition = null,
        tilt = tilt,
        gsa = emptyMap(),
        gsv = if (glonass) mapOf(GsvKey(Constellation.GLONASS, 1) to SatellitesInView(6, emptyList())) else emptyMap(),
        completedAtMs = atMs,
    )

    private fun withTilt(state: TiltState, notification: TiltNotification = TiltNotification.NONE) =
        WindowSnapshot((0 until 5).map { epoch(it * 200L, tilt(state, notification)) }, null)

    private fun List<ReceiverCheck>.of(id: CheckId): ReceiverCheck? = firstOrNull { it.id == id }

    private fun rtcm(vararg types: Int, glonassInView: Boolean = false) = WindowSnapshot(
        (0 until 5).map { epoch(it * 200L, glonass = glonassInView) },
        rtcmDelta = types.associateWith { 10L },
    )

    private val ntrip = CheckContext(ntripOpen = true)

    // ----- Inclinación (RF-7) -----

    @Test
    fun `S043 RF-7 sin ninguna ETC no hay comprobacion de inclinacion`() {
        checker.check(WindowSnapshot((0 until 5).map { epoch(it * 200L) }, null)).of(CheckId.TILT).shouldBeNull()
    }

    @Test
    fun `S043 RF-7 compensando, sin aviso y con el modo elegido y comprobado es correcta`() {
        val verified = CheckContext(tiltMode = TiltMode.APP, tiltVerified = true)
        val check = checker.check(withTilt(TiltState.COMPENSATING), verified).of(CheckId.TILT)!!

        check.status shouldBe CheckStatus.OK
        check.measured shouldBe "COMPENSATING"
        check.causes shouldBe emptyList()
    }

    @Test
    fun `S043 RF-7 en preparacion o en alineacion avisa de que aun no compensa`() {
        for (state in listOf(TiltState.SETUP, TiltState.ALIGNMENT)) {
            val check = checker.check(withTilt(state), CheckContext(tiltMode = TiltMode.APP, tiltVerified = true)).of(CheckId.TILT)!!

            check.status shouldBe CheckStatus.WARNING
            check.causes shouldBe listOf(CheckCause.TILT_NOT_COMPENSATING)
        }
    }

    @Test
    fun `S043 RF-7 un fallo fatal o la inclinacion apagada fallan`() {
        for (state in listOf(TiltState.FATAL, TiltState.OFF)) {
            val verified = CheckContext(tiltMode = TiltMode.APP, tiltVerified = true)
            checker.check(withTilt(state), verified).of(CheckId.TILT)!!.status shouldBe CheckStatus.FAIL
        }
    }

    @Test
    fun `S043 RF-7 un aviso del ETC es una causa aunque este compensando`() {
        val check = checker.check(
            withTilt(TiltState.COMPENSATING, TiltNotification.FAST_MOTION),
            CheckContext(tiltMode = TiltMode.APP, tiltVerified = true),
        ).of(CheckId.TILT)!!

        check.status shouldBe CheckStatus.WARNING
        check.causes shouldBe listOf(CheckCause.TILT_NOTICE)
    }

    @Test
    fun `S043 RF-7 sin el modo elegido o sin comprobar avisa, y con modo no se dice que falta`() {
        val unset = checker.check(withTilt(TiltState.COMPENSATING), CheckContext()).of(CheckId.TILT)!!
        val unverified = checker.check(withTilt(TiltState.COMPENSATING), CheckContext(tiltMode = TiltMode.RECEIVER))
            .of(CheckId.TILT)!!

        unset.causes shouldBe listOf(CheckCause.TILT_MODE_UNSET)
        unverified.causes shouldBe listOf(CheckCause.TILT_MODE_UNVERIFIED)
        unset.status shouldBe CheckStatus.WARNING
    }

    // ----- RTCM (RF-8) -----

    @Test
    fun `S043 RF-8 sin NTRIP no hay comprobaciones de RTCM`() {
        val checks = checker.check(rtcm(1006, 1074))

        listOf(CheckId.RTCM_BASE, CheckId.RTCM_OBSERVATIONS, CheckId.RTCM_GLONASS, CheckId.DATUM).forEach { checks.of(it).shouldBeNull() }
    }

    @Test
    fun `S043 RF-8 con NTRIP pero sin dos fotos todavia las RTCM salen Sin datos`() {
        val checks = checker.check(WindowSnapshot((0 until 5).map { epoch(it * 200L) }, null), ntrip)

        checks.of(CheckId.RTCM_BASE)!!.status shouldBe CheckStatus.NO_DATA
        checks.of(CheckId.RTCM_OBSERVATIONS)!!.status shouldBe CheckStatus.NO_DATA
    }

    @Test
    fun `S043 RF-8 un 1006 y observaciones MSM 7 son correctos y dicen que tipos llegan`() {
        val checks = checker.check(rtcm(1006, 1077, 1127), ntrip)

        checks.of(CheckId.RTCM_BASE)!!.status shouldBe CheckStatus.OK
        checks.of(CheckId.RTCM_BASE)!!.measured shouldBe "1006"
        checks.of(CheckId.RTCM_OBSERVATIONS)!!.status shouldBe CheckStatus.OK
        checks.of(CheckId.RTCM_OBSERVATIONS)!!.measured shouldBe "1077, 1127"
    }

    @Test
    fun `S043 RF-8 las correcciones sin 1005 ni 1006 fallan con su causa`() {
        val check = checker.check(rtcm(1077, 1087), ntrip).of(CheckId.RTCM_BASE)!!

        check.status shouldBe CheckStatus.FAIL
        check.causes shouldBe listOf(CheckCause.NO_BASE_MESSAGES)
    }

    @Test
    fun `S043 RF-8 los MSM 1 a 3 no cuentan como observaciones y los antiguos 1004 y 1012 si`() {
        checker.check(rtcm(1005, 1071, 1073), ntrip).of(CheckId.RTCM_OBSERVATIONS)!!.status shouldBe CheckStatus.FAIL
        checker.check(rtcm(1005, 1004), ntrip).of(CheckId.RTCM_OBSERVATIONS)!!.status shouldBe CheckStatus.OK
        checker.check(rtcm(1005, 1012), ntrip).of(CheckId.RTCM_OBSERVATIONS)!!.status shouldBe CheckStatus.OK
        checker.check(rtcm(1005, 1074), ntrip).of(CheckId.RTCM_OBSERVATIONS)!!.causes shouldBe emptyList()
    }

    @Test
    fun `S043 RF-8 sin satelites GLONASS en vista no se comprueba GLONASS`() {
        checker.check(rtcm(1005, 1074, glonassInView = false), ntrip).of(CheckId.RTCM_GLONASS).shouldBeNull()
    }

    @Test
    fun `S043 RF-8 con GLONASS en vista y sin sus observaciones avisa, y con ellas es correcto`() {
        val without = checker.check(rtcm(1005, 1074, glonassInView = true), ntrip).of(CheckId.RTCM_GLONASS)!!
        val with = checker.check(rtcm(1005, 1074, 1084, glonassInView = true), ntrip).of(CheckId.RTCM_GLONASS)!!
        val legacy = checker.check(rtcm(1005, 1004, 1012, glonassInView = true), ntrip).of(CheckId.RTCM_GLONASS)!!

        without.status shouldBe CheckStatus.WARNING
        without.causes shouldBe listOf(CheckCause.NO_GLONASS)
        with.status shouldBe CheckStatus.OK
        legacy.status shouldBe CheckStatus.OK
    }

    // ----- Datum (RF-8, plan D4) -----

    private fun datum(year: Int?, project: CorrectionsDatum?) =
        checker.check(rtcm(1006, 1074), CheckContext(ntripOpen = true, projectDatum = project, itrfYear = year)).of(CheckId.DATUM)

    @Test
    fun `S043 RF-8 con anio ITRF y un proyecto ETRS89 o REGCAN95 avisa de que no coinciden`() {
        for (declared in listOf(CorrectionsDatum.ETRS89, CorrectionsDatum.REGCAN95)) {
            val check = datum(14, declared)!!

            check.status shouldBe CheckStatus.WARNING
            check.causes shouldBe listOf(CheckCause.DATUM_MISMATCH)
            check.measured shouldBe declared.name
        }
    }

    @Test
    fun `S043 RF-8 con anio ITRF y un proyecto ITRF o WGS84 es correcto`() {
        datum(20, CorrectionsDatum.ITRF_WGS84)!!.status shouldBe CheckStatus.OK
    }

    @Test
    fun `S043 RF-8 sin anio ITRF no se puede comprobar y se recuerda el datum declarado`() {
        val check = datum(0, CorrectionsDatum.ETRS89)!!

        check.status shouldBe CheckStatus.WARNING
        check.causes shouldBe listOf(CheckCause.DATUM_UNKNOWN)
        check.measured shouldBe "ETRS89"
    }

    @Test
    fun `S043 RF-8 sin 1005 ni 1006 todavia o sin proyecto no hay comprobacion del datum`() {
        datum(null, CorrectionsDatum.ETRS89).shouldBeNull()
        datum(14, null).shouldBeNull()
    }

    @Test
    fun `S043 RF-9 cada fallo de RTCM trae el paso que lo resuelve`() {
        val withSteps = ReceiverChecker { id -> "paso_${id.name.lowercase()}" }

        val checks = withSteps.check(rtcm(1077), ntrip)

        checks.of(CheckId.RTCM_BASE)!!.stepId shouldBe "paso_rtcm_base"
        checks.of(CheckId.RTCM_OBSERVATIONS)!!.stepId.shouldBeNull() // correcta: sin paso
    }

    // ----- Con sesiones grabadas (RNF-T12) -----

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
        ?: error("No se encuentra la raíz del repositorio")

    private fun recorded(name: String): List<Epoch> {
        val assembler = EpochAssembler()
        val epochs = mutableListOf<Epoch>()
        for (sentence in NmeaFramer().feed(File(root, "fixtures/nmea/sintetico/$name.nmea").readBytes())) {
            assembler.feed(sentence, 0)?.let { epochs += it.copy(completedAtMs = it.utcMillisOfDay.toLong()) }
        }
        return epochs
    }

    private fun window(epochs: List<Epoch>) = WindowSnapshot(epochs, null)

    @Test
    fun `S043 RNF-T12 en la sesion con inclinacion la ETC pasa de preparacion a alineacion y a compensar`() {
        val epochs = recorded("rs3-inclinacion")
        val context = CheckContext(tiltMode = TiltMode.APP, tiltVerified = true)

        val setup = checker.check(window(epochs.take(8)), context).of(CheckId.TILT)!!
        val alignment = checker.check(window(epochs.subList(12, 18)), context).of(CheckId.TILT)!!
        val calm = epochs.indexOfFirst { it.tilt?.state == TiltState.COMPENSATING && it.tilt?.notification == TiltNotification.NONE }
        val compensating = checker.check(window(listOf(epochs[calm])), context).of(CheckId.TILT)!!

        setup.measured shouldBe "SETUP"
        setup.status shouldBe CheckStatus.WARNING
        alignment.measured shouldBe "ALIGNMENT"
        alignment.status shouldBe CheckStatus.WARNING
        compensating.measured shouldBe "COMPENSATING"
        compensating.status shouldBe CheckStatus.OK
    }

    @Test
    fun `S043 RNF-T12 en la sesion con inclinacion el aviso de movimiento rapido sale como causa`() {
        val epochs = recorded("rs3-inclinacion")
        val first = epochs.indexOfFirst { it.tilt?.notification == TiltNotification.FAST_MOTION }
        (first >= 0) shouldBe true // la grabación trae el aviso

        val context = CheckContext(tiltMode = TiltMode.APP, tiltVerified = true)
        val check = checker.check(window(epochs.subList(first, first + 3)), context).of(CheckId.TILT)!!

        check.causes shouldBe listOf(CheckCause.TILT_NOTICE)
    }
}
