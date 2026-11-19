package es.topophone.receiver.experimental

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Quién es el receptor: lo que dice `GET /info` (el firmware es `device.app_version`, plan «Lo que enseña el código de referencia»). */
data class ReachIdentity(val model: String, val serial: String, val firmware: String)

/** Lo que dice `GET /configuration` del dispositivo: su rol y la altura de antena en metros (unidad supuesta, plan D10). */
data class ReachDeviceSettings(val role: String?, val antennaHeightM: Double)

/** La información básica completa (S044/RF-11): sin ella no se escribe nada (S044/RF-15). */
data class ReachInfo(val model: String, val serial: String, val firmware: String, val role: String?, val antennaHeightM: Double)

/** Las dos partes de la lectura, cada una con su compatibilidad (S044/RF-15). */
data class ReachInfoReading(val identity: ReachPart<ReachIdentity>, val device: ReachPart<ReachDeviceSettings>) {
    /** La información básica solo existe si las dos partes se entienden. */
    val info: ReachInfo?
        get() {
            val id = (identity as? ReachPart.Ok)?.value ?: return null
            val dev = (device as? ReachPart.Ok)?.value ?: return null
            return ReachInfo(id.model, id.serial, id.firmware, dev.role, dev.antennaHeightM)
        }
}

/** Lee modelo, serie, firmware, rol y altura de antena con dos GET y valida cada parte por separado (S044/RF-11, RF-15; plan A1). */
class ReachInfoReader(private val client: ReachHttpClient) {

    suspend fun read(): ReachInfoReading = ReachInfoReading(
        identity = readPart(ReachPartId.IDENTITY, "/info", ::identity),
        device = readPart(ReachPartId.DEVICE, "/configuration", ::device),
    )

    private suspend fun <T> readPart(part: ReachPartId, path: String, parse: (JsonElement) -> Parsed<T>): ReachPart<T> {
        val body = when (val result = client.get(path)) {
            is ReachResult.Ok -> result.value
            is ReachResult.Failed -> return ReachPart.Incompatible(part, "sin respuesta")
        }
        val json = ReachJson.parse(body) ?: return ReachPart.Incompatible(part, "la respuesta no es JSON")
        return when (val parsed = parse(json)) {
            is Parsed.Value -> ReachPart.Ok(parsed.value)
            is Parsed.Problem -> ReachPart.Incompatible(part, parsed.reason)
        }
    }

    private sealed interface Parsed<out T> {
        data class Value<T>(val value: T) : Parsed<T>

        data class Problem(val reason: String) : Parsed<Nothing>
    }

    private fun identity(json: JsonElement): Parsed<ReachIdentity> {
        val device = ReachJson.objectAt(json, "device") ?: return Parsed.Problem("falta device")
        val model = ReachJson.string(device, "model") ?: return Parsed.Problem(wrong(device, "model", "device.model", "un texto"))
        val serial = ReachJson.string(device, "serial_number")
            ?: return Parsed.Problem(wrong(device, "serial_number", "device.serial_number", "un texto"))
        val firmware = ReachJson.string(device, "app_version")
            ?: return Parsed.Problem(wrong(device, "app_version", "device.app_version", "un texto"))
        return Parsed.Value(ReachIdentity(model, serial, firmware))
    }

    private fun device(json: JsonElement): Parsed<ReachDeviceSettings> {
        val device = ReachJson.objectAt(json, "configuration", "device") ?: return Parsed.Problem("falta configuration.device")
        val height = ReachJson.number(device, "antenna_height")
            ?: return Parsed.Problem(wrong(device, "antenna_height", "configuration.device.antenna_height", "un numero"))
        return Parsed.Value(ReachDeviceSettings(role = ReachJson.string(device, "role"), antennaHeightM = height))
    }

    /** «falta X» si no está y «X no es un texto/numero» si está con otro tipo; nunca el valor. */
    private fun wrong(parent: JsonObject, key: String, path: String, expected: String): String =
        if (key in parent) "$path no es $expected" else "falta $path"
}
