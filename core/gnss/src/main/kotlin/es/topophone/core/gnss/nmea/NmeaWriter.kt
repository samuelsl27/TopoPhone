package es.topophone.core.gnss.nmea

import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Escribe frases NMEA con suma de control, en el mismo formato que el RS3, para las fuentes que genera la app: el receptor
 * virtual y el GNSS del móvil (S022/RF-15, plan D4 y D8). Sin `android.*`. Todos los números salen con [Locale.ROOT] y un número
 * fijo de decimales, de modo que los mismos datos dan siempre los mismos bytes. Cada frase termina en `\r\n`.
 */
object NmeaWriter {

    private const val MILLIS_PER_SECOND = 1000
    private const val SECONDS_PER_MINUTE = 60
    private const val MINUTES_PER_HOUR = 60
    private const val CENTI = 10
    private const val CENTISECONDS_PER_SECOND = 100
    private const val MINUTE_DECIMALS = 10_000_000L
    private const val SATELLITES_PER_GSV = 4
    private const val MAX_GSA_SATELLITES = 12
    private const val HEX = 16

    /** Cierra el [body] (lo que va entre `$` y `*`) con la suma de control y el fin de línea. */
    fun line(body: String): String = "$" + body + "*" + "%02X".format(Locale.ROOT, NmeaChecksum.compute(body)) + "\r\n"

    /** `hhmmss.ss` desde los milisegundos desde las 00:00 UTC. */
    fun time(utcMillisOfDay: Int): String {
        val hours = utcMillisOfDay / (MILLIS_PER_SECOND * SECONDS_PER_MINUTE * MINUTES_PER_HOUR)
        val minutes = utcMillisOfDay / (MILLIS_PER_SECOND * SECONDS_PER_MINUTE) % MINUTES_PER_HOUR
        val seconds = utcMillisOfDay / MILLIS_PER_SECOND % SECONDS_PER_MINUTE
        val centis = utcMillisOfDay % MILLIS_PER_SECOND / CENTI
        return "%02d%02d%02d.%02d".format(Locale.ROOT, hours, minutes, seconds, centis % CENTISECONDS_PER_SECOND)
    }

    /** `ddmm.mmmmmmm` (o `dddmm...` con 3 dígitos de grados) y el hemisferio; el redondeo no deja minutos en 60. */
    private fun coordinate(degrees: Double, degreeDigits: Int, positive: String, negative: String): String {
        val units = (abs(degrees) * MINUTES_PER_HOUR * MINUTE_DECIMALS).roundToLong()
        val whole = units / (MINUTES_PER_HOUR * MINUTE_DECIMALS)
        val minutes = units % (MINUTES_PER_HOUR * MINUTE_DECIMALS)
        val text = "%0${degreeDigits}d%02d.%07d".format(
            Locale.ROOT,
            whole,
            minutes / MINUTE_DECIMALS,
            minutes % MINUTE_DECIMALS,
        )
        return text + "," + if (degrees < 0) negative else positive
    }

    private fun latitude(degrees: Double) = coordinate(degrees, 2, "N", "S")

    private fun longitude(degrees: Double) = coordinate(degrees, 3, "E", "W")

    private fun number(value: Double?, decimals: Int): String = if (value == null) "" else "%.${decimals}f".format(Locale.ROOT, value)

    private fun quality(status: SolutionStatus): Int = when (status) {
        SolutionStatus.NONE -> 0
        SolutionStatus.SINGLE -> 1
        SolutionStatus.DGPS -> 2
        SolutionStatus.FIX -> 4
        SolutionStatus.FLOAT -> 5
        SolutionStatus.ESTIMATED -> 6
    }

    /** GGA. Sin solución ([SolutionStatus.NONE]) la posición va vacía. */
    @Suppress("LongParameterList")
    fun gga(
        utcMillisOfDay: Int,
        latitudeDeg: Double?,
        longitudeDeg: Double?,
        status: SolutionStatus,
        satellites: Int,
        hdop: Double?,
        altitudeM: Double?,
        geoidSeparationM: Double?,
        correctionAgeS: Double?,
        stationId: Int = 0,
        talker: String = "GN",
    ): String {
        val position = if (status == SolutionStatus.NONE || latitudeDeg == null || longitudeDeg == null) {
            ",,,"
        } else {
            latitude(latitudeDeg) + "," + longitude(longitudeDeg)
        }
        val counts = "%02d".format(Locale.ROOT, satellites)
        val station = "%04d".format(Locale.ROOT, stationId)
        return line(
            "${talker}GGA,${time(utcMillisOfDay)},$position,${quality(status)},$counts,${number(hdop, 1)},${number(altitudeM, 3)},M," +
                "${number(geoidSeparationM, 3)},M,${number(correctionAgeS, 1)},$station",
        )
    }

    /** GST: precisión 1σ. Los cuatro primeros campos (elipse de error) van vacíos si no se dan. */
    @Suppress("LongParameterList")
    fun gst(
        utcMillisOfDay: Int,
        sigmaLatitudeM: Double?,
        sigmaLongitudeM: Double?,
        sigmaAltitudeM: Double?,
        rmsM: Double? = null,
        semiMajorM: Double? = null,
        semiMinorM: Double? = null,
        orientationDeg: Double? = null,
        talker: String = "GN",
    ): String = line(
        "${talker}GST,${time(utcMillisOfDay)},${number(rmsM, 3)},${number(semiMajorM, 3)},${number(semiMinorM, 3)}," +
            "${number(orientationDeg, 1)},${number(sigmaLatitudeM, 3)},${number(sigmaLongitudeM, 3)},${number(sigmaAltitudeM, 3)}",
    )

    /** GSA: hasta 12 satélites usados de una constelación (`systemId` de NMEA 4.10, 1 GPS a 6 NavIC) y los DOP. */
    @Suppress("LongParameterList")
    fun gsa(
        talker: String,
        mode: FixMode,
        systemId: Int?,
        satellitesUsed: List<Int>,
        pdop: Double?,
        hdop: Double?,
        vdop: Double?,
    ): String {
        val used = satellitesUsed.take(MAX_GSA_SATELLITES).map { "%02d".format(Locale.ROOT, it) }
        val slots = (used + List(MAX_GSA_SATELLITES - used.size) { "" }).joinToString(",")
        val modeCode = when (mode) {
            FixMode.NONE -> 1
            FixMode.FIX_2D -> 2
            FixMode.FIX_3D -> 3
        }
        return line("${talker}GSA,A,$modeCode,$slots,${number(pdop, 1)},${number(hdop, 1)},${number(vdop, 1)},${systemId ?: ""}")
    }

    /** GSV de una constelación y una señal: las frases necesarias, a cuatro satélites por frase. */
    fun gsv(talker: String, satellites: List<SatelliteView>, signalId: Int): List<String> {
        val signal = signalId.toString(HEX).uppercase(Locale.ROOT)
        if (satellites.isEmpty()) return listOf(line("${talker}GSV,1,1,00,$signal"))
        val groups = satellites.chunked(SATELLITES_PER_GSV)
        val inView = "%02d".format(Locale.ROOT, satellites.size)
        return groups.mapIndexed { index, group ->
            val fields = group.joinToString(",") {
                val elevation = it.elevationRad?.let { rad -> "%02d".format(Locale.ROOT, Math.toDegrees(rad).roundToInt()) }.orEmpty()
                val azimuth = it.azimuthRad?.let { rad -> "%03d".format(Locale.ROOT, Math.toDegrees(rad).roundToInt()) }.orEmpty()
                val snr = it.snrDbHz?.let { value -> "%02d".format(Locale.ROOT, value) }.orEmpty()
                "%02d,$elevation,$azimuth,$snr".format(Locale.ROOT, it.prn)
            }
            line("${talker}GSV,${groups.size},${index + 1},$inView,$fields,$signal")
        }
    }

    /** ZDA: fecha y hora UTC. */
    fun zda(utcMillisOfDay: Int, date: LocalDate, talker: String = "GN"): String = line(
        "${talker}ZDA,${time(utcMillisOfDay)},%02d,%02d,%04d,00,00".format(Locale.ROOT, date.dayOfMonth, date.monthValue, date.year),
    )

    /** EBP de Emlid: posición de la base (grados y metros sobre el elipsoide). */
    fun ebp(base: BasePosition, talker: String = "GN"): String =
        line("${talker}EBP,${latitude(base.latitudeDeg)},${longitude(base.longitudeDeg)},${number(base.ellipsoidalHeightM, 3)},M")

    /** ETC_rev_3: estado y aviso de la inclinación, rumbo, dirección, inclinación (en radianes) y coeficientes de precisión. */
    fun etc(tilt: TiltData, utcMillisOfDay: Int = tilt.utcMillisOfDay ?: 0, talker: String = "GN"): String {
        val state = when (tilt.state) {
            TiltState.OFF -> ""
            TiltState.FATAL -> "0"
            TiltState.SETUP -> "10"
            TiltState.ALIGNMENT -> "20"
            TiltState.COMPENSATING -> "30"
        }
        val notification = when (tilt.notification) {
            TiltNotification.OFF -> ""
            TiltNotification.NONE -> "0"
            TiltNotification.FAST_MOTION -> "10"
            TiltNotification.BAD_GNSS -> "20"
            TiltNotification.FILTER_FAULT -> "30"
        }
        val coefficients = tilt.accuracyCoefficients
        return line(
            "${talker}ETC,${time(utcMillisOfDay)},$state,$notification,${degrees(tilt.headingRad, 2)}," +
                "${degrees(tilt.tiltDirectionRad, 3)},${degrees(tilt.tiltRad, 3)},${number(coefficients?.north, 3)}," +
                "${number(coefficients?.east, 3)},${number(coefficients?.up, 3)}",
        )
    }

    private fun degrees(radians: Double?, decimals: Int) = number(radians?.let(Math::toDegrees), decimals)
}
