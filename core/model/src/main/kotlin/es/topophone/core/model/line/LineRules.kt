package es.topophone.core.model.line

import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeKey

/** Por qué no se puede guardar, cerrar o crear una línea (S025/RF-11, RF-15, RF-19). */
enum class LineIssue { TOO_FEW_VERTICES, REPEATED_VERTEX }

/** Las reglas de las líneas del proyecto (S025). */
object LineRules {
    const val MIN_OPEN = 2
    const val MIN_POLYGON = 3

    /** El mínimo de vértices de una línea abierta (2) o de un polígono (3) (RF-15, RF-19). */
    fun minVertices(closed: Boolean): Int = if (closed) MIN_POLYGON else MIN_OPEN

    /** Si una línea con [count] vértices se puede cerrar en polígono (RF-10, RF-11). */
    fun canClose(count: Int): Boolean = count >= MIN_POLYGON

    /**
     * Comprueba la lista de puntos de una línea: el mínimo de vértices y que no haya dos iguales seguidos; en un
     * polígono el último y el primero también son seguidos (RF-15, RF-19). `null` si es válida.
     */
    fun validate(pointIds: List<String>, closed: Boolean): LineIssue? {
        if (pointIds.size < minVertices(closed)) return LineIssue.TOO_FEW_VERTICES
        val repeated = pointIds.zipWithNext().any { (a, b) -> a == b } ||
            (closed && pointIds.size > 1 && pointIds.first() == pointIds.last())
        return if (repeated) LineIssue.REPEATED_VERTEX else null
    }

    /** Cerrar la línea o dejarla como está según el código al terminarla: solo un código de polígono con ≥ 3 vértices cierra (RF-4). */
    fun closedOnFinish(geometry: CodeGeometry, vertexCount: Int, alreadyClosed: Boolean): Boolean =
        alreadyClosed || (geometry == CodeGeometry.POLYGON && canClose(vertexCount))

    /** Invierte el sentido de la línea (RF-16). */
    fun reversed(line: ProjectLine): ProjectLine = line.copy(vertices = line.vertices.asReversed())
}

/** El nombre que propone el sistema para una línea nueva (S025/RF-2, RF-14, plan D5). */
object LineNaming {
    /**
     * `CÓDIGO_n`, con n el mayor número usado con ese prefijo en [names] más 1 (1 si no hay ninguno); el prefijo se
     * compara sin distinguir mayúsculas ni acentos. Sin código, el prefijo es [fallback].
     */
    fun next(code: String?, names: Collection<String>, fallback: String = "LINE"): String {
        val prefix = code?.takeIf { it.isNotBlank() } ?: fallback
        val key = CodeKey.of("${prefix}_")
        val highest = names.maxOfOrNull { name ->
            val k = CodeKey.of(name)
            if (k.startsWith(key)) k.removePrefix(key).toIntOrNull()?.takeIf { it > 0 } ?: 0 else 0
        } ?: 0
        return "${prefix}_${highest + 1}"
    }
}
