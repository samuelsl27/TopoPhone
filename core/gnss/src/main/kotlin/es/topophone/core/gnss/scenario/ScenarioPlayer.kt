package es.topophone.core.gnss.scenario

import es.topophone.core.gnss.nmea.BasePosition
import es.topophone.core.gnss.nmea.FixMode
import es.topophone.core.gnss.nmea.NmeaWriter
import es.topophone.core.gnss.nmea.SolutionStatus
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Genera las épocas de un [Scenario] como líneas NMEA (S022/RF-14, RF-15, plan D4 y A1). La época del segundo `n` depende solo de
 * (escenario, origen, hora de inicio, n): sin azar, dos ejecuciones dan los mismos bytes. La hora de inicio se trunca al segundo.
 * Las frases salen en el orden del RS3: GGA, GST, GSA y GSV de cada constelación, ZDA y, si el escenario las pide, EBP y ETC.
 */
class ScenarioPlayer(val scenario: Scenario, val origin: ScenarioOrigin, startUtcMillis: Long) {

    private val startMs = Math.floorDiv(startUtcMillis, MILLIS_PER_SECOND) * MILLIS_PER_SECOND

    val durationS: Int get() = scenario.durationS

    /** Hora UTC, en milisegundos desde 1970, de la época del segundo [second]. */
    fun utcMillis(second: Int): Long = startMs + second * MILLIS_PER_SECOND

    /** Las líneas NMEA de la época del segundo [second]; vacía si el escenario no emite nada en ese segundo. */
    fun epoch(second: Int): List<String> {
        val state = scenario.at(second)
        if (!state.emitting) return emptyList()
        val utc = utcMillis(second)
        val millisOfDay = Math.floorMod(utc, MILLIS_PER_DAY).toInt()
        val date = Instant.ofEpochMilli(utc).atZone(ZoneOffset.UTC).toLocalDate()
        val position = if (state.solution == SolutionStatus.NONE) null else offset(state.eastM, state.northM)
        val sky = Sky.visible(state.satellites)

        val lines = mutableListOf<String>()
        lines += NmeaWriter.gga(
            utcMillisOfDay = millisOfDay,
            latitudeDeg = position?.first,
            longitudeDeg = position?.second,
            status = state.solution,
            satellites = state.satellites,
            hdop = state.hdop,
            altitudeM = if (position == null) null else origin.ellipsoidalHeightM + state.upM - GEOID_SEPARATION_M,
            geoidSeparationM = if (position == null) null else GEOID_SEPARATION_M,
            correctionAgeS = state.correctionAgeS,
        )
        lines += NmeaWriter.gst(millisOfDay, state.sigmaLatitudeM, state.sigmaLongitudeM, state.sigmaAltitudeM)
        val mode = if (state.solution == SolutionStatus.NONE) FixMode.NONE else FixMode.FIX_3D
        val pdop = state.hdop * PDOP_FACTOR
        val vdop = state.hdop * VDOP_FACTOR
        // Por constelación, en el orden en que aparece en el cielo (GPS, GLONASS, Galileo, BeiDou).
        val byConstellation = sky.groupBy({ it.first.talker to it.first.systemId }, { it.second })
        for ((constellation, views) in byConstellation) {
            lines += NmeaWriter.gsa(constellation.first, mode, constellation.second, views.map { it.prn }, pdop, state.hdop, vdop)
        }
        for ((constellation, views) in byConstellation) lines += NmeaWriter.gsv(constellation.first, views, GSV_SIGNAL_L1)
        lines += NmeaWriter.zda(millisOfDay, date)
        state.base?.let { base ->
            val basePosition = offset(base.eastM, base.northM)
            lines += NmeaWriter.ebp(BasePosition(basePosition.first, basePosition.second, origin.ellipsoidalHeightM + base.upM))
        }
        state.tilt?.let { lines += NmeaWriter.etc(it, millisOfDay) }
        return lines
    }

    /** La época del segundo [second] como bytes ASCII, lista para escribir en un flujo. */
    fun epochBytes(second: Int): ByteArray = epoch(second).joinToString("").toByteArray(Charsets.US_ASCII)

    /**
     * Latitud y longitud, en grados, de un punto a [eastM] metros al este y [northM] al norte del origen. Aproximación local con
     * los radios de curvatura del elipsoide WGS84 en la latitud del origen: a 100 m el error es menor que 1 mm (plan D5).
     */
    private fun offset(eastM: Double, northM: Double): Pair<Double, Double> {
        val phi = Math.toRadians(origin.latitudeDeg)
        val w = sqrt(1.0 - WGS84_E2 * sin(phi) * sin(phi))
        val meridianRadius = WGS84_A * (1.0 - WGS84_E2) / (w * w * w)
        val primeVerticalRadius = WGS84_A / w
        val latitude = origin.latitudeDeg + Math.toDegrees(northM / meridianRadius)
        val longitude = origin.longitudeDeg + Math.toDegrees(eastM / (primeVerticalRadius * cos(phi)))
        return latitude to longitude
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
        const val MILLIS_PER_DAY = 86_400_000L
        const val WGS84_A = 6_378_137.0
        const val WGS84_E2 = 0.00669437999014

        /** Separación del geoide que lleva la GGA: la altitud es sobre el geoide y la altura elipsoidal es altitud más esto. */
        const val GEOID_SEPARATION_M = 50.0
        const val PDOP_FACTOR = 1.5
        const val VDOP_FACTOR = 1.2
        const val GSV_SIGNAL_L1 = 1
    }
}
