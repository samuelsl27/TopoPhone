package es.topophone.core.model.wms

import java.util.Locale

/** Cómo se pide una capa al servicio: «por teselas» o una sola imagen de la zona visible (S036, glosario «Modo de petición»). */
enum class WmsRequestMode { TILES, IMAGE }

/**
 * Un servicio WMS de la biblioteca del móvil, con sus capas ya leídas aparte (S036/RF-3, RF-4).
 *
 * @property name el nombre que ve el usuario, que puede renombrar (RF-7).
 * @property address la dirección normalizada, sin los parámetros estándar de WMS (véase [WmsAddress]).
 * @property title el título que declara el servicio.
 * @property version la versión del último GetCapabilities leído: `1.3.0` o `1.1.1`. Algunos servicios contestan en una distinta de la
 *   pedida, así que todas las peticiones posteriores usan esta (RF-2).
 * @property presetKey la clave del preset de donde viene, o null si lo escribió el usuario (RF-14).
 * @property mapFormats y [infoFormats] los formatos que anuncia para GetMap y para GetFeatureInfo.
 */
data class WmsService(
    val id: String,
    val name: String,
    val address: String,
    val title: String,
    val version: String,
    val attribution: String,
    val presetKey: String?,
    val requestMode: WmsRequestMode,
    val mapFormats: List<String>,
    val infoFormats: List<String>,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

/** Por qué una capa se ve en el árbol pero no se puede añadir a un proyecto (S036/RF-12). */
enum class WmsLayerProblem { GROUP_ONLY, NO_WEB_MERCATOR }

/**
 * Una capa del árbol de un servicio, en el orden del documento (S036/RF-3). [depth] es 0 para las de primer nivel.
 *
 * @property name null si la capa solo agrupa a otras (no se puede pedir).
 * @property transparent si admite transparencia: `opaque` casi nunca viene bien declarado, así que no decide el formato de las imágenes.
 * @property queryable si admite GetFeatureInfo (RF-36).
 * @property mercatorCode el código de Web Mercator que la capa o un ascendiente anuncia, con preferencia por `EPSG:3857`, o null si no
 *   anuncia ninguno (RF-9).
 */
data class WmsLayerInfo(
    val ordinal: Int,
    val depth: Int,
    val name: String?,
    val title: String,
    val abstract: String,
    val transparent: Boolean,
    val queryable: Boolean,
    val mercatorCode: String?,
) {
    /** Lo que falta para poder añadir la capa a un proyecto, o null si se puede (RF-12). */
    val notAddableReason: WmsLayerProblem?
        get() = when {
            name == null -> WmsLayerProblem.GROUP_ONLY
            mercatorCode == null -> WmsLayerProblem.NO_WEB_MERCATOR
            else -> null
        }

    val isAddable: Boolean get() = notAddableReason == null
}

/**
 * Una capa de un servicio añadida a un proyecto (S036/RF-18 a RF-24). [position] 0 es la de arriba; [opacity] va de 0 a 100 de 5 en 5.
 */
data class ProjectMapLayer(
    val id: String,
    val projectId: String,
    val serviceId: String,
    val layerName: String,
    val position: Int,
    val visible: Boolean,
    val opacity: Int,
) {
    init {
        require(WmsLimits.isValidOpacity(opacity)) { "La opacidad va de 0 a 100 de 5 en 5: $opacity" }
    }
}

/**
 * Un servicio público que la app ya trae preparado (S036/RF-13 a RF-16).
 *
 * @property group y [year] agrupan los presets en la lista (el ICV tiene una ortofoto por vuelo).
 * @property license el texto corto de la licencia y [licenseUrl] su página, si la hay.
 * @property preferredFormat el formato de las teselas (la ortofoto va en JPEG); null deja el de [WmsRequestMode] por defecto.
 * @property infoVersion la versión con la que se hacen las consultas de información cuando el servicio no responde bien en la suya.
 */
data class WmsPreset(
    val key: String,
    val name: String,
    val region: String,
    val group: String,
    val year: Int?,
    val address: String,
    val attribution: String,
    val license: String,
    val licenseUrl: String?,
    val requestMode: WmsRequestMode,
    val preferredFormat: String?,
    val infoVersion: String?,
    val suggestedLayers: List<String>,
    val notes: String = "",
)

/** Los límites y las constantes de las capas WMS (S036). */
object WmsLimits {
    /** Capas por proyecto (RF-26). */
    const val MAX_PROJECT_LAYERS = 10

    /** Tiempo máximo de cada petición de capacidades o de consulta (RF-8, RF-39). */
    const val TIMEOUT_S = 20

    /** Tamaño máximo del documento de capacidades (RF-11). */
    const val MAX_CAPABILITIES_BYTES = 20L * 1024 * 1024

    /** Niveles de capas anidadas que se leen (RF-11). */
    const val MAX_DEPTH = 10

    /** Paso de la opacidad en % (RF-21). */
    const val OPACITY_STEP = 5

    /** Entidades que se piden en una consulta de información (A6 del plan). */
    const val FEATURE_COUNT = 5

    /** Consultas de información a la vez (A6 del plan). */
    const val MAX_PARALLEL_INFO = 5

    private const val OPACITY_MAX = 100
    private const val PREFERRED_CODE = "EPSG:3857"
    private val WEB_MERCATOR_CODES = listOf(PREFERRED_CODE, "EPSG:900913", "EPSG:102100", "EPSG:102113", "EPSG:3785")

    fun isValidOpacity(percent: Int): Boolean = percent in 0..OPACITY_MAX && percent % OPACITY_STEP == 0

    /** El valor de la rejilla de 5 % más cercano dentro de 0 a 100 (RF-21). */
    fun snapOpacity(percent: Int): Int = (percent.coerceIn(0, OPACITY_MAX) + OPACITY_STEP / 2) / OPACITY_STEP * OPACITY_STEP

    /** Si [code] es EPSG:3857 o uno de sus alias de Web Mercator (RF-9; D5 del plan), sin distinguir mayúsculas. */
    fun isWebMercator(code: String): Boolean = normalize(code) in WEB_MERCATOR_CODES

    /** De los códigos que anuncia una capa, el de Web Mercator que se usa: `EPSG:3857` si está y si no el primero que valga; null si ninguno. */
    fun preferredWebMercator(codes: List<String>): String? {
        val valid = codes.map(::normalize).filter { it in WEB_MERCATOR_CODES }
        return if (PREFERRED_CODE in valid) PREFERRED_CODE else valid.firstOrNull()
    }

    private fun normalize(code: String) = code.trim().uppercase(Locale.ROOT)
}
