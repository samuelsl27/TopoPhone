package es.topophone.core.model.line

import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.point.NaturalOrder

/** Las operaciones del asistente «Línea desde puntos» sobre la lista de puntos elegidos, en el orden en que se tocan (S025/RF-12, RF-13). */
object LineWizard {
    /** Tocar un punto lo añade al final; tocar uno ya elegido lo quita (RF-12, RF-13). */
    fun toggle(ids: List<String>, id: String): List<String> = if (id in ids) ids.filter { it != id } else ids + id

    /** Los puntos elegidos ordenados por nombre, con los números en su sitio (1, 2, 10) y no por letras (RF-12). */
    fun sortedByName(ids: List<String>, names: Map<String, String>): List<String> =
        ids.sortedWith(compareBy(NaturalOrder) { names[it] ?: it })

    /** Invierte el orden (RF-13). */
    fun reversed(ids: List<String>): List<String> = ids.asReversed()

    /** Si la línea sale cerrada de entrada: un código de polígono la propone cerrada, y el usuario puede cambiarlo (RF-13). */
    fun defaultClosed(geometry: CodeGeometry?): Boolean = geometry == CodeGeometry.POLYGON

    /** Por qué no se puede crear con estos puntos; `null` si se puede (RF-15). */
    fun issue(ids: List<String>, closed: Boolean): LineIssue? = LineRules.validate(ids, closed)
}
