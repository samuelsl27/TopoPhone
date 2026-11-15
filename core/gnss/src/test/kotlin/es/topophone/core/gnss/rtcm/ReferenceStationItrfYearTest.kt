package es.topophone.core.gnss.rtcm

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** El año de realización ITRF de los mensajes 1005 y 1006, para saber si las correcciones declaran su datum (S043/RF-8, plan D4). */
class ReferenceStationItrfYearTest {

    /** Un 1005 o un 1006 montado campo a campo (RTCM 10403.3, DF002 a DF028) con su año ITRF (DF021). */
    private fun message(type: Int, itrfYear: Int) = RtcmBitWriter()
        .unsigned(type, 12)
        .unsigned(6, 12)
        .unsigned(itrfYear.toLong(), 6)
        .unsigned(0b1110, 4)
        .signed(4_604_889_1789L, 38)
        .unsigned(0, 1)
        .unsigned(0, 1)
        .signed(-476_930_7239L, 38)
        .unsigned(0, 2)
        .signed(4_372_677_1220L, 38)
        .apply { if (type == 1006) unsigned(0, 16) }
        .bytes()

    @Test
    fun `S043 RF-8 un 1005 con el anio ITRF 14 lo devuelve`() {
        ReferenceStationDecoder.decode(message(1005, 14)).shouldNotBeNull().itrfYear shouldBe 14
    }

    @Test
    fun `S043 RF-8 un 1006 con el anio ITRF 20 lo devuelve`() {
        ReferenceStationDecoder.decode(message(1006, 20)).shouldNotBeNull().itrfYear shouldBe 20
    }

    @Test
    fun `S043 RF-8 con el anio 0 las correcciones no declaran su datum`() {
        ReferenceStationDecoder.decode(message(1005, 0)).shouldNotBeNull().itrfYear shouldBe 0
        ReferenceStationDecoder.decode(message(1006, 0)).shouldNotBeNull().itrfYear shouldBe 0
    }

    @Test
    fun `S043 RF-8 el valor mas alto de los seis bits se lee entero`() {
        ReferenceStationDecoder.decode(message(1006, 63)).shouldNotBeNull().itrfYear shouldBe 63
    }

    @Test
    fun `S043 RF-8 leer el anio no cambia la posicion ni el identificador`() {
        val station = ReferenceStationDecoder.decode(message(1006, 14)).shouldNotBeNull()

        station.stationId shouldBe 6
        station.x shouldBe (4_604_889.1789 plusOrMinus 1e-6)
        station.y shouldBe (-476_930.7239 plusOrMinus 1e-6)
        station.z shouldBe (4_372_677.1220 plusOrMinus 1e-6)
    }

    @Test
    fun `S043 RF-8 el 1006 real de la estacion 6 no declara el anio ITRF`() {
        RtcmFixtures.payloadsOf(1006).map { ReferenceStationDecoder.decode(it).shouldNotBeNull().itrfYear }.toSet() shouldBe setOf(0)
    }
}
