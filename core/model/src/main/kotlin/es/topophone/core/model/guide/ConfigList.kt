package es.topophone.core.model.guide

/** Las listas de configuración del receptor (S043/RF-1). Los textos de cada una están en los recursos de la app. */
enum class ConfigListId { NTRIP_ROVER, WIFI_ROVER, TILT_ROVER, BASE }

/**
 * Un paso de una lista: qué ajuste poner en el receptor y dónde. [checks] son las comprobaciones previas asociadas; sin ninguna, el paso
 * solo se marca a mano (S043/RF-2, RF-4).
 */
data class ConfigStep(val id: String, val checks: List<CheckId> = emptyList()) {
    val checkable: Boolean get() = checks.isNotEmpty()
}

/** Pasos ordenados para dejar el receptor listo para una tarea (S043, términos). */
data class ConfigList(val id: ConfigListId, val steps: List<ConfigStep>)
