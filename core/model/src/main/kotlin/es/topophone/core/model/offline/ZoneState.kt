package es.topophone.core.model.offline

/** El estado de una fuente dentro de una zona (S037/RF-15, RF-18, RF-19). */
enum class SourceState { PENDING, DOWNLOADING, PAUSED, COMPLETE, INCOMPLETE }

/** El estado de una zona (S037/RF-24): «Completa», «Incompleta» y, solo mientras se renueva, «Actualizando» (RF-26). */
enum class ZoneState {
    COMPLETE,
    INCOMPLETE,
    UPDATING,
    ;

    companion object {
        /** Completa solo si todas sus fuentes lo están (y tiene alguna); mientras se actualiza, [UPDATING]. */
        fun of(sources: List<SourceState>, updating: Boolean): ZoneState = when {
            updating -> UPDATING
            sources.isNotEmpty() && sources.all { it == SourceState.COMPLETE } -> COMPLETE
            else -> INCOMPLETE
        }
    }
}
