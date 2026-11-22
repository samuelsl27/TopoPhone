package es.topophone.core.formats.crs

import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.CrsDefinitions
import es.topophone.core.geodesy.catalog.CustomBuildResult
import es.topophone.core.geodesy.catalog.CustomCrsBuilder
import es.topophone.core.geodesy.catalog.CustomCrsDraft
import es.topophone.core.geodesy.catalog.CustomCrsMethods
import es.topophone.core.geodesy.datum.GridSource
import es.topophone.core.model.project.CUSTOM_EPSG
import es.topophone.core.model.project.CrsDefinition
import es.topophone.core.model.project.CustomCrs
import es.topophone.core.model.project.CustomHeight
import es.topophone.core.model.project.GeoBox
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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

/** Por qué no se importa un fichero JSON de SC (S035/RF-14). */
enum class CustomCrsRejection {
    /** No es un JSON de SC de TopoPhone, o le falta o le sobra algo: el contenido no se entiende. */
    INVALID,

    /** Es de una versión que esta app no conoce. */
    UNKNOWN_VERSION,

    /** Usa una proyección o una transformación que la app no calcula. */
    UNSUPPORTED_METHOD,

    /** Algún número está fuera de rango (S035/RF-6). */
    OUT_OF_RANGE,
}

sealed interface CustomCrsReadResult {
    /** El SC leído, sin identificador ni fechas (los pone quien lo guarda). */
    data class Ok(val crs: CustomCrs) : CustomCrsReadResult

    data class Rejected(val reason: CustomCrsRejection) : CustomCrsReadResult
}

/**
 * El JSON de un SC personalizado (S035/RF-12 a RF-14): un formato propio de TopoPhone, versionado, que lleva la definición completa. Los números salen
 * de `Double.toString`: con punto decimal, sin redondear y de ida y vuelta exacta, sea cual sea la configuración regional (RNF-T5, RNF-T9). Al leer
 * se valida el **contenido** (versión, método, rangos), no la extensión del fichero.
 */
object CustomCrsJson {
    const val FORMAT = "topophone-crs"
    const val VERSION = 1

    private val catalog get() = CrsCatalog.bundled

    fun write(crs: CustomCrs): String {
        val d = crs.definition
        return buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("name", crs.name)
            put(
                "ellipsoid",
                buildJsonObject {
                    put("semiMajorM", d.ellipsoidSemiMajorM)
                    put("inverseFlattening", d.ellipsoidInverseFlattening)
                },
            )
            put("primeMeridianDeg", d.primeMeridianDeg)
            put(
                "projection",
                buildJsonObject {
                    put("method", if (d.projected) d.method else CustomCrsDraft.GEOGRAPHIC)
                    put("parameters", buildJsonObject { d.parameters.forEach { (k, v) -> put(k, v) } })
                },
            )
            put("unitMetres", d.unitMetres)
            put(
                "area",
                d.area?.let {
                    JsonArray(listOf(it.southDeg, it.westDeg, it.northDeg, it.eastDeg).map(::JsonPrimitive))
                } ?: JsonNull,
            )
            put(
                "datum",
                buildJsonObject {
                    put("name", d.datumName)
                    put("frame", d.frame)
                    put(
                        "transformation",
                        buildJsonObject {
                            put("name", d.transformationName)
                            put("accuracyM", d.transformationAccuracyM?.let(::JsonPrimitive) ?: JsonNull)
                            put("steps", d.transformationSteps)
                        },
                    )
                },
            )
            put("height", heightJson(crs.height))
        }.toString()
    }

    private fun heightJson(height: CustomHeight): JsonObject = buildJsonObject {
        when (height) {
            CustomHeight.Ellipsoidal -> put("kind", "ellipsoidal")
            is CustomHeight.Geoid -> {
                put("kind", "geoid")
                put("grid", height.grid)
            }
            is CustomHeight.ConstantGeoid -> {
                put("kind", "constant")
                put("undulationM", height.undulationM)
            }
        }
    }

    fun read(text: String): CustomCrsReadResult = try {
        parse(text)
    } catch (_: RuntimeException) {
        // Un JSON dañado, un campo que falta o de otro tipo: nada se importa a medias.
        CustomCrsReadResult.Rejected(CustomCrsRejection.INVALID)
    }

    private fun parse(text: String): CustomCrsReadResult {
        val root = Json.parseToJsonElement(text).jsonObject
        if (root["format"]?.jsonPrimitive?.contentOrNull != FORMAT) return rejected(CustomCrsRejection.INVALID)
        val version = root["version"]?.jsonPrimitive?.intOrNull ?: return rejected(CustomCrsRejection.INVALID)
        if (version != VERSION) return rejected(CustomCrsRejection.UNKNOWN_VERSION)

        val projection = root.getValue("projection").jsonObject
        val method = projection.getValue("method").jsonPrimitive.content
        val keys = CustomCrsMethods.parameterKeys(method) ?: return rejected(CustomCrsRejection.UNSUPPORTED_METHOD)
        val parameters = projection.getValue("parameters").jsonObject.mapValues { it.value.number() }
        if (!parameters.keys.containsAll(keys) || !keys.containsAll(parameters.keys)) return rejected(CustomCrsRejection.INVALID)

        val ellipsoid = root.getValue("ellipsoid").jsonObject
        val datum = root.getValue("datum").jsonObject
        val transformation = datum.getValue("transformation").jsonObject
        val frame = datum.getValue("frame").jsonPrimitive.content
        if (frame != "ETRS89" && frame != "WGS84") return rejected(CustomCrsRejection.INVALID)
        val steps = transformation.getValue("steps").jsonPrimitive.content
        try {
            CrsCatalog.parseSteps(steps)
        } catch (_: RuntimeException) {
            return rejected(CustomCrsRejection.UNSUPPORTED_METHOD)
        }

        val name = root.getValue("name").jsonPrimitive.content
        val definition = CrsDefinition(
            epsg = CUSTOM_EPSG,
            name = name,
            projected = method != CustomCrsDraft.GEOGRAPHIC,
            datumCode = 0,
            datumName = datum.getValue("name").jsonPrimitive.content,
            ellipsoidSemiMajorM = ellipsoid.getValue("semiMajorM").number(),
            ellipsoidInverseFlattening = ellipsoid.getValue("inverseFlattening").number(),
            primeMeridianDeg = root.getValue("primeMeridianDeg").number(),
            method = method,
            parameters = keys.associateWith { parameters.getValue(it) },
            unitMetres = root.getValue("unitMetres").number(),
            area = root["area"]?.takeIf { it !is JsonNull }?.let(::boxOf),
            frame = frame,
            transformationName = transformation.getValue("name").jsonPrimitive.content,
            transformationAccuracyM = transformation["accuracyM"]?.takeIf { it !is JsonNull }?.number(),
            transformationArea = null,
            transformationSteps = steps,
        )
        val height = heightOf(root.getValue("height").jsonObject)
        return validated(CustomCrs("", name, definition, height))
    }

    private fun boxOf(element: JsonElement): GeoBox {
        val v = element.jsonArray.map { it.number() }
        require(v.size == 4)
        return GeoBox(v[0], v[1], v[2], v[3])
    }

    private fun heightOf(o: JsonObject): CustomHeight = when (o.getValue("kind").jsonPrimitive.content) {
        "ellipsoidal" -> CustomHeight.Ellipsoidal
        "geoid" -> CustomHeight.Geoid(o.getValue("grid").jsonPrimitive.content)
        "constant" -> CustomHeight.ConstantGeoid(o.getValue("undulationM").number())
        else -> throw IllegalArgumentException("Altura desconocida")
    }

    /** Un número de JSON, finito y escrito como número (no como texto). */
    private fun JsonElement.number(): Double {
        val p = jsonPrimitive
        require(!p.isString)
        return requireNotNull(p.doubleOrNull)
    }

    /** Pasa el SC por las mismas reglas que el editor (S035/RF-6): rangos, rejillas conocidas y transformación que se pueda construir. */
    private fun validated(crs: CustomCrs): CustomCrsReadResult {
        val draft = CustomCrsDraft.of(crs).copy(height = crs.height)
        if (CustomCrsBuilder.build(draft, catalog) is CustomBuildResult.Invalid) return rejected(CustomCrsRejection.OUT_OF_RANGE)
        val buildable = try {
            CrsDefinitions.resolve(crs.definition, GridSource { null })
        } catch (_: RuntimeException) {
            null
        }
        return if (buildable == null) rejected(CustomCrsRejection.OUT_OF_RANGE) else CustomCrsReadResult.Ok(crs)
    }

    private fun rejected(reason: CustomCrsRejection) = CustomCrsReadResult.Rejected(reason)
}
