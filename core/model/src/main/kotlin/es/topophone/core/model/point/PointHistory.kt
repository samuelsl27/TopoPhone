package es.topophone.core.model.point

/** Una cosa que cambió en una versión del punto (S011/RF-11). */
sealed interface HistoryLine {
    data class Name(val from: String, val to: String) : HistoryLine

    data class Description(val from: String, val to: String) : HistoryLine

    /** La altura del jalón, en metros. */
    data class Pole(val fromM: Double?, val toM: Double?) : HistoryLine

    /** Las coordenadas se escribieron a mano. */
    data object Coordinates : HistoryLine

    /** Las coordenadas volvieron a las de la medida. */
    data object Reverted : HistoryLine
}

/** Un cambio del historial con su fecha y lo que cambió. */
data class HistoryItem(val changedAtMs: Long, val change: PointChange, val lines: List<HistoryLine>)

/** El historial de un punto en palabras: cada versión anterior frente a la que vino después (S011/RF-11). */
object PointHistory {
    /**
     * [entries] van del cambio más reciente al más antiguo, como los da el repositorio; el más reciente se compara
     * con el punto [current].
     */
    fun items(entries: List<PointHistoryEntry>, current: Point): List<HistoryItem> {
        var after = PointSnapshot.of(current)
        return entries.map { entry ->
            val before = entry.previous
            val lines = buildList {
                if (before.name != after.name) add(HistoryLine.Name(before.name, after.name))
                if (before.description != after.description) add(HistoryLine.Description(before.description, after.description))
                if (before.poleHeightM != after.poleHeightM) add(HistoryLine.Pole(before.poleHeightM, after.poleHeightM))
                when {
                    entry.change == PointChange.REVERT -> add(HistoryLine.Reverted)
                    entry.change == PointChange.EDIT && before.coordinates != after.coordinates -> add(HistoryLine.Coordinates)
                }
            }
            after = before
            HistoryItem(entry.changedAtMs, entry.change, lines)
        }
    }
}
