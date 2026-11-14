package es.topophone.core.model.offline

/** Las tres formas de definir el área de una zona (S037/RF-2). */
enum class OfflineAreaKind(val key: String) {
    RADIUS("radius"),
    VIEWPORT("viewport"),
    PROJECT("project"),
    ;

    companion object {
        fun fromKey(key: String): OfflineAreaKind? = entries.firstOrNull { it.key == key }
    }
}

/**
 * El área de una zona sin conexión, en grados (S037/RF-2 a RF-4). Toda zona se descarga como una caja en latitud y longitud (plan, A1); `core:geodesy` la
 * calcula a partir de estos datos. Las coordenadas no se redondean (constitución).
 */
sealed interface OfflineArea {
    val kind: OfflineAreaKind

    /** Un radio de [radiusM] (de 100 m a 20 km) alrededor del centro del proyecto (S006/RF-37). */
    data class Radius(val latitudeDeg: Double, val longitudeDeg: Double, val radiusM: Double) : OfflineArea {
        init {
            require(latitudeDeg in -90.0..90.0) { "Latitud fuera de rango: $latitudeDeg" }
            require(longitudeDeg in -180.0..180.0) { "Longitud fuera de rango: $longitudeDeg" }
            require(OfflineLimits.isValidRadius(radiusM)) { "El radio va de 100 m a 20 km: $radiusM" }
        }

        override val kind get() = OfflineAreaKind.RADIUS
    }

    /** El encuadre visible del mapa. Nunca cruza el antimeridiano: el mapa es de España. */
    data class Viewport(val south: Double, val west: Double, val north: Double, val east: Double) : OfflineArea {
        init {
            require(south in -90.0..90.0 && north in -90.0..90.0) { "Latitud fuera de rango" }
            require(west in -180.0..180.0 && east in -180.0..180.0) { "Longitud fuera de rango" }
            require(south < north) { "El sur debe quedar por debajo del norte" }
            require(west < east) { "El oeste debe quedar a la izquierda del este" }
        }

        override val kind get() = OfflineAreaKind.VIEWPORT
    }

    /**
     * Todo el proyecto: la extensión de sus puntos (un solo punto la deja en un punto, `south == north`) con un [marginM] de 200 m a 2 km por cada lado
     * (S037/RF-4, término «Todo el proyecto»).
     */
    data class ProjectExtent(val south: Double, val west: Double, val north: Double, val east: Double, val marginM: Double) : OfflineArea {
        init {
            require(south in -90.0..90.0 && north in -90.0..90.0) { "Latitud fuera de rango" }
            require(west in -180.0..180.0 && east in -180.0..180.0) { "Longitud fuera de rango" }
            require(south <= north) { "El sur no puede quedar por encima del norte" }
            require(west <= east) { "El oeste no puede quedar a la derecha del este" }
            require(OfflineLimits.isValidMargin(marginM)) { "El margen va de 200 m a 2 km: $marginM" }
        }

        override val kind get() = OfflineAreaKind.PROJECT
    }
}
