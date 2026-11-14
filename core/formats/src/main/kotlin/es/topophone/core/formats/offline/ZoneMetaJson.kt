package es.topophone.core.formats.offline

import es.topophone.core.model.offline.DetailLevel
import es.topophone.core.model.offline.DownloadableSource
import es.topophone.core.model.offline.OfflineArea
import es.topophone.core.model.offline.OfflineAreaKind
import es.topophone.core.model.offline.SourceKind
import es.topophone.core.model.offline.ZoneMeta
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * El JSON de los metadatos de una región (S037, plan «Modelo de datos»), en UTF-8 y con su versión (`v`). Lo que no se puede leer (otra versión, un campo que falta,
 * un valor fuera de rango) da null: la zona se trata como una región ajena y no se muestra. Los números se escriben con punto y sin redondear.
 */
object ZoneMetaJson {
    fun encode(meta: ZoneMeta): ByteArray = buildJsonObject {
        put("v", ZoneMeta.VERSION)
        put("zone", meta.zoneId)
        put("project", meta.projectId)
        put("name", meta.name)
        put("source", sourceJson(meta.source))
        put("area", areaJson(meta.area))
        put("detail", meta.detail.key)
        put("tiles", meta.tiles)
        put("estimatedBytes", meta.estimatedBytes)
        put("downloadedAt", meta.downloadedAtMs?.let { JsonPrimitive(it) } ?: JsonNull)
        put("failed", meta.failed)
    }.toString().toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): ZoneMeta? = try {
        val root = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
        if (root["v"]?.jsonPrimitive?.intOrNull != ZoneMeta.VERSION) {
            null
        } else {
            ZoneMeta(
                zoneId = root.text("zone"),
                projectId = root.text("project"),
                name = root.text("name"),
                source = sourceOf(root.obj("source")),
                area = areaOf(root.obj("area")),
                detail = DetailLevel.fromKey(root.text("detail")) ?: error("nivel"),
                tiles = root.long("tiles"),
                estimatedBytes = root.long("estimatedBytes"),
                downloadedAtMs = root["downloadedAt"]?.jsonPrimitive?.longOrNull,
                failed = root.long("failed"),
            )
        }
    } catch (_: Exception) {
        null
    }

    private fun sourceJson(source: DownloadableSource): JsonObject = buildJsonObject {
        put("kind", source.kind.key)
        source.serviceId?.let { put("serviceId", it) }
        source.layerName?.let { put("layer", it) }
        put("title", source.title)
    }

    private fun areaJson(area: OfflineArea): JsonObject = buildJsonObject {
        put("kind", area.kind.key)
        when (area) {
            is OfflineArea.Radius -> {
                put("lat", area.latitudeDeg)
                put("lon", area.longitudeDeg)
                put("radiusM", area.radiusM)
            }
            is OfflineArea.Viewport -> {
                put("south", area.south)
                put("west", area.west)
                put("north", area.north)
                put("east", area.east)
            }
            is OfflineArea.ProjectExtent -> {
                put("south", area.south)
                put("west", area.west)
                put("north", area.north)
                put("east", area.east)
                put("marginM", area.marginM)
            }
        }
    }

    private fun sourceOf(json: JsonObject) = DownloadableSource(
        kind = SourceKind.fromKey(json.text("kind")) ?: error("fuente"),
        title = json.text("title"),
        serviceId = json["serviceId"]?.jsonPrimitive?.contentOrNull,
        layerName = json["layer"]?.jsonPrimitive?.contentOrNull,
    )

    private fun areaOf(json: JsonObject): OfflineArea = when (OfflineAreaKind.fromKey(json.text("kind"))) {
        OfflineAreaKind.RADIUS -> OfflineArea.Radius(json.double("lat"), json.double("lon"), json.double("radiusM"))
        OfflineAreaKind.VIEWPORT -> OfflineArea.Viewport(
            json.double("south"),
            json.double("west"),
            json.double("north"),
            json.double("east"),
        )
        OfflineAreaKind.PROJECT -> OfflineArea.ProjectExtent(
            json.double("south"),
            json.double("west"),
            json.double("north"),
            json.double("east"),
            json.double("marginM"),
        )
        null -> error("area")
    }

    private fun JsonObject.text(name: String): String = this[name]?.jsonPrimitive?.contentOrNull ?: error(name)

    private fun JsonObject.long(name: String): Long = this[name]?.jsonPrimitive?.longOrNull ?: error(name)

    private fun JsonObject.double(name: String): Double = this[name]?.jsonPrimitive?.doubleOrNull ?: error(name)

    private fun JsonObject.obj(name: String): JsonObject = this[name]?.jsonObject ?: error(name)
}
