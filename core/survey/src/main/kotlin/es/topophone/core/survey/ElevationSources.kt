package es.topophone.core.survey

import es.topophone.core.model.point.ElevationSource

/**
 * Contra qué cota se replantea (S042/RF-24, RF-25, plan A5). La superficie nunca pisa una cota propia sin que el usuario lo elija:
 * 1. sin superficie activa, la cota del punto (o ninguna);
 * 2. si el usuario eligió un origen con el conmutador, ese;
 * 3. si no, con una cota propia o escrita a mano, la cota del punto;
 * 4. y sin ninguna cota propia ni escrita, la superficie.
 */
object ElevationSources {
    /** El origen que se usa y si el conmutador «Cota del punto» / «Superficie» se ofrece (RF-25). */
    data class Resolution(val source: ElevationSource, val canChoose: Boolean)

    /**
     * El origen de la cota de ahora. [hasOwnElevation] es que el punto de diseño (o el objetivo de una línea) trae cota propia;
     * [hasManualElevation] es que el usuario escribió una; [choice] es lo que eligió con el conmutador, o null si no ha elegido.
     * El conmutador se ofrece con una superficie activa y una cota propia o escrita con la que elegir.
     */
    fun resolve(surfaceActive: Boolean, hasOwnElevation: Boolean, hasManualElevation: Boolean, choice: ElevationSource?): Resolution {
        if (!surfaceActive) return Resolution(ElevationSource.POINT, canChoose = false)
        val hasPointElevation = hasOwnElevation || hasManualElevation
        val source = when {
            choice != null -> choice
            hasPointElevation -> ElevationSource.POINT
            else -> ElevationSource.SURFACE
        }
        return Resolution(source, canChoose = hasPointElevation)
    }
}
