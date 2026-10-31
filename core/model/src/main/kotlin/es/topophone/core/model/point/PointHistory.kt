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

/** Qué pasó con una foto de un punto ya guardado (S017b/RF-23). */
enum class PhotoAction { ADDED, DELETED }

/** Un suceso de foto: no es una versión del punto y no lo marca como editado (S011/RF-12). */
data class PhotoEvent(val atMs: Long, val action: PhotoAction, val photoName: String, val photoTakenAtMs: Long)

/** Una línea del historial completo: una versión del punto o un suceso de foto (S017b/RF-23). */
sealed interface HistoryRecord {
    val atMs: Long

    data class Version(val item: HistoryItem) : HistoryRecord {
        override val atMs: Long get() = item.changedAtMs
    }

    data class Photo(val event: PhotoEvent) : HistoryRecord {
        override val atMs: Long get() = event.atMs
    }

    companion object {
        /** Versiones y sucesos de foto juntos, del más reciente al más antiguo; a igual fecha, la versión primero. */
        fun timeline(items: List<HistoryItem>, events: List<PhotoEvent>): List<HistoryRecord> =
            (items.map(::Version) + events.map(::Photo)).sortedByDescending { it.atMs }
    }
}
