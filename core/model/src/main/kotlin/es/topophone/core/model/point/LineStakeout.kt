package es.topophone.core.model.point

import es.topophone.core.model.cad.CadEntityKind

/**
 * Cómo se replantea una línea de un dibujo (S041/RF-3): los vértices uno a uno, la línea más cercana, la línea o su paralela
 * a lo largo, un tramo entre dos PK o una serie de puntos a intervalos.
 */
enum class LineStakeoutMode(val fixedPoint: Boolean = false) {
    BY_SEGMENTS(fixedPoint = true),
    TO_LINE,
    ALONG,
    SEGMENT_AB,
    INTERVALS(fixedPoint = true),
    ;

    companion object {
        /** El modo de la primera vez (S041/RF-2). */
        val DEFAULT = TO_LINE

        /** El modo guardado con ese nombre, o [DEFAULT] si no hay o no es de este programa. */
        fun fromName(name: String?): LineStakeoutMode = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** De dónde viene el punto de diseño que se replantea (S041/RF-28). */
sealed interface StakeSource {
    /** Un punto del proyecto (S018). */
    data object ProjectPoint : StakeSource

    /** Un elemento de un dibujo: se guardan sus nombres, no sus identificadores, para que sobrevivan al borrado del dibujo (RF-32). */
    data class CadElement(val drawingName: String, val layerName: String, val kind: CadEntityKind) : StakeSource
}

/** Por qué no se aplica un valor escrito en el replanteo de una línea (S041/RF-21). */
enum class LineInputError { NOT_A_NUMBER, OUT_OF_LINE, SAME_PK, INTERVAL_NOT_POSITIVE }
