package es.topophone.core.model.survey.linear

/** Qué se toma con el receptor: un eje, el perfil longitudinal de un eje o el perfil transversal de un PK (S052). */
enum class LinearSurveyKind { AXIS, LONGITUDINAL, CROSS }

/** Por qué un punto de una toma queda fuera del perfil, o `NONE` si entra (S052/RF-12, RF-18). */
enum class SurveyFlag { NONE, OUT_OF_AXIS, NO_PROJECTION, OUT_OF_SECTION }

/**
 * Una toma: una sesión de medida de uno de los tres tipos (S052, Términos). [alignmentId] es el eje creado (en una toma de eje, null
 * mientras está abierta) o el eje sobre el que se mide. [chainageFrom], [chainageTo] e [intervalM] son el modo guiado del longitudinal y
 * [chainage] el PK de la sección del transversal; [toleranceM] es la tolerancia de eje o de sección elegida al empezar (null: la de
 * omisión). Sin [finishedAtMs] la toma está abierta y se puede reanudar (RF-9).
 */
data class LinearSurvey(
    val id: String,
    val projectId: String,
    val kind: LinearSurveyKind,
    val alignmentId: String?,
    val name: String,
    val chainageFrom: Double? = null,
    val chainageTo: Double? = null,
    val intervalM: Double? = null,
    val chainage: Double? = null,
    val guided: Boolean = false,
    val toleranceM: Double? = null,
    val createdAtMs: Long,
    val finishedAtMs: Long? = null,
) {
    val isOpen: Boolean get() = finishedAtMs == null

    companion object {
        /** Distancia máxima al eje con que un punto entra en un perfil longitudinal (S052/RF-12). */
        const val DEFAULT_AXIS_TOLERANCE_M = 0.50

        /** Distancia máxima a la línea de la sección con que un punto es del PK (S052/RF-18). */
        const val DEFAULT_SECTION_TOLERANCE_M = 0.10
    }
}

/**
 * Un punto del proyecto dentro de una toma (S052/RF-2): su orden de medida, si sigue incluido (RF-4, RF-21) y, calculados al
 * añadirlo, su PK, su desplazamiento, la diferencia de cota con la rasante o la sección de diseño y la marca (RF-11, RF-17). Los números
 * no se redondean. [code] es el código de sección del punto en un perfil transversal (RF-17).
 */
data class SurveyPoint(
    val pointId: String,
    val ordinal: Int,
    val included: Boolean,
    val chainageM: Double?,
    val offsetM: Double?,
    val dHeightM: Double?,
    val flag: SurveyFlag,
    val code: String? = null,
) {
    /** Entra en el perfil si está incluido y no tiene marca (RF-12, RF-18, RF-21). */
    val inProfile: Boolean get() = included && flag == SurveyFlag.NONE
}
