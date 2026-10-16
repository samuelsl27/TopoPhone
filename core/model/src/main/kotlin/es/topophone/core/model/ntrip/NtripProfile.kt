package es.topophone.core.model.ntrip

import es.topophone.core.model.project.ProjectNames

/**
 * Datos de un perfil NTRIP que se rellenan en el formulario; la contraseña va aparte (S007/RF-2, RF-4).
 *
 * @property mountpointNeedsGga lo que dice la tabla de fuentes del punto de montaje elegido, o `null`
 *   si se escribió a mano y no se sabe (S007/RF-19, RF-21).
 */
data class NtripProfileDraft(
    val name: String,
    val host: String,
    val port: Int = DEFAULT_PORT,
    val useTls: Boolean = false,
    val username: String = "",
    val mountpoint: String = "",
    val mountpointNeedsGga: Boolean? = null,
) {
    companion object {
        const val DEFAULT_PORT = 2101
    }
}

/** Perfil NTRIP guardado; de la contraseña solo se sabe si la hay (RNF-T11). Fechas en ms UTC. */
data class NtripProfile(
    val id: String,
    val draft: NtripProfileDraft,
    val hasPassword: Boolean,
    val createdAtMs: Long,
    val modifiedAtMs: Long,
)

/** Por qué no se puede guardar un perfil NTRIP (S007/RF-3). */
enum class NtripProfileError { EMPTY_NAME, DUPLICATE_NAME, EMPTY_HOST, INVALID_PORT }

object NtripProfileRules {
    private val validPorts = 1..65535

    /** S007/RF-3: todos los motivos a la vez; los nombres se comparan como los de los proyectos (S006). */
    fun validate(draft: NtripProfileDraft, otherNames: Collection<String>): Set<NtripProfileError> = buildSet {
        when {
            draft.name.isBlank() -> add(NtripProfileError.EMPTY_NAME)
            otherNames.any { ProjectNames.same(it, draft.name) } -> add(NtripProfileError.DUPLICATE_NAME)
        }
        if (draft.host.isBlank()) add(NtripProfileError.EMPTY_HOST)
        if (draft.port !in validPorts) add(NtripProfileError.INVALID_PORT)
    }
}
