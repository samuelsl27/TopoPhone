package es.topophone.core.model.offline

/** El estado de una fuente dentro de una zona (S037/RF-15, RF-18, RF-19). */
enum class SourceState { PENDING, DOWNLOADING, PAUSED, COMPLETE, INCOMPLETE }

/** El estado de una zona (S037/RF-24): «Completa» o «Incompleta». «Actualizar» no cambia el estado de la zona (RF-26). */
enum class ZoneState {
    COMPLETE,
    INCOMPLETE,
    ;

    companion object {
        /** Completa solo si todas sus fuentes lo están (y tiene alguna). */
        fun of(sources: List<SourceState>): ZoneState =
            if (sources.isNotEmpty() && sources.all { it == SourceState.COMPLETE }) COMPLETE else INCOMPLETE
    }
}
