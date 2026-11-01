package es.topophone.core.model.alignment.section

/** Por qué no se guarda una sección, una sección tipo o un peralte, con el punto afectado (S050/RF-7). */
sealed interface SectionIssue {
    /** Un código que no vale: vacío, de más de [SectionCode.MAX_LENGTH] caracteres o con espacios en los extremos. */
    data class InvalidCode(val code: String) : SectionIssue

    /** Un código que aparece dos veces en la misma sección. */
    data class RepeatedCode(val code: String) : SectionIssue

    /** El punto de rasante no es el eje ni un código de la sección tipo. */
    data class UnknownGradeCode(val code: String) : SectionIssue

    /** El punto [code] no queda a la derecha del anterior (o su tramo no tiene ancho positivo). */
    data class Unordered(val code: String) : SectionIssue

    /** Una pendiente de módulo mayor de 100 % o que no es un número, en el tramo [code] (vacío si es una fila de peralte). */
    data class SlopeTooSteep(val code: String, val slope: Double) : SectionIssue

    /** Un PK antes del inicio del eje o después de su final. */
    data class ChainageOutsideAxis(val chainage: Double) : SectionIssue

    /** Dos filas de peralte con el mismo PK. */
    data class RepeatedChainage(val chainage: Double) : SectionIssue

    /** Un rango de sección tipo cuyo final es anterior a su inicio. */
    data class ReversedRange(val fromChainage: Double, val toChainage: Double) : SectionIssue

    /** Un rango de sección tipo que se pisa con otro ya guardado (plan D1). */
    data class OverlappingTemplates(val fromChainage: Double, val toChainage: Double) : SectionIssue
}
