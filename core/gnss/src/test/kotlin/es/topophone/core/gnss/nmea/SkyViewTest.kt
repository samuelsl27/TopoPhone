package es.topophone.core.gnss.nmea

import es.topophone.core.gnss.signal.Band
import es.topophone.core.gnss.signal.SatelliteId
import es.topophone.core.gnss.signal.SignalKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test

/** Lo último de GSA y GSV: DOP, satélites usados y en vista, skyplot y SNR del rover (S015/RF-2, RF-3, RF-6, RF-7). */
class SkyViewTest {

    private fun rad(deg: Double) = Math.toRadians(deg)

    private fun sat(prn: Int, elevation: Double?, azimuth: Double?, snr: Int?) =
        SatelliteView(prn, elevation?.let(::rad), azimuth?.let(::rad), snr)

    private fun epoch(gsa: Map<Constellation, GsaData> = emptyMap(), gsv: Map<GsvKey, SatellitesInView> = emptyMap(), atMs: Long = 1_000) =
        Epoch(36_000_000, null, null, null, null, null, null, null, null, gsa, gsv, atMs)

    private val gsa = mapOf(
        Constellation.GPS to GsaData(FixMode.FIX_3D, Constellation.GPS, listOf(5, 12), 1.2, 0.5, 1.0),
        Constellation.GLONASS to GsaData(FixMode.FIX_3D, Constellation.GLONASS, listOf(70), 1.2, 0.5, 1.0),
    )
    private val gsv = mapOf(
        GsvKey(Constellation.GPS, 1) to SatellitesInView(
            10,
            listOf(sat(5, 40.0, 185.0, 35), sat(12, 15.0, 84.0, 42), sat(13, 33.0, 121.0, null), sat(24, null, null, 28)),
        ),
        GsvKey(Constellation.GPS, 8) to SatellitesInView(6, listOf(sat(5, 40.0, 185.0, 31))),
        GsvKey(Constellation.GLONASS, 1) to SatellitesInView(7, listOf(sat(70, 55.0, 300.0, 47), sat(71, 20.0, 10.0, 39))),
        GsvKey(Constellation.GALILEO, null) to SatellitesInView(1, listOf(sat(2, 60.0, 90.0, 44))),
    )

    @Test
    fun `S015 RF-2 los DOP salen de la GSA`() {
        SkyView.next(null, epoch(gsa, gsv))?.dop shouldBe Dop(1.2, 0.5, 1.0)
    }

    @Test
    fun `S015 RF-3 usados y en vista por constelacion, contados como en S003 RF-15`() {
        val sky = SkyView.next(null, epoch(gsa, gsv))!!

        sky.counts shouldBe mapOf(
            Constellation.GPS to SatelliteCount(used = 2, inView = 10),
            Constellation.GLONASS to SatelliteCount(used = 1, inView = 7),
            Constellation.GALILEO to SatelliteCount(used = 0, inView = 1),
        )
    }

    @Test
    fun `S015 RF-6 el skyplot lleva un punto por satelite con elevacion y azimut, y si se usa`() {
        val sky = SkyView.next(null, epoch(gsa, gsv))!!

        sky.satellites shouldBe listOf(
            SkySatellite(SatelliteId(Constellation.GPS, 5), rad(40.0), rad(185.0), used = true),
            SkySatellite(SatelliteId(Constellation.GPS, 12), rad(15.0), rad(84.0), used = true),
            SkySatellite(SatelliteId(Constellation.GPS, 13), rad(33.0), rad(121.0), used = false),
            SkySatellite(SatelliteId(Constellation.GLONASS, 6), rad(55.0), rad(300.0), used = true),
            SkySatellite(SatelliteId(Constellation.GLONASS, 7), rad(20.0), rad(10.0), used = false),
            SkySatellite(SatelliteId(Constellation.GALILEO, 2), rad(60.0), rad(90.0), used = false),
        )
    }

    @Test
    fun `S015 RF-7 la SNR del rover por satelite y banda, sin banda si no se indica y vacia si no se sigue`() {
        val sky = SkyView.next(null, epoch(gsa, gsv))!!

        sky.roverSnr shouldBe mapOf(
            SignalKey(SatelliteId(Constellation.GPS, 5), Band.L1) to 35,
            SignalKey(SatelliteId(Constellation.GPS, 12), Band.L1) to 42,
            SignalKey(SatelliteId(Constellation.GPS, 13), Band.L1) to null,
            SignalKey(SatelliteId(Constellation.GPS, 24), Band.L1) to 28,
            SignalKey(SatelliteId(Constellation.GPS, 5), Band.L5) to 31,
            SignalKey(SatelliteId(Constellation.GLONASS, 6), Band.G1) to 47,
            SignalKey(SatelliteId(Constellation.GLONASS, 7), Band.G1) to 39,
            SignalKey(SatelliteId(Constellation.GALILEO, 2), null) to 44,
        )
    }

    @Test
    fun `S015 RF-2 las epocas sin GSA ni GSV conservan la vista anterior`() {
        val sky = SkyView.next(null, epoch(gsa, gsv))

        SkyView.next(sky, epoch(atMs = 1_200)) shouldBeSameInstanceAs sky
        SkyView.next(null, epoch()).shouldBeNull()
    }

    @Test
    fun `S015 RF-3 una epoca solo con GSA cambia los DOP y los usados y conserva los satelites en vista`() {
        val sky = SkyView.next(null, epoch(gsa, gsv))!!
        val newGsa = mapOf(Constellation.GPS to GsaData(FixMode.FIX_3D, Constellation.GPS, listOf(5), 2.0, 1.1, 1.7))

        val next = SkyView.next(sky, epoch(newGsa, atMs = 2_000))!!

        next.dop shouldBe Dop(2.0, 1.1, 1.7)
        next.used shouldBe mapOf(Constellation.GPS to setOf(5))
        next.inView shouldBe sky.inView
        next.updatedAtMs shouldBe 2_000
    }
}
