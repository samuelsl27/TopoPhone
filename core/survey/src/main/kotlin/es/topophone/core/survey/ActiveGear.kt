package es.topophone.core.survey

import es.topophone.core.model.point.PoleKind
/**
 * Un jalón guardado (S046/RF-9, RF-16).
 *
 * @property enteredM lo escrito: la altura vertical o la inclinada, según [kind].
 */
data class SavedPole(val id: String, val name: String, val kind: PoleKind, val enteredM: Double)

/**
 * El equipo de un receptor ya resuelto: su perfil de antena y su jalón activo con las alturas calculadas (S046/RF-8,
 * RF-15; plan A3).
 *
 * @property problem por qué no se puede medir; null si se puede (S046/RF-28).
 */
data class ActiveGear(
    val profile: AntennaProfile,
    val pole: SavedPole?,
    val verticalPoleM: Double?,
    val antennaHeightM: Double?,
    val problem: GearProblem?,
) {
    val usable: Boolean get() = problem == null
}

/** Del perfil y el jalón que recuerda un receptor al equipo que se usa (S046/RF-7, RF-13; plan A3). */
object GearResolver {

    /**
     * @param profileRef `catalog:<modelo>` o `own:<uuid>`; null si el receptor no se ha configurado: RS3.
     * @param ownProfiles los perfiles propios; un perfil que ya no existe cae en el RS3.
     * @param activePoleId el jalón activo; si es null o ya no existe, el primero de [poles] (la lista va por antigüedad).
     */
    fun resolve(profileRef: String?, ownProfiles: List<AntennaProfile>, activePoleId: String?, poles: List<SavedPole>): ActiveGear {
        val profile = profileRef?.let { ref -> AntennaCatalog.byRef(ref) ?: ownProfiles.firstOrNull { it.ref == ref } }
            ?: AntennaProfile.RS3
        val pole = poles.firstOrNull { it.id == activePoleId } ?: poles.firstOrNull()
            ?: return ActiveGear(profile, null, null, null, GearProblem.NO_POLE)
        return when (val vertical = PoleGeometry.vertical(pole.kind, pole.enteredM, profile.radiusM)) {
            is VerticalHeight.Invalid -> ActiveGear(profile, pole, null, null, vertical.problem)
            is VerticalHeight.Valid -> ActiveGear(
                profile,
                pole,
                vertical.metres,
                PoleGeometry.antennaHeightM(vertical.metres, profile.offsetM),
                null,
            )
        }
    }
}
