package es.topophone.receiver.experimental

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Un ajuste del receptor tal como se muestra: su ruta y su valor. Si la ruta lleva una palabra sensible, [hidden] es `true` y [value] queda
 * vacío: el valor se descarta al leer, así no puede llegar a la pantalla ni al log (S044/RF-14, RNF-T11).
 */
data class ReachConfigEntry(val path: String, val value: String, val hidden: Boolean)

/** Lo que se ve de la configuración: ajustes de posicionamiento y de salida, en el orden en que el receptor los devuelve. */
data class ReachConfigView(val positioning: List<ReachConfigEntry>, val output: List<ReachConfigEntry>) {
    val count: Int get() = positioning.size + output.size
}

/**
 * Lee `GET /configuration` en solo lectura (S044/RF-14, RF-15; plan D6, A1): aplana `positioning_settings` y las secciones cuyo nombre
 * contiene `output` a pares ruta → valor y descarta los valores de cualquier ruta con `pass`, `secret`, `token`, `key` o `psk`.
 */
class ReachConfigReader(private val client: ReachHttpClient) {

    suspend fun read(): ReachPart<ReachConfigView> {
        val body = when (val result = client.get("/configuration")) {
            is ReachResult.Ok -> result.value
            is ReachResult.Failed -> return ReachPart.Incompatible(ReachPartId.SETTINGS, "sin respuesta")
        }
        val json = ReachJson.parse(body) ?: return ReachPart.Incompatible(ReachPartId.SETTINGS, "la respuesta no es JSON")
        val configuration = ReachJson.objectAt(json, "configuration")
        val positioning = configuration?.get(POSITIONING) as? JsonObject
            ?: return ReachPart.Incompatible(ReachPartId.SETTINGS, "falta configuration.$POSITIONING")
        val output = configuration.entries.filter { (name, value) -> OUTPUT in name && value is JsonObject }
        return ReachPart.Ok(
            ReachConfigView(
                positioning = flatten(POSITIONING, positioning),
                output = output.flatMap { (name, value) -> flatten(name, value) },
            ),
        )
    }

    private fun flatten(path: String, element: JsonElement): List<ReachConfigEntry> = when (element) {
        is JsonObject -> element.entries.flatMap { (key, value) -> flatten("$path.$key", value) }
        is JsonArray -> if (element.all { it is JsonPrimitive }) {
            listOf(entry(path, element.joinToString(", ") { text(it) }))
        } else {
            element.flatMapIndexed { index, value -> flatten("$path[$index]", value) }
        }
        else -> listOf(entry(path, text(element)))
    }

    private fun entry(path: String, value: String): ReachConfigEntry =
        if (isSensitive(path)) ReachConfigEntry(path, value = "", hidden = true) else ReachConfigEntry(path, value, hidden = false)

    private fun text(element: JsonElement): String = when (element) {
        is JsonNull -> ""
        is JsonPrimitive -> element.content
        else -> element.toString()
    }

    companion object {
        private const val POSITIONING = "positioning_settings"
        private const val OUTPUT = "output"
        private val SENSITIVE_WORDS = listOf("pass", "secret", "token", "key", "psk")

        /** Una ruta es sensible si algún tramo suyo, sin distinguir mayúsculas, lleva una de las palabras: se prefiere ocultar de más. */
        internal fun isSensitive(path: String): Boolean = path.lowercase().let { lower -> SENSITIVE_WORDS.any { it in lower } }
    }
}
