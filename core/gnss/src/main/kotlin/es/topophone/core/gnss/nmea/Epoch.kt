package es.topophone.core.gnss.nmea

import java.time.LocalDate

/** Grupo de frases GSV de una constelación y una señal. */
data class GsvKey(val constellation: Constellation, val signalId: Int?)

/** Satélites en vista de un grupo GSV: el total que declara y los que lista. */
data class SatellitesInView(val count: Int, val satellites: List<SatelliteView>)

/**
 * Frases del receptor con la misma hora UTC (S003/RF-8). De cada tipo queda la última de la época;
 * GSA y GSV, por constelación (y señal). `completedAtMs` es del reloj monótono del móvil.
 */
data class Epoch(
    val utcMillisOfDay: Int,
    val date: LocalDate?,
    val gga: GgaFix?,
    val gst: GstAccuracy?,
    val rmc: RmcData?,
    val vtg: VtgData?,
    val zda: ZdaData?,
    val basePosition: BasePosition?,
    val tilt: TiltData?,
    val gsa: Map<Constellation, GsaData>,
    val gsv: Map<GsvKey, SatellitesInView>,
    val completedAtMs: Long,
) {
    /** Satélites usados según las GSA de la época; `null` si no trae ninguna. */
    val satellitesUsed: Int? get() = if (gsa.isEmpty()) null else gsa.values.sumOf { it.satellitesUsed.size }

    /** Satélites en vista: por constelación, el mayor total de sus señales (S003/RF-15); `null` sin GSV. */
    val satellitesInView: Int?
        get() = if (gsv.isEmpty()) null else gsv.entries.groupBy({ it.key.constellation }, { it.value.count }).values.sumOf { it.max() }
}

/**
 * Agrupa en épocas las frases válidas que llegan del receptor (S003/RF-8 a S003/RF-11).
 *
 * Una época se completa cuando llega una frase con otra hora o cuando pasan [silenceMs] sin
 * frases ([tick]). Las frases sin hora (GSA, GSV, VTG, EBP) van a la época en curso. Una frase
 * con hora igual o anterior a la de la última época completa se descarta, salvo en el paso de
 * medianoche (más de 12 h de diferencia) o tras [reset]. No es seguro entre hilos.
 */
class EpochAssembler(private val silenceMs: Long = DEFAULT_SILENCE_MS) {

    private var current = Builder()
    private var lastCompleted: Int? = null
    private var lastArrivalMs = 0L
    private var date: LocalDate? = null
    private var dateTime = 0

    /** Añade una frase; devuelve la época anterior si esta frase la completa. */
    fun feed(sentence: NmeaSentence, nowMs: Long): Epoch? {
        val parsed = parse(sentence) ?: return null // S003/RF-7
        val time = parsed.time
        var finished: Epoch? = null
        if (time != null) {
            val last = lastCompleted
            if (last != null && time <= last && last - time < HALF_DAY_MS) return null // S003/RF-10
            val open = current.time
            if (open != null && open != time) finished = complete(nowMs) // S003/RF-9
            current.time = time
        }
        current.add(parsed)
        lastArrivalMs = nowMs
        return finished
    }

    /** Completa la época en curso si lleva [silenceMs] sin frases (S003/RF-9). */
    fun tick(nowMs: Long): Epoch? = if (current.time != null && nowMs - lastArrivalMs >= silenceMs) complete(nowMs) else null

    /** Completa ya la época en curso, si tiene hora: al terminar una reproducción o antes de volver a empezar. */
    fun flush(nowMs: Long): Epoch? = if (current.time != null) complete(nowMs) else null

    /** Vuelta al principio de una reproducción: olvida la época en curso, la última hora y la fecha. */
    fun reset() {
        current = Builder()
        lastCompleted = null
        date = null
        dateTime = 0
    }

    private fun complete(nowMs: Long): Epoch {
        val builder = current
        val time = checkNotNull(builder.time)
        val ownDate = builder.zda?.date ?: builder.rmc?.date
        val known = date
        when {
            ownDate != null -> {
                date = ownDate
                dateTime = time
            }
            known != null && time < dateTime -> { // S003/RF-11: medianoche sin RMC ni ZDA todavía
                date = known.plusDays(1)
                dateTime = time
            }
        }
        lastCompleted = time
        current = Builder()
        return builder.build(time, date, nowMs)
    }

    private class Parsed(val time: Int?, val apply: (Builder) -> Unit)

    private fun parse(sentence: NmeaSentence): Parsed? = when (sentence.type) {
        "GGA" -> GgaParser.parse(sentence)?.let { gga -> Parsed(NmeaTime.parse(gga.utcTime)) { it.gga = gga } }
        "GST" -> GstParser.parse(sentence)?.let { gst -> Parsed(NmeaTime.parse(gst.utcTime)) { it.gst = gst } }
        "RMC" -> RmcParser.parse(sentence)?.let { rmc -> Parsed(rmc.utcMillisOfDay) { it.rmc = rmc } }
        "ZDA" -> ZdaParser.parse(sentence)?.let { zda -> Parsed(zda.utcMillisOfDay) { it.zda = zda } }
        "ETC" -> EtcParser.parse(sentence)?.let { tilt -> Parsed(tilt.utcMillisOfDay) { it.tilt = tilt } }
        "VTG" -> VtgParser.parse(sentence)?.let { vtg -> Parsed(null) { it.vtg = vtg } }
        "EBP" -> EbpParser.parse(sentence)?.let { base -> Parsed(null) { it.basePosition = base } }
        "GSA" -> GsaParser.parse(sentence)?.let { gsa -> Parsed(null) { it.gsa[gsa.constellation] = gsa } }
        "GSV" -> GsvParser.parse(sentence)?.let { gsv -> Parsed(null) { it.addGsv(gsv) } }
        else -> null
    }

    private class Builder {
        var time: Int? = null
        var gga: GgaFix? = null
        var gst: GstAccuracy? = null
        var rmc: RmcData? = null
        var vtg: VtgData? = null
        var zda: ZdaData? = null
        var basePosition: BasePosition? = null
        var tilt: TiltData? = null
        val gsa = mutableMapOf<Constellation, GsaData>()
        private val gsv = mutableMapOf<GsvKey, SatellitesInView>()

        fun add(parsed: Parsed) = parsed.apply(this)

        /** La primera frase de un grupo GSV empieza de nuevo la lista de ese grupo. */
        fun addGsv(data: GsvData) {
            val key = GsvKey(data.constellation, data.signalId)
            val previous = gsv[key]?.satellites.takeIf { data.messageNumber > 1 }.orEmpty()
            gsv[key] = SatellitesInView(data.satellitesInView, previous + data.satellites)
        }

        fun build(time: Int, date: LocalDate?, nowMs: Long) =
            Epoch(time, date, gga, gst, rmc, vtg, zda, basePosition, tilt, gsa.toMap(), gsv.toMap(), nowMs)
    }

    companion object {
        const val DEFAULT_SILENCE_MS = 150L
        private const val HALF_DAY_MS = NmeaTime.MILLIS_PER_DAY / 2
    }
}
