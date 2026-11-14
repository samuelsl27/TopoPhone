package es.topophone.core.formats.cogo

import es.topophone.core.formats.cogo.CogoRefJson.array
import es.topophone.core.formats.cogo.CogoRefJson.number
import es.topophone.core.formats.cogo.CogoRefJson.text
import es.topophone.core.model.cogo.DraftPoint
import es.topophone.core.model.cogo.TraverseDraft
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * El JSON de `traverse_drafts.data` (S038/RF-26, plan D12): los puntos de entrada y los tramos escritos, en SI, con su versión (`v`).
 * El proyecto y la hora del último cambio van en las columnas de la fila, no aquí. Un valor que falta o no es un número es `null` y se
 * conserva; lo que no se puede leer da null.
 */
object TraverseDraftJson {
    const val VERSION = 1

    fun encode(draft: TraverseDraft): String = buildJsonObject {
        put("v", VERSION)
        put("start", pointJson(draft.start))
        put("reference", pointJson(draft.reference))
        put("closure", pointJson(draft.closure))
        put("legs", JsonArray(draft.legs.map(CogoRefJson::legJson)))
    }.toString()

    fun decode(text: String, projectId: String, updatedAtMs: Long): TraverseDraft? = try {
        val root = Json.parseToJsonElement(text).jsonObject
        if (root["v"]?.jsonPrimitive?.intOrNull != VERSION) {
            null
        } else {
            TraverseDraft(
                projectId = projectId,
                start = pointOf(root["start"]),
                reference = pointOf(root["reference"]),
                closure = pointOf(root["closure"]),
                legs = root.array("legs").map { CogoRefJson.legOf(it.jsonObject) },
                updatedAtMs = updatedAtMs,
            )
        }
    } catch (_: Exception) {
        null
    }

    private fun pointJson(point: DraftPoint?): JsonElement = when (point) {
        null -> JsonNull
        is DraftPoint.OfProject -> buildJsonObject {
            put("pointId", point.pointId)
            put("name", point.name)
        }
        is DraftPoint.Typed -> buildJsonObject {
            put("e", point.easting)
            put("n", point.northing)
        }
    }

    private fun pointOf(element: JsonElement?): DraftPoint? {
        if (element == null || element is JsonNull) return null
        val json: JsonObject = element.jsonObject
        return if ("pointId" in json) {
            DraftPoint.OfProject(json.text("pointId"), json.text("name"))
        } else {
            DraftPoint.Typed(json.number("e"), json.number("n"))
        }
    }
}
