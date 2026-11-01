package es.topophone.core.survey

/** El orden de recorrido de un replanteo por código: todos los códigos de cada PK, o todos los PK de cada código (S050/RF-16). */
enum class SectionStationOrder { BY_CHAINAGE, BY_CODE }

/** Un objetivo de un replanteo por código: un PK y un código de sección. */
data class SectionStationItem(val chainageM: Double, val code: String)

/**
 * Los pares (PK, código) de un replanteo por código (S050/RF-16, RF-17; plan A4). Los PK son los de [AxisChainageSeries] (inicio,
 * intervalo, final y puntos singulares: los del eje de S054 y los de la sección y el peralte, que el llamador junta en [keyStations]).
 * No sabe de secciones: un par cuyo código no existe en ese PK lo detecta [SectionStakeTarget] y quien recorre la serie lo salta.
 *
 * @param codes los códigos a replantear, en el orden elegido; sin ellos la serie está vacía.
 * @param keyStations los PK singulares si está marcada la casilla; null si no.
 */
class SectionStationSeries(
    private val order: SectionStationOrder,
    private val codes: List<String>,
    startM: Double,
    endM: Double?,
    intervalM: Double?,
    keyStations: List<Double>?,
    axisEndM: Double,
) {
    private val chainages = AxisChainageSeries(startM, endM, intervalM, keyStations, axisEndM)

    /** Cuántos pares tiene la serie. */
    val size: Int = (chainages.size.toLong() * codes.size).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** El par [index], de 0 a [size] - 1. */
    fun itemAt(index: Int): SectionStationItem {
        require(index in 0 until size)
        return when (order) {
            SectionStationOrder.BY_CHAINAGE -> SectionStationItem(chainages.chainageOf(index / codes.size), codes[index % codes.size])
            SectionStationOrder.BY_CODE -> SectionStationItem(chainages.chainageOf(index % chainages.size), codes[index / chainages.size])
        }
    }

    /** La posición del par siguiente: no pasa del último. */
    fun next(index: Int): Int = minOf(index + 1, size - 1)

    /** La posición del par anterior: no pasa del primero. */
    fun previous(index: Int): Int = maxOf(index - 1, 0)
}
