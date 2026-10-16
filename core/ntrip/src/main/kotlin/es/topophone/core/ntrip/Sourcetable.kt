package es.topophone.core.ntrip

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Punto de montaje de la tabla de fuentes (línea `STR`), con lo que se muestra al elegirlo (S007/RF-15).
 *
 * @property latitude y [longitude] en grados, o `null` si la tabla no los da (o da 0,0, como las redes).
 * @property needsGga si el caster necesita la posición del receptor (campo `nmea` = 1), o `null` si no lo dice.
 */
data class SourcetableStream(
    val mountpoint: String,
    val identifier: String,
    val format: String,
    val formatDetails: String,
    val carrier: String,
    val navSystems: String,
    val network: String,
    val country: String,
    val latitude: Double?,
    val longitude: Double?,
    val needsGga: Boolean?,
)

/** Lee la tabla de fuentes NTRIP (S007/RF-14): solo las líneas `STR` con punto de montaje, hasta `ENDSOURCETABLE`. */
object SourcetableParser {
    private const val MOUNTPOINT = 1
    private const val IDENTIFIER = 2
    private const val FORMAT = 3
    private const val FORMAT_DETAILS = 4
    private const val CARRIER = 5
    private const val NAV_SYSTEMS = 6
    private const val NETWORK = 7
    private const val COUNTRY = 8
    private const val LATITUDE = 9
    private const val LONGITUDE = 10
    private const val NMEA = 11
    private const val NEWLINE = '\n'.code.toByte()

    fun parse(text: String): List<SourcetableStream> = parse(text.lineSequence())

    /**
     * Líneas de la respuesta del caster. Cada una se lee en UTF-8 y, si no lo es, en ISO-8859-1: hay
     * tablas que mezclan las dos (en la del IGN, "Sierra Espuña" va en ISO-8859-1 y "Gomezserracín" en UTF-8).
     */
    fun lines(bytes: ByteArray): List<String> {
        val lines = ArrayList<String>()
        var start = 0
        for (i in 0..bytes.size) {
            val end = i == bytes.size
            if (end || bytes[i] == NEWLINE) {
                if (!end || i > start) lines += decode(bytes, start, i).trimEnd('\r')
                start = i + 1
            }
        }
        return lines
    }

    private fun decode(bytes: ByteArray, from: Int, to: Int): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, from, to - from))
            .toString()
    } catch (_: CharacterCodingException) {
        String(bytes, from, to - from, Charsets.ISO_8859_1)
    }

    fun parse(lines: Sequence<String>): List<SourcetableStream> = lines
        .map { it.trimEnd('\r') }
        .takeWhile { !it.startsWith("ENDSOURCETABLE") }
        .filter { it.startsWith("STR;") }
        .mapNotNull(::stream)
        .toList()

    private fun stream(line: String): SourcetableStream? {
        val fields = line.split(';')
        fun field(i: Int) = fields.getOrNull(i)?.trim().orEmpty()
        val mountpoint = field(MOUNTPOINT)
        if (mountpoint.isEmpty()) return null
        val latitude = field(LATITUDE).toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
        val longitude = field(LONGITUDE).toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
        // 0,0 es "sin posición": las soluciones de red no tienen una estación concreta.
        val located = latitude != null && longitude != null && !(latitude == 0.0 && longitude == 0.0)
        return SourcetableStream(
            mountpoint = mountpoint,
            identifier = field(IDENTIFIER),
            format = field(FORMAT),
            formatDetails = field(FORMAT_DETAILS),
            carrier = field(CARRIER),
            navSystems = field(NAV_SYSTEMS),
            network = field(NETWORK),
            country = field(COUNTRY),
            latitude = latitude.takeIf { located },
            longitude = longitude.takeIf { located },
            needsGga = when (field(NMEA)) {
                "1" -> true
                "0" -> false
                else -> null
            },
        )
    }
}

/** Punto de montaje con su distancia al receptor en km, o `null` si no se puede calcular (S007/RF-15). */
data class RankedMountpoint(val stream: SourcetableStream, val distanceKm: Double?)

/** Orden por distancia y búsqueda de puntos de montaje (S007/RF-16 a RF-18). */
object Mountpoints {
    private const val EARTH_RADIUS_KM = 6371.0

    /** Distancia del semiverseno sobre la esfera: de sobra para ordenar (error < 0,5 %). */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = phi2 - phi1
        val dLambda = Math.toRadians(lon2 - lon1)
        val h = sin(dPhi / 2).let { it * it } + cos(phi1) * cos(phi2) * sin(dLambda / 2).let { it * it }
        return 2 * EARTH_RADIUS_KM * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /**
     * Con posición del receptor, de más cercano a más lejano y los que no tienen coordenadas al final
     * (S007/RF-16); sin posición, por nombre y sin distancias (S007/RF-17).
     */
    fun sort(streams: List<SourcetableStream>, latitude: Double?, longitude: Double?): List<RankedMountpoint> {
        val byName = compareBy<RankedMountpoint> { it.stream.mountpoint.lowercase(Locale.ROOT) }.thenBy { it.stream.mountpoint }
        if (latitude == null || longitude == null) return streams.map { RankedMountpoint(it, null) }.sortedWith(byName)
        return streams
            .map { stream ->
                val lat = stream.latitude
                val lon = stream.longitude
                RankedMountpoint(stream, if (lat != null && lon != null) distanceKm(latitude, longitude, lat, lon) else null)
            }
            .sortedWith(compareBy<RankedMountpoint, Double?>(nullsLast()) { it.distanceKm }.then(byName))
    }

    /** Los que contienen [text] en el nombre o el identificador, sin distinguir mayúsculas (S007/RF-18). */
    fun filter(mountpoints: List<RankedMountpoint>, text: String): List<RankedMountpoint> {
        val query = text.trim()
        if (query.isEmpty()) return mountpoints
        return mountpoints.filter {
            it.stream.mountpoint.contains(query, ignoreCase = true) || it.stream.identifier.contains(query, ignoreCase = true)
        }
    }
}
