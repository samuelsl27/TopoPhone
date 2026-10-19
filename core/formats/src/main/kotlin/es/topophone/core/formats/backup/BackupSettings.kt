package es.topophone.core.formats.backup

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Un ajuste con su tipo, para restaurarlo con la misma clave de DataStore (S013/RF-2, RF-8). */
sealed interface SettingValue {
    data class Text(val value: String) : SettingValue

    data class Int32(val value: Int) : SettingValue

    data class Int64(val value: Long) : SettingValue

    data class Float32(val value: Float) : SettingValue

    data class Float64(val value: Double) : SettingValue

    data class Flag(val value: Boolean) : SettingValue

    data class Texts(val value: Set<String>) : SettingValue
}

/** Los ajustes que viajan en la copia, por almacén, y el idioma de la app ("" = el del sistema). */
data class BackupSettings(val stores: Map<String, Map<String, SettingValue>>, val appLanguage: String)

/**
 * `settings.json` de la copia completa (S013, plan "settings.json"). Cada valor lleva su tipo
 * (`{"int": 20}`, `{"double": 1.8}`…), porque JSON no distingue un `Int` de un `Long` ni un `Float` de un `Double`.
 * Los `double` se escriben con su representación más corta que vuelve al mismo valor (constitución nº 2).
 */
object BackupSettingsJson {
    private val json = Json

    fun encode(settings: BackupSettings): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("stores", JsonObject(settings.stores.mapValues { (_, values) -> JsonObject(values.mapValues { encode(it.value) }) }))
            put("appLanguage", settings.appLanguage)
        },
    )

    /** null si el texto no es un `settings.json` válido; los tipos que no conoce (de una versión futura) se ignoran. */
    fun decode(text: String): BackupSettings? = try {
        val root = json.parseToJsonElement(text) as? JsonObject
        val stores = root?.get("stores") as? JsonObject
        val language = (root?.get("appLanguage") as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (stores == null || language == null) {
            null
        } else {
            BackupSettings(
                stores = stores.mapValues { (_, values) ->
                    (values as? JsonObject ?: return null).entries.mapNotNull { (key, value) ->
                        when (val decoded = decode(value as? JsonObject ?: return null)) {
                            Decoded.Unknown -> null
                            Decoded.Invalid -> return null
                            is Decoded.Value -> key to decoded.value
                        }
                    }.toMap()
                },
                appLanguage = language,
            )
        }
    } catch (_: SerializationException) {
        null
    }

    private fun encode(value: SettingValue): JsonObject = buildJsonObject {
        when (value) {
            is SettingValue.Text -> put(STRING, value.value)
            is SettingValue.Int32 -> put(INT, value.value)
            is SettingValue.Int64 -> put(LONG, value.value)
            is SettingValue.Float32 -> put(FLOAT, value.value)
            is SettingValue.Float64 -> put(DOUBLE, value.value)
            is SettingValue.Flag -> put(BOOLEAN, value.value)
            is SettingValue.Texts -> put(STRINGS, JsonArray(value.value.sorted().map(::JsonPrimitive)))
        }
    }

    private sealed interface Decoded {
        data class Value(val value: SettingValue) : Decoded
        data object Unknown : Decoded
        data object Invalid : Decoded
    }

    private fun decode(value: JsonObject): Decoded {
        val (type, element) = value.entries.singleOrNull() ?: return Decoded.Invalid
        val primitive = element as? JsonPrimitive
        val decoded: SettingValue? = when (type) {
            STRING -> primitive?.takeIf { it.isString }?.content?.let(SettingValue::Text)
            INT -> primitive?.takeUnless { it.isString }?.intOrNull?.let(SettingValue::Int32)
            LONG -> primitive?.takeUnless { it.isString }?.longOrNull?.let(SettingValue::Int64)
            FLOAT -> primitive?.takeUnless { it.isString }?.floatOrNull?.let(SettingValue::Float32)
            DOUBLE -> primitive?.takeUnless { it.isString }?.doubleOrNull?.let(SettingValue::Float64)
            BOOLEAN -> primitive?.takeUnless { it.isString }?.booleanOrNull?.let(SettingValue::Flag)
            STRINGS -> (element as? JsonArray)?.map {
                (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return Decoded.Invalid
            }
                ?.toSet()?.let(SettingValue::Texts)
            else -> return Decoded.Unknown
        }
        return decoded?.let(Decoded::Value) ?: Decoded.Invalid
    }

    private const val STRING = "string"
    private const val INT = "int"
    private const val LONG = "long"
    private const val FLOAT = "float"
    private const val DOUBLE = "double"
    private const val BOOLEAN = "boolean"
    private const val STRINGS = "strings"
}
