package es.topophone.core.formats.wms

import es.topophone.core.model.wms.WmsAddress
import es.topophone.core.model.wms.WmsAddressResult
import es.topophone.core.model.wms.WmsPreset
import es.topophone.core.model.wms.WmsRequestMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Los servicios WMS públicos que la app ya trae preparados (S036/RF-13, RF-16): el catálogo está en `presets.json`, que verifica
 * `tools/formatos/wms_presets.py` con peticiones reales, y cada uno lleva su atribución, su licencia y el modo de petición que piden sus
 * condiciones de uso (el Catastro y GRAFCAN, una imagen; los demás, teselas).
 */
object WmsPresets {
    private const val RESOURCE = "presets.json"
    private val KEY = Regex("[a-z0-9]+(-[a-z0-9]+)*")
    private val FORMATS = setOf("image/png", "image/jpeg")
    private val VERSIONS = setOf("1.1.1", "1.3.0")

    /** Todos los presets, en el orden del catálogo (por grupo y, en las ortofotos del ICV, del vuelo más reciente al más antiguo). */
    val all: List<WmsPreset> by lazy {
        val stream = WmsPresets::class.java.getResourceAsStream(RESOURCE) ?: error("Falta $RESOURCE entre los recursos")
        parse(stream.use { String(it.readBytes(), Charsets.UTF_8) })
    }

    fun byKey(key: String): WmsPreset? = all.firstOrNull { it.key == key }

    /** Los presets de [json]; [IllegalArgumentException] con el motivo si alguno no es válido o se repite una clave o una dirección. */
    fun parse(json: String): List<WmsPreset> {
        val presets = Json.parseToJsonElement(json).jsonArray.mapIndexed { index, element -> preset(element.jsonObject, index) }
        require(presets.map { it.key }.toSet().size == presets.size) { "Hay claves de preset repetidas" }
        require(presets.map { it.address }.toSet().size == presets.size) { "Hay direcciones de preset repetidas" }
        return presets
    }

    private fun preset(o: JsonObject, index: Int): WmsPreset {
        fun text(name: String): String? = (o[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        fun required(name: String) = text(name) ?: throw IllegalArgumentException("El preset $index no tiene «$name»")
        val key = required("key")
        require(KEY.matches(key)) { "La clave «$key» no es válida" }
        val address = required("address")
        val parsed = (WmsAddress.parse(address) as? WmsAddressResult.Valid)?.address
        require(parsed != null && parsed.isHttps && parsed.parameters.isEmpty() && parsed.base == address) {
            "La dirección del preset $key no es una dirección HTTPS normalizada sin parámetros: $address"
        }
        val mode = when (required("requestMode")) {
            "TILES" -> WmsRequestMode.TILES
            "IMAGE" -> WmsRequestMode.IMAGE
            else -> throw IllegalArgumentException("El preset $key tiene un modo de petición desconocido")
        }
        val format = text("preferredFormat")
        require(format == null || format in FORMATS) { "El preset $key tiene un formato desconocido: $format" }
        val infoVersion = text("infoVersion")
        require(infoVersion == null || infoVersion in VERSIONS) { "El preset $key tiene una versión de consulta desconocida: $infoVersion" }
        val licenseUrl = text("licenseUrl")
        require(licenseUrl == null || licenseUrl.startsWith("https://")) { "La licencia del preset $key no es una dirección HTTPS" }
        val layers = o["suggestedLayers"]?.jsonArray?.map { (it as JsonPrimitive).content }.orEmpty()
        require(layers.isNotEmpty() && layers.all { it.isNotEmpty() }) { "El preset $key no tiene capas sugeridas" }
        return WmsPreset(
            key = key,
            name = required("name"),
            region = required("region"),
            group = required("group"),
            year = (o["year"] as? JsonPrimitive)?.intOrNull,
            address = address,
            attribution = required("attribution"),
            license = required("license"),
            licenseUrl = licenseUrl,
            requestMode = mode,
            preferredFormat = format,
            infoVersion = infoVersion,
            suggestedLayers = layers,
            notes = text("notes").orEmpty(),
        )
    }
}
