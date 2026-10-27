package es.topophone.core.model.alignment

/**
 * Por qué no se importa un eje (S049/RF-5, RF-6, RF-12). [element] es el número del tramo, desde 1, cuando el motivo es de un tramo.
 */
sealed interface AlignmentIssue {
    /** El final de un tramo y el principio del siguiente distan más de 1 cm (RF-5). */
    data class Gap(val element: Int, val gapM: Double) : AlignmentIssue

    /** El tipo de tramo no es una recta, un arco ni una clotoide (RF-5): [name] es como lo llama el fichero. */
    data class UnsupportedElement(val element: Int, val name: String) : AlignmentIssue

    /** El final declarado de un tramo no es el que sale de su geometría (RF-5). */
    data class InconsistentElement(val element: Int, val differenceM: Double) : AlignmentIssue

    /** El eje tiene ecuaciones de PK, que esta versión no admite (decisión D3). */
    data object StationEquation : AlignmentIssue

    /** Un trozo de la polilínea con vértices intermedios no es un arco de circunferencia (decisión D1, RF-6). */
    data class NonCircularCurve(val element: Int) : AlignmentIssue

    /** Un vértice del eje queda fuera del ámbito del sistema del proyecto (RF-12). */
    data object OutsideCrsArea : AlignmentIssue
}

/**
 * Por qué no se importa una rasante (S049/RF-10, decisión D3). [vertex] es el número del vértice, desde 1, y [chainage] su PK.
 */
sealed interface ProfileIssue {
    val vertex: Int
    val chainage: Double

    /** Dos vértices con el mismo PK. */
    data class RepeatedChainage(override val vertex: Int, override val chainage: Double) : ProfileIssue

    /** Un vértice antes del PK inicial del eje o después de su final. */
    data class OutsideAxis(override val vertex: Int, override val chainage: Double) : ProfileIssue

    /** Un acuerdo vertical que no cabe entre sus vértices vecinos, o un acuerdo en un extremo, o sin parámetro válido. */
    data class CurveDoesNotFit(override val vertex: Int, override val chainage: Double) : ProfileIssue
}
