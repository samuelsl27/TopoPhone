package es.topophone.core.model.point

import es.topophone.core.model.codes.CodeKey

/**
 * El filtro por código de la lista de objetos (S024/RF-18): [key] es la clave del código (sin mayúsculas ni acentos), [NONE] los puntos
 * sin código, y null, todos.
 */
object PointCodeFilter {
    /** Los puntos sin código. */
    const val NONE = ""

    fun matches(summary: PointSummary, key: String?): Boolean = when (key) {
        null -> true
        NONE -> summary.code == null
        else -> summary.code != null && CodeKey.of(summary.code) == key
    }

    /** Los códigos que llevan los [points], por orden alfabético de su clave, cada uno con el texto del primero que lo usa. */
    fun codesOf(points: List<PointSummary>): List<String> =
        points.mapNotNull { it.code }.distinctBy { CodeKey.of(it) }.sortedBy { CodeKey.of(it) }
}
