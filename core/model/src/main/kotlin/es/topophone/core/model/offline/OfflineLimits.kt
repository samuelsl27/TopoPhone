package es.topophone.core.model.offline

/** Los límites de las zonas sin conexión (S037/RF-3, RF-4, RF-9, RF-12, RF-14, RF-19, RF-31 y RNF). */
object OfflineLimits {
    private const val MB = 1024L * 1024
    private const val GB = 1024L * MB

    /** S037/RF-3: radio alrededor del centro del proyecto. */
    const val MIN_RADIUS_M = 100.0
    const val MAX_RADIUS_M = 20_000.0

    /** S037/RF-4: margen de «todo el proyecto» por cada lado. */
    const val MIN_MARGIN_M = 200.0
    const val MAX_MARGIN_M = 2_000.0

    /** S037/RF-31: límite de almacenamiento de los mapas sin conexión, aparte de la caché de S009. */
    const val DEFAULT_STORAGE_LIMIT_BYTES = 2 * GB
    const val MIN_STORAGE_LIMIT_BYTES = 200 * MB
    const val MAX_STORAGE_LIMIT_BYTES = 10 * GB

    /** S037/RF-12: espacio libre que debe quedar en el móvil además de lo que se descarga. */
    const val FREE_SPACE_RESERVE_BYTES = 500 * MB

    /** S037/RF-14: con datos móviles se confirma una descarga de más de esto. */
    const val MOBILE_CONFIRMATION_BYTES = 50 * MB

    /** S037/RF-9: lo que OpenFreeMap admite por descarga sin conexión (<https://openfreemap.org/tos/>); 50 MB decimales, por el lado seguro. */
    const val OPENFREEMAP_MAX_TILES = 3_000L
    const val OPENFREEMAP_MAX_BYTES = 50_000_000L

    /** S037/RNF: peticiones simultáneas a un mismo servidor; y es también lo que pide OpenFreeMap. */
    const val MAX_REQUESTS_PER_HOST = 4

    /** S037/RF-19: segundos sin avance (con errores) tras los que el descargador da por fallidas las teselas que faltan; MapLibre reintenta sin fin. */
    const val STALL_TIMEOUT_S = 30

    fun isValidRadius(metres: Double): Boolean = metres in MIN_RADIUS_M..MAX_RADIUS_M

    fun isValidMargin(metres: Double): Boolean = metres in MIN_MARGIN_M..MAX_MARGIN_M

    fun clampStorageLimit(bytes: Long): Long = bytes.coerceIn(MIN_STORAGE_LIMIT_BYTES, MAX_STORAGE_LIMIT_BYTES)

    /** S037/RF-14: más de 50 MB (exactamente 50 MB no pide confirmación). */
    fun needsMobileConfirmation(estimatedBytes: Long): Boolean = estimatedBytes > MOBILE_CONFIRMATION_BYTES

    /** S037/RF-19: fallan más del 2 % de las teselas de la fuente (en enteros: `fallidas · 50 > total`). */
    fun exceedsFailureThreshold(failed: Long, total: Long): Boolean = total > 0 && failed * 50 > total

    /** S037/RF-9: más de 3.000 teselas o más de 50 MB estimados. */
    fun exceedsOpenFreeMapLimit(tiles: Long, bytes: Long): Boolean = tiles > OPENFREEMAP_MAX_TILES || bytes > OPENFREEMAP_MAX_BYTES
}
