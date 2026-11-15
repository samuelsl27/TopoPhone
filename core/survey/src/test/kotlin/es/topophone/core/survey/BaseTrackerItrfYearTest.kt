package es.topophone.core.survey

import es.topophone.core.gnss.nmea.BasePosition
import es.topophone.core.gnss.rtcm.ReferenceStation
import es.topophone.core.model.point.BaseSource
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** La base de las correcciones recuerda el año ITRF que declara su 1005 o 1006, para la comprobación del datum (S043/RF-8, plan D4). */
class BaseTrackerItrfYearTest {

    private val tracker = BaseTracker()

    @Test
    fun `S043 RF-8 la base de un 1006 con anio ITRF lo guarda`() {
        tracker.onReferenceStation(ReferenceStation(6, 4_604_889.1789, -476_930.7239, 4_372_677.1220, 0.0, itrfYear = 14), nowMs = 1_000)

        val base = tracker.state.position.shouldNotBeNull()
        base.source shouldBe BaseSource.CORRECTIONS
        base.itrfYear shouldBe 14
    }

    @Test
    fun `S043 RF-8 un 1005 sin anio ITRF da la base con el anio 0`() {
        tracker.onReferenceStation(ReferenceStation(6, 4_604_889.1789, -476_930.7239, 4_372_677.1220, null), nowMs = 1_000)

        tracker.state.position.shouldNotBeNull().itrfYear shouldBe 0
    }

    @Test
    fun `S043 RF-8 la base que da el receptor por EBP no declara ningun anio`() {
        tracker.onEbp(BasePosition(40.4166667, -3.7, 700.0), nowMs = 1_000)

        tracker.state.position.shouldNotBeNull().itrfYear.shouldBeNull()
    }
}
