package es.topophone.core.model.geometry

/** Distancias de la información geométrica: de cuadrícula o de terreno (S026/RF-9). */
enum class DistanceMode {
    GRID,
    GROUND,
    ;

    companion object {
        val DEFAULT = GRID

        fun fromKey(key: String?): DistanceMode = entries.firstOrNull { it.name == key } ?: DEFAULT
    }
}
