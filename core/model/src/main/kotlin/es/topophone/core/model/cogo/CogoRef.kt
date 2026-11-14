package es.topophone.core.model.cogo

/** Lo que calcula COGO y se puede guardar como puntos del proyecto (S038/RF-22). La inversa no guarda nada (S038/RF-9). */
enum class CogoOperation {
    TRAVERSE,
    INTERSECTION_DISTANCE_DISTANCE,
    INTERSECTION_DIRECTION_DIRECTION,
    INTERSECTION_DISTANCE_DIRECTION,
}

/**
 * Un punto de entrada del cálculo, copiado por valor al guardar (S038/RF-22): borrar el punto del proyecto después no cambia el punto
 * COGO. [role] dice qué papel hacía (`start`, `reference`, `closure`, `first`, `second`); [name] es null si se escribió a mano. Este,
 * Norte y altura en el SC del proyecto, en metros y sin redondear.
 */
data class CogoEntry(val role: String, val name: String?, val easting: Double, val northing: Double, val height: Double?)

/**
 * Cómo se da la dirección de un tramo de una poligonal (S038/RF-10): un azimut; el ángulo a la derecha o a la izquierda que forman la
 * visual atrás y el tramo; o la deflexión a la derecha o a la izquierda respecto a la prolongación de la visual atrás.
 */
enum class LegKind { AZIMUTH, ANGLE_RIGHT, ANGLE_LEFT, DEFLECTION_RIGHT, DEFLECTION_LEFT }

/**
 * Un tramo de una poligonal tal como se escribió, en SI (S038/RF-10): el azimut, el ángulo o la deflexión en radianes ([angleRad]), la
 * distancia en metros ([distanceM], de terreno o de cuadrícula según la elección del momento) y el desnivel opcional ([dHeightM]).
 * Un valor es null si aún no se ha escrito o no es un número (S038/RF-26).
 */
data class TraverseLeg(val kind: LegKind, val angleRad: Double?, val distanceM: Double?, val dHeightM: Double?) {
    /** Tiene la dirección y la distancia: el desnivel es opcional. */
    val isComplete: Boolean get() = angleRad != null && distanceM != null
}

/** Un valor escrito de una intersección: su clave (`distance1`, `distance2`, `azimuth1`, `azimuth2`) y su valor en SI. */
data class CogoValue(val key: String, val value: Double)

/** Qué solución de una intersección se guardó (S038/RF-14, RF-16): a la izquierda o a la derecha, la primera o la segunda, o la única. */
enum class IntersectionSide { LEFT, RIGHT, FIRST, SECOND, SINGLE }

/**
 * El rastro de un punto COGO (S038/RF-22): la operación, los puntos de entrada por valor, los tramos de la poligonal hasta ese
 * punto o los valores escritos de la intersección, y la solución elegida.
 */
data class CogoRef(
    val operation: CogoOperation,
    val entries: List<CogoEntry>,
    val legs: List<TraverseLeg> = emptyList(),
    val values: List<CogoValue> = emptyList(),
    val solution: IntersectionSide? = null,
) {
    /** El valor escrito de [key], o null si la operación no lo tiene. */
    fun value(key: String): Double? = values.firstOrNull { it.key == key }?.value
}
