package es.topophone.core.gnss.nmea

/** Velocidad de la reproducción (S003/RF-36). */
enum class ReplaySpeed(val factor: Int) {
    X1(1),
    X5(5),
}

/** Líneas de una grabación, cada una con su fin de línea; se pide de nuevo al volver a empezar. */
fun interface ReplaySource {
    fun open(): Iterator<ByteArray>
}

/**
 * Entrega las líneas de una grabación al ritmo de la hora UTC de sus épocas (S003/RF-36 a RF-39).
 *
 * El ritmo es acumulado desde un ancla (sin deriva aunque el sistema se retrase al dormir), un
 * cambio de velocidad se aplica desde la época siguiente, los huecos de más de [maxGapMs] se
 * acortan, una hora que retrocede no hace esperar y, con el bucle activado, al terminar se llama a
 * [onRestart] y se vuelve a empezar. [next] bloquea con [sleep]: se usa desde un hilo de E/S.
 */
class ReplayPacer(
    private val source: ReplaySource,
    private val clock: () -> Long,
    private val sleep: (Long) -> Unit,
    private val speed: () -> ReplaySpeed,
    private val loop: () -> Boolean,
    private val onRestart: () -> Unit,
    private val maxGapMs: Long = MAX_GAP_MS,
) {
    private var lines = source.open()
    private var lastTime: Int? = null
    private var fileMs = 0L
    private var anchorRealMs = 0L
    private var anchorFileMs = 0L
    private var factor = 0

    /** La siguiente línea cuando le toca; `null` al terminar sin bucle (S003/RF-40). */
    fun next(): ByteArray? {
        if (!lines.hasNext()) {
            if (!loop()) return null
            onRestart() // S003/RF-39
            lines = source.open()
            lastTime = null
            if (!lines.hasNext()) return null
        }
        val line = lines.next()
        epochTime(line)?.let(::pace)
        return line
    }

    private fun pace(time: Int) {
        val previous = lastTime
        if (time == previous) return
        if (previous == null) { // primera época, o vuelta al principio del bucle
            lastTime = time
            anchorRealMs = clock()
            anchorFileMs = fileMs
            factor = speed().factor
            return
        }
        var delta = (time - previous).toLong()
        if (delta < 0) delta += NmeaTime.MILLIS_PER_DAY // medianoche
        if (delta >= NmeaTime.MILLIS_PER_DAY / 2) return // la hora retrocede: ni se espera ni cuenta
        lastTime = time
        fileMs += minOf(delta, maxGapMs) // S003/RF-38
        val due = anchorRealMs + (fileMs - anchorFileMs) / factor
        val wait = due - clock()
        if (wait > 0) sleep(wait)
        val requested = speed().factor
        if (requested != factor) { // S003/RF-37: la nueva velocidad, desde la época siguiente
            anchorRealMs = maxOf(due, clock())
            anchorFileMs = fileMs
            factor = requested
        }
    }

    /** Hora de la línea si es una frase válida con hora (GGA, GST, RMC, ZDA o ETC). */
    private fun epochTime(line: ByteArray): Int? {
        val text = String(line, Charsets.US_ASCII).trimEnd('\r', '\n')
        val start = text.lastIndexOf('$')
        if (start < 0) return null
        val sentence = text.substring(start)
        if (!NmeaChecksum.isValid(sentence)) return null
        val fields = sentence.substring(1, sentence.lastIndexOf('*')).split(',')
        val address = fields.first()
        if (address.length != ADDRESS_LENGTH || address.substring(2) !in TIMED_TYPES) return null
        return NmeaTime.parse(fields.getOrElse(1) { "" })
    }

    companion object {
        /** Los huecos de más de 10 s se reproducen como de 10 s (S003/RF-38). */
        const val MAX_GAP_MS = 10_000L
        private const val ADDRESS_LENGTH = 5
        private val TIMED_TYPES = setOf("GGA", "GST", "RMC", "ZDA", "ETC")
    }
}
