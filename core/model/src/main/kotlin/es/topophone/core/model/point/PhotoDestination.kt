package es.topophone.core.model.point

/** A qué punto irá la foto que se haga desde el recolector (S017b/RF-2, RF-5 a RF-8). */
sealed interface PhotoDestination {
    /** El punto que se está midiendo; las fotos quedan en espera hasta que se guarde (RF-2). */
    data object Measuring : PhotoDestination

    /** Un punto ya guardado: el último que guardó el recolector (RF-5, RF-6). */
    data class Saved(val pointId: String) : PhotoDestination

    /** No hay punto al que añadir la foto (RF-7). */
    data object None : PhotoDestination

    companion object {
        /**
         * Con el modo automático en marcha va al último punto guardado aunque haya otro en medida (RF-6); sin modo
         * automático, al punto en medida —también en pausa o esperando confirmar— (RF-2) o, si no hay medida, al último
         * guardado (RF-5). Sin ninguno, [None] (RF-7).
         */
        fun resolve(measuring: Boolean, autoRunning: Boolean, lastSavedPointId: String?): PhotoDestination = when {
            autoRunning -> lastSavedPointId?.let(::Saved) ?: None
            measuring -> Measuring
            else -> lastSavedPointId?.let(::Saved) ?: None
        }
    }
}
