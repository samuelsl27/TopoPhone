package es.topophone.core.gnss.scenario

import es.topophone.core.gnss.nmea.SatelliteView

/** Un satélite del cielo fijo del receptor virtual: su constelación por la frase (`talker` y `systemId` de la GSA) y su PRN. */
internal data class SkySatellite(val talker: String, val systemId: Int, val prn: Int)

/**
 * El cielo del receptor virtual: 28 satélites de cuatro constelaciones con elevación, azimut y relación señal/ruido fijos, que
 * salen de su posición en la lista (sin azar). Con `n` satélites se toman los `n` primeros de una lista que reparte las
 * constelaciones por turnos, así que con pocos satélites hay de varias constelaciones (S022/RF-15).
 */
internal object Sky {
    private const val ELEVATION_BASE_DEG = 12
    private const val ELEVATION_RANGE_DEG = 70
    private const val ELEVATION_STEP = 37
    private const val AZIMUTH_STEP = 47
    private const val FULL_CIRCLE_DEG = 360
    private const val SNR_BASE = 30
    private const val SNR_RANGE = 18
    private const val SNR_STEP = 7

    private val gps = listOf(5, 12, 13, 15, 18, 20, 24, 25, 29, 31).map { SkySatellite("GP", 1, it) }
    private val glonass = listOf(70, 71, 72, 73, 79, 80, 81).map { SkySatellite("GL", 2, it) }
    private val galileo = listOf(2, 7, 11, 19, 26, 30, 33).map { SkySatellite("GA", 3, it) }
    private val beidou = listOf(6, 9, 16, 21).map { SkySatellite("GB", 4, it) }

    /** Las constelaciones por turnos: GPS, GLONASS, Galileo, BeiDou, GPS, GLONASS... */
    private val order: List<SkySatellite> = buildList {
        val groups = listOf(gps, glonass, galileo, beidou)
        for (round in 0 until groups.maxOf { it.size }) groups.forEach { group -> group.getOrNull(round)?.let(::add) }
    }

    val size: Int get() = order.size

    /** Los [count] primeros satélites con su elevación, azimut y relación señal/ruido, en el orden de la lista. */
    fun visible(count: Int): List<Pair<SkySatellite, SatelliteView>> {
        val taken = order.take(count.coerceIn(0, order.size))
        return taken.map { satellite ->
            val index = order.indexOf(satellite)
            val elevation = ELEVATION_BASE_DEG + index * ELEVATION_STEP % ELEVATION_RANGE_DEG
            val azimuth = index * AZIMUTH_STEP % FULL_CIRCLE_DEG
            val snr = SNR_BASE + index * SNR_STEP % SNR_RANGE
            satellite to SatelliteView(satellite.prn, Math.toRadians(elevation.toDouble()), Math.toRadians(azimuth.toDouble()), snr)
        }
    }
}
