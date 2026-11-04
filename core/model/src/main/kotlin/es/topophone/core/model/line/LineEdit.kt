package es.topophone.core.model.line

import kotlin.math.hypot

/**
 * Las operaciones de edición de los vértices de una línea, sobre la lista de identificadores de punto (S025/RF-16).
 * Ninguna toca los puntos (RF-17); el resultado se valida con [LineRules.validate] antes de guardarlo (RF-19).
 */
object LineEdit {
    /** Añade [pointId] al final. */
    fun append(ids: List<String>, pointId: String): List<String> = ids + pointId

    /** Inserta [pointId] antes de la posición [index] (de 0 a `ids.size`); entre dos vértices o al final. */
    fun insert(ids: List<String>, index: Int, pointId: String): List<String> =
        ids.toMutableList().also { it.add(index.coerceIn(0, ids.size), pointId) }

    /** Quita el vértice en [index]; si no existe, la lista no cambia. */
    fun remove(ids: List<String>, index: Int): List<String> = if (index in ids.indices) ids.filterIndexed { i, _ -> i != index } else ids

    /** Cambia el punto del vértice en [index] por [pointId]. */
    fun replace(ids: List<String>, index: Int, pointId: String): List<String> =
        if (index in ids.indices) ids.mapIndexed { i, id -> if (i == index) pointId else id } else ids

    /** Mueve el vértice de la posición [from] a la posición [to] de la lista resultante. */
    fun move(ids: List<String>, from: Int, to: Int): List<String> {
        if (from !in ids.indices || to !in ids.indices || from == to) return ids
        return ids.toMutableList().also { it.add(to, it.removeAt(from)) }
    }

    /** Invierte el sentido de la línea. */
    fun reverse(ids: List<String>): List<String> = ids.asReversed()
}

/** El PK de cada vértice de una línea, partiendo del primero (S025/RF-16; el PK de S041). */
object LineChainage {
    /**
     * Distancias horizontales acumuladas, en metros, desde el primer vértice hasta cada uno, con las coordenadas
     * planas del SC del proyecto ([eastNorth] en orden de vértice). El primero vale 0.
     */
    fun of(eastNorth: List<Pair<Double, Double>>): List<Double> {
        var total = 0.0
        return eastNorth.mapIndexed { i, (e, n) ->
            if (i > 0) total += hypot(e - eastNorth[i - 1].first, n - eastNorth[i - 1].second)
            total
        }
    }

    /** La longitud horizontal de la línea; un polígono suma además el lado que une el último vértice con el primero. */
    fun length(eastNorth: List<Pair<Double, Double>>, closed: Boolean): Double {
        val open = of(eastNorth).lastOrNull() ?: return 0.0
        return if (closed && eastNorth.size > 2) {
            open + hypot(eastNorth.last().first - eastNorth.first().first, eastNorth.last().second - eastNorth.first().second)
        } else {
            open
        }
    }
}
