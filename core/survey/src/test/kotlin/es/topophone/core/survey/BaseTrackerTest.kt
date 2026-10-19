package es.topophone.core.survey

import es.topophone.core.geodesy.Ecef
import es.topophone.core.gnss.nmea.BasePosition
import es.topophone.core.gnss.nmea.Constellation
import es.topophone.core.gnss.rtcm.MsmCell
import es.topophone.core.gnss.rtcm.MsmCnr
import es.topophone.core.gnss.rtcm.ReferenceStation
import es.topophone.core.gnss.rtcm.RtcmSystem
import es.topophone.core.gnss.signal.Band
import es.topophone.core.gnss.signal.SatelliteId
import es.topophone.core.gnss.signal.SignalKey
import es.topophone.core.model.point.BaseSource
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** La base en uso: EBP o 1005/1006, caducidad, SNR de la base y cambios de base (S015/RF-10 a RF-12, RF-16, RF-17). */
class BaseTrackerTest {

    /** La estación 6 (Avilés) del 1006 real de RGAPA y lo que da pyproj de sus geocéntricas. */
    private val avl13m = ReferenceStation(6, 4_604_889.1789, -476_930.7239, 4_372_677.1220, 0.0)
    private val madrid = BasePosition(40.4166667, -3.7, 700.0)

    /** La misma base [metres] al norte (en latitud, sin cambiar la altura). */
    private fun BasePosition.north(metres: Double) = copy(latitudeDeg = latitudeDeg + metres / 111_000.0)

    private val tracker = BaseTracker()

    @Test
    fun `S015 RF-11 la EBP del receptor da la base`() {
        tracker.onEbp(madrid, nowMs = 1_000).shouldBeTrue()

        val base = tracker.state.position.shouldNotBeNull()
        base.source shouldBe BaseSource.RECEIVER
        base.latitudeDeg shouldBe 40.4166667
        base.ellipsoidalHeightM shouldBe 700.0
        base.ecef shouldBe Ecef.fromGeodetic(40.4166667, -3.7, 700.0)
        base.stationId.shouldBeNull()
        tracker.state.serial shouldBe 1
        tracker.state.change.shouldBeNull()
    }

    @Test
    fun `S015 RF-12 sin EBP la base sale del 1006, en geograficas como pyproj`() {
        tracker.onReferenceStation(avl13m, nowMs = 1_000)

        val base = tracker.state.position.shouldNotBeNull()
        base.source shouldBe BaseSource.CORRECTIONS
        base.stationId shouldBe 6
        base.antennaHeightM shouldBe 0.0
        base.latitudeDeg shouldBe (43.5578666032 plusOrMinus 1e-9)
        base.longitudeDeg shouldBe (-5.9130704113 plusOrMinus 1e-9)
        base.ellipsoidalHeightM shouldBe (78.4338 plusOrMinus 0.001)
    }

    @Test
    fun `S015 RF-11 con las dos fuentes manda la EBP, y la del 1006 si la EBP caduca`() {
        tracker.onReferenceStation(avl13m, nowMs = 0)
        tracker.onEbp(madrid, nowMs = 1_000)

        tracker.state.position?.source shouldBe BaseSource.RECEIVER

        tracker.onReferenceStation(avl13m, nowMs = 50_000)
        tracker.tick(nowMs = 61_001).shouldBeTrue()

        tracker.state.position?.source shouldBe BaseSource.CORRECTIONS
    }

    @Test
    fun `S015 RF-16 sin EBP ni 1005 1006 en 60 s no hay base`() {
        tracker.onEbp(madrid, nowMs = 0)

        tracker.tick(nowMs = 60_000).shouldBeFalse()
        tracker.state.position.shouldNotBeNull()
        tracker.tick(nowMs = 60_001).shouldBeTrue()
        tracker.state.position.shouldBeNull()
    }

    @Test
    fun `S015 RF-17 la base que se aleja mas de 0,1 m de la ultima de su fuente cambia`() {
        tracker.onEbp(madrid, nowMs = 0)
        tracker.onEbp(madrid.north(0.05), nowMs = 1_000)

        tracker.state.change.shouldBeNull()

        tracker.onEbp(madrid.north(0.6), nowMs = 2_000)

        val change = tracker.state.change.shouldNotBeNull()
        change.distanceM shouldBe (0.55 plusOrMinus 0.01)
        change.count shouldBe 1
        tracker.state.serial shouldBe 2
    }

    @Test
    fun `S015 RF-17 pasar de una fuente a otra no es un cambio aunque las posiciones difieran`() {
        tracker.onEbp(madrid, nowMs = 0)
        tracker.onReferenceStation(avl13m, nowMs = 1_000)
        tracker.tick(nowMs = 60_500)

        tracker.state.position?.source shouldBe BaseSource.CORRECTIONS
        tracker.state.change.shouldBeNull()
        tracker.state.serial shouldBe 1
    }

    @Test
    fun `S015 RF-17 la base que vuelve en otro sitio se compara con la ultima desde que se conecto`() {
        tracker.onEbp(madrid, nowMs = 0)
        tracker.tick(nowMs = 70_000)
        tracker.state.position.shouldBeNull()

        tracker.onEbp(madrid, nowMs = 80_000)
        tracker.state.change.shouldBeNull()
        tracker.onEbp(madrid.north(1_000.0), nowMs = 81_000)

        tracker.state.change?.distanceM shouldBe (1_000.0 plusOrMinus 1.0)
    }

    @Test
    fun `S015 RF-17 una conexion nueva con el receptor olvida la base anterior`() {
        tracker.onEbp(madrid, nowMs = 0)

        tracker.reset()
        tracker.onEbp(madrid.north(1_000.0), nowMs = 1_000)

        tracker.state.change.shouldBeNull()
        tracker.state.serial shouldBe 1
    }

    @Test
    fun `S015 RF-10 la SNR de la base por satelite y banda caduca a los 10 s`() {
        val gps = MsmCnr(RtcmSystem.GPS, 6, listOf(MsmCell(5, 2, 35.0), MsmCell(5, 31, 38.0), MsmCell(5, 10, 34.0), MsmCell(16, 1, 50.0)))
        val glonass = MsmCnr(RtcmSystem.GLONASS, 6, listOf(MsmCell(7, 2, 40.0)))

        tracker.onMsm(gps, nowMs = 0)
        tracker.onMsm(glonass, nowMs = 5_000)

        tracker.state.snrAvailable.shouldBeTrue()
        tracker.state.snr shouldBe mapOf(
            // L1 C/A y L1C son la misma banda: se queda la mayor. La señal 1 no tiene banda y se ignora.
            SignalKey(SatelliteId(Constellation.GPS, 5), Band.L1) to 38.0,
            SignalKey(SatelliteId(Constellation.GPS, 5), Band.L2) to 34.0,
            SignalKey(SatelliteId(Constellation.GLONASS, 7), Band.G1) to 40.0,
        )

        tracker.tick(nowMs = 10_001).shouldBeTrue()
        tracker.state.snr.keys shouldBe setOf(SignalKey(SatelliteId(Constellation.GLONASS, 7), Band.G1))

        tracker.tick(nowMs = 15_001).shouldBeTrue()
        tracker.state.snrAvailable.shouldBeFalse()
        tracker.state.snr shouldBe emptyMap()
    }

    @Test
    fun `S015 RF-10 los MSM de SBAS no dan SNR de la base`() {
        tracker.onMsm(MsmCnr(RtcmSystem.SBAS, 6, listOf(MsmCell(1, 2, 40.0))), nowMs = 0)

        tracker.state.snr shouldBe emptyMap()
        tracker.state.snrAvailable.shouldBeFalse()
    }
}
