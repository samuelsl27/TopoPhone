package es.topophone.core.survey

import es.topophone.core.model.project.ProjectNames

/** Qué campo de un perfil o de un jalón no es válido (S046/RF-4, RF-10). */
enum class GearProblemField { NAME_EMPTY, NAME_TOO_LONG, NAME_TAKEN, OFFSET, RADIUS, HEIGHT }

/** Resultado de validar lo escrito para un perfil de antena. */
sealed interface ProfileCheck {
    data class Valid(val name: String, val offsetM: Double, val radiusM: Double?) : ProfileCheck

    data class Invalid(val field: GearProblemField) : ProfileCheck
}

/** Resultado de validar lo escrito para un jalón. */
sealed interface PoleCheck {
    data class Valid(val name: String, val kind: PoleKind, val enteredM: Double) : PoleCheck

    data class Invalid(val field: GearProblemField) : PoleCheck
}

/**
 * Nombres únicos y rangos de los perfiles de antena y de los jalones guardados (S046/RF-3, RF-4, RF-9, RF-10; plan A4). Los
 * nombres se comparan sin espacios a los lados, sin mayúsculas y sin tildes, como los de proyecto (S006/RF-7).
 */
object GearValidation {
    const val PROFILE_NAME_MAX = 40
    const val POLE_NAME_MAX = 30
    const val OFFSET_MAX_M = 1.0
    const val RADIUS_MAX_M = 0.3

    private val NUMBER = Regex("""^\d+([.,]\d+)?$""")

    /**
     * @param existing los nombres que ya hay.
     * @param ignoring el nombre que se está editando, que no cuenta como repetido.
     */
    fun profile(name: String, offsetText: String, radiusText: String, existing: Collection<String>, ignoring: String?): ProfileCheck {
        val clean = name.trim()
        nameProblem(clean, PROFILE_NAME_MAX, existing, ignoring)?.let { return ProfileCheck.Invalid(it) }
        val offset = parse(offsetText, OFFSET_MAX_M) ?: return ProfileCheck.Invalid(GearProblemField.OFFSET)
        val radius = if (radiusText.isBlank()) {
            null
        } else {
            parse(radiusText, RADIUS_MAX_M) ?: return ProfileCheck.Invalid(GearProblemField.RADIUS)
        }
        return ProfileCheck.Valid(clean, offset, radius)
    }

    fun pole(name: String, kind: PoleKind, heightText: String, existing: Collection<String>, ignoring: String?): PoleCheck {
        val clean = name.trim()
        nameProblem(clean, POLE_NAME_MAX, existing, ignoring)?.let { return PoleCheck.Invalid(it) }
        val height = PoleHeight.parse(heightText) ?: return PoleCheck.Invalid(GearProblemField.HEIGHT)
        return PoleCheck.Valid(clean, kind, height)
    }

    private fun nameProblem(clean: String, max: Int, existing: Collection<String>, ignoring: String?): GearProblemField? = when {
        clean.isEmpty() -> GearProblemField.NAME_EMPTY
        clean.length > max -> GearProblemField.NAME_TOO_LONG
        existing.any { taken(it, clean, ignoring) } -> GearProblemField.NAME_TAKEN
        else -> null
    }

    private fun taken(other: String, clean: String, ignoring: String?): Boolean =
        ProjectNames.same(other, clean) && (ignoring == null || !ProjectNames.same(other, ignoring))

    /** Metros entre 0 y [max], con punto o coma decimal; null si no es un número de ese rango. */
    private fun parse(text: String, max: Double): Double? {
        val trimmed = text.trim()
        if (!NUMBER.matches(trimmed)) return null
        return trimmed.replace(',', '.').toDouble().takeIf { it in 0.0..max }
    }
}
