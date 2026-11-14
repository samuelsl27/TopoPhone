package es.topophone.core.formats.cogo

import es.topophone.core.model.cogo.CogoEntry
import es.topophone.core.model.cogo.CogoOperation
import es.topophone.core.model.cogo.CogoRef
import es.topophone.core.model.cogo.CogoValue
import es.topophone.core.model.cogo.IntersectionSide
import es.topophone.core.model.cogo.LegKind
import es.topophone.core.model.cogo.TraverseLeg
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * El JSON de `points.cogo` (S038/RF-22, plan «Modelo de datos»): la operación, los puntos de entrada por valor, los tramos o los valores
 * escritos, y la solución elegida, con su versión (`v`). Los números salen de `Double.toString`: con punto decimal, sin redondear y de
 * ida y vuelta exacta, sea cual sea la configuración regional (RNF-T9). Lo que no se puede leer (otra versión, un campo que falta, un
 * nombre desconocido) da null.
 */
object CogoRefJson {
    const val VERSION = 1

    fun encode(ref: CogoRef): String = buildJsonObject {
        put("v", VERSION)
        put("op", ref.operation.name)
        put("entries", JsonArray(ref.entries.map(::entryJson)))
        put("legs", JsonArray(ref.legs.map(::legJson)))
        put("values", JsonArray(ref.values.map(::valueJson)))
        put("solution", ref.solution?.let { JsonPrimitive(it.name) } ?: JsonNull)
    }.toString()

    fun decode(text: String): CogoRef? = try {
        val root = Json.parseToJsonElement(text).jsonObject
        if (root["v"]?.jsonPrimitive?.intOrNull != VERSION) {
            null
        } else {
            CogoRef(
                operation = CogoOperation.valueOf(root.text("op")),
                entries = root.array("entries").map { entryOf(it.jsonObject) },
                legs = root.array("legs").map { legOf(it.jsonObject) },
                values = root.array("values").map { CogoValue(it.jsonObject.text("key"), it.jsonObject.number("value")) },
                solution = root["solution"]?.jsonPrimitive?.contentOrNull?.let { IntersectionSide.valueOf(it) },
            )
        }
    } catch (_: Exception) {
        null
    }

    internal fun legJson(leg: TraverseLeg): JsonObject = buildJsonObject {
        put("kind", leg.kind.name)
        put("angleRad", leg.angleRad?.let { JsonPrimitive(it) } ?: JsonNull)
        put("distanceM", leg.distanceM?.let { JsonPrimitive(it) } ?: JsonNull)
        put("dHeightM", leg.dHeightM?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    internal fun legOf(json: JsonObject) = TraverseLeg(
        kind = LegKind.valueOf(json.text("kind")),
        angleRad = json.numberOrNull("angleRad"),
        distanceM = json.numberOrNull("distanceM"),
        dHeightM = json.numberOrNull("dHeightM"),
    )

    private fun valueJson(value: CogoValue): JsonObject = buildJsonObject {
        put("key", value.key)
        put("value", value.value)
    }

    private fun entryJson(entry: CogoEntry): JsonObject = buildJsonObject {
        put("role", entry.role)
        put("name", entry.name?.let { JsonPrimitive(it) } ?: JsonNull)
        put("e", entry.easting)
        put("n", entry.northing)
        put("h", entry.height?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    private fun entryOf(json: JsonObject) = CogoEntry(
        role = json.text("role"),
        name = json["name"]?.jsonPrimitive?.contentOrNull,
        easting = json.number("e"),
        northing = json.number("n"),
        height = json.numberOrNull("h"),
    )

    internal fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.contentOrNull ?: error(key)

    internal fun JsonObject.number(key: String): Double = getValue(key).jsonPrimitive.doubleOrNull ?: error(key)

    internal fun JsonObject.numberOrNull(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull

    internal fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray
}
