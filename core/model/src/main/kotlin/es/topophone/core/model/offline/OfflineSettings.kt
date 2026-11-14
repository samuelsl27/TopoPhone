package es.topophone.core.model.offline

/**
 * Lo que recuerda la app de las zonas sin conexión (S037/RF-8, RF-31): el límite de almacenamiento y el área y el nivel de la última descarga. Son de este
 * móvil y no viajan en la copia de seguridad.
 */
data class OfflineSettings(
    val storageLimitBytes: Long = OfflineLimits.DEFAULT_STORAGE_LIMIT_BYTES,
    val detail: DetailLevel = DetailLevel.NORMAL,
    val areaKind: OfflineAreaKind = OfflineAreaKind.RADIUS,
    val radiusM: Double = DEFAULT_RADIUS_M,
    val marginM: Double = DEFAULT_MARGIN_M,
) {
    /** Los mismos ajustes con cada valor dentro de sus límites; un número que no lo es vuelve al de fábrica. */
    fun sanitized(): OfflineSettings = copy(
        storageLimitBytes = OfflineLimits.clampStorageLimit(storageLimitBytes),
        radiusM = if (radiusM.isNaN()) DEFAULT_RADIUS_M else radiusM.coerceIn(OfflineLimits.MIN_RADIUS_M, OfflineLimits.MAX_RADIUS_M),
        marginM = if (marginM.isNaN()) DEFAULT_MARGIN_M else marginM.coerceIn(OfflineLimits.MIN_MARGIN_M, OfflineLimits.MAX_MARGIN_M),
    )

    companion object {
        const val DEFAULT_RADIUS_M = 500.0
        const val DEFAULT_MARGIN_M = 500.0
    }
}
