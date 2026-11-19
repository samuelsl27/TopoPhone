package es.topophone.receiver.experimental

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Lectura tolerante de JSON sin plugin ni clases de datos: cada campo se pide y se comprueba por separado (plan D6). */
internal object ReachJson {

    fun parse(text: String): JsonElement? = try {
        Json.parseToJsonElement(text)
    } catch (_: IllegalArgumentException) {
        null
    }

    /** El objeto que cuelga de `path` (una clave tras otra), o `null` si algo del camino falta o no es un objeto. */
    fun objectAt(root: JsonElement?, vararg path: String): JsonObject? {
        var current = root as? JsonObject ?: return null
        for (key in path) current = current[key] as? JsonObject ?: return null
        return current
    }

    /** Un texto de verdad: ni un número ni un booleano escritos sin comillas. */
    fun string(obj: JsonObject, key: String): String? = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Un número de verdad: ni un texto con dígitos ni un booleano. */
    fun number(obj: JsonObject, key: String): Double? = (obj[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
}
