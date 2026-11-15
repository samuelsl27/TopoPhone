package es.topophone.receiver.transport

import es.topophone.core.gnss.nmea.NmeaWriter
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.PositionSource
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Una posición del GNSS del móvil: latitud y longitud en grados, altura elipsoidal (la que da Android, sin geoide), precisiones de
 * Android (68 %, en metros), hora UTC en milisegundos desde 1970 y si Android la marca como simulada por otra app (S022/RF-5 a RF-7).
 */
data class LocationFix(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val ellipsoidalHeightM: Double?,
    val horizontalAccuracyM: Double?,
    val verticalAccuracyM: Double?,
    val timeMillis: Long,
    val mock: Boolean,
    val satellites: Int? = null,
)

/** De dónde salen las posiciones del móvil (S022/RF-5): el sistema Android, o una fuente falsa en los tests. */
interface LocationSource {
    /** Hay permiso de ubicación precisa (S022/RF-2). */
    fun hasPermission(): Boolean

    /** La ubicación del móvil está activada (S022/RF-3). */
    fun isEnabled(): Boolean

    /** Empieza a entregar posiciones a [onFix], una por segundo; hasta [stop]. */
    fun start(onFix: (LocationFix) -> Unit)

    fun stop()
}

/** Falta el permiso de ubicación precisa: no se puede abrir el GNSS del móvil (S022/RF-2). */
class PhonePermissionException : IOException("Sin permiso de ubicación")

/** La ubicación del móvil está desactivada (S022/RF-3). */
class LocationDisabledException : IOException("Ubicación desactivada")

/**
 * El GNSS del móvil como transporte (S022/RF-5, RF-6, RF-8, RF-9, RF-20, plan D8 a D10). Cada posición de Android se escribe como
 * unas frases NMEA que la sesión lee como las de cualquier receptor: GGA con calidad SINGLE y la altura elipsoidal en la altitud con
 * separación del geoide 0, GST con la precisión de Android y ZDA. Nunca sale FIX, diga lo que diga Android. No admite correcciones,
 * no graba sesión y tiene su propio plazo de «Sin datos».
 */
class PhoneGnssTransport(override val description: String, private val source: LocationSource) : ReceiverTransport {

    override val simulated: Boolean = false

    override val acceptsCorrections: Boolean = false

    override val recordsSession: Boolean = false

    override val noDataTimeoutMs: Long = NO_DATA_TIMEOUT_MS

    /** La última posición venía marcada como simulada por otra app (S022/RF-7). */
    @Volatile var mock: Boolean = false
        private set

    override val positionSource: PositionSource get() = if (mock) PositionSource.PHONE_MOCK else PositionSource.PHONE

    private val queue = LinkedBlockingQueue<ByteArray>()

    @Volatile private var closed = false

    override fun open() {
        if (!source.hasPermission()) throw PhonePermissionException()
        if (!source.isEnabled()) throw LocationDisabledException()
        source.start { fix ->
            mock = fix.mock
            queue.offer(sentences(fix).toByteArray(Charsets.US_ASCII))
        }
    }

    override val input: InputStream = object : InputStream() {
        private var pending = ByteArray(0)
        private var position = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and BYTE_MASK
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (position >= pending.size) {
                if (closed) return -1
                pending = queue.poll(POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                position = 0
            }
            val count = minOf(len, pending.size - position)
            System.arraycopy(pending, position, b, off, count)
            position += count
            return count
        }
    }

    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = Unit // sin correcciones: lo que se escriba se descarta
    }

    override fun close() {
        closed = true
        source.stop()
        queue.offer(ByteArray(0)) // despierta una lectura que espera la siguiente posición
    }

    /** Las frases de una posición: GGA (SINGLE), GST y ZDA, con la hora UTC de la posición. */
    private fun sentences(fix: LocationFix): String {
        val utc = Instant.ofEpochMilli(fix.timeMillis).atZone(ZoneOffset.UTC)
        val millisOfDay = Math.floorMod(fix.timeMillis, MILLIS_PER_DAY).toInt()
        // La precisión horizontal de Android es un radio: con σ en cada eje igual a precisión/√2 la horizontal de la app sale igual.
        val sigmaAxis = fix.horizontalAccuracyM?.let { it / sqrt(2.0) }
        return NmeaWriter.gga(
            utcMillisOfDay = millisOfDay,
            latitudeDeg = fix.latitudeDeg,
            longitudeDeg = fix.longitudeDeg,
            status = SolutionStatus.SINGLE, // S022/RF-5: nunca FIX
            satellites = fix.satellites ?: 0,
            hdop = null,
            altitudeM = fix.ellipsoidalHeightM, // S022/RF-6: elipsoidal, sin geoide
            geoidSeparationM = fix.ellipsoidalHeightM?.let { 0.0 },
            correctionAgeS = null,
        ) + NmeaWriter.gst(millisOfDay, sigmaAxis, sigmaAxis, fix.verticalAccuracyM) +
            NmeaWriter.zda(millisOfDay, utc.toLocalDate())
    }

    private companion object {
        const val NO_DATA_TIMEOUT_MS = 3_000L
        const val MILLIS_PER_DAY = 86_400_000L
        const val POLL_MS = 200L
        const val BYTE_MASK = 0xFF
    }
}
