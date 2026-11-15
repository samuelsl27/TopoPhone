package es.topophone.core.model.point

/**
 * De dónde viene la posición con la que se midió un punto (S022/RF-11, RF-19): un receptor, una reproducción de sesión, el GNSS
 * del móvil, el GNSS del móvil con una ubicación simulada por otra app (RF-7) o el receptor virtual.
 */
enum class PositionSource {
    RECEIVER,
    REPLAY,
    PHONE,
    PHONE_MOCK,
    VIRTUAL,
    ;

    /** Posición que no viene de un receptor real: es lo que marca la columna «Replay» de la exportación (S022 plan D3). */
    val simulated: Boolean get() = this == REPLAY || this == VIRTUAL || this == PHONE_MOCK

    companion object {
        /**
         * La fuente guardada con el punto. Los puntos de antes de S022 no la llevan: son del receptor o, si `from_replay` era
         * verdadero, de una reproducción. Un valor desconocido cuenta como receptor.
         */
        fun fromStored(name: String?, fromReplay: Boolean?): PositionSource = when {
            name != null -> entries.firstOrNull { it.name == name } ?: RECEIVER
            fromReplay == true -> REPLAY
            else -> RECEIVER
        }
    }
}
