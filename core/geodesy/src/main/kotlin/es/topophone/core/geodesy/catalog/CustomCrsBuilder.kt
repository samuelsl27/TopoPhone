package es.topophone.core.geodesy.catalog

import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.projection.ProblemKind
import es.topophone.core.geodesy.projection.ProjectionFactory
import es.topophone.core.geodesy.projection.ProjectionParameter
import es.topophone.core.geodesy.projection.ProjectionResult
import es.topophone.core.geodesy.projection.ProjectionSetup
import es.topophone.core.model.project.CUSTOM_EPSG
import es.topophone.core.model.project.CrsDefinition
import es.topophone.core.model.project.CustomHeight
import kotlin.math.abs

/** Por qué falla un campo del editor de SC personalizados (S035/RF-6); la interfaz lo traduce a un texto de `strings.xml`. */
enum class CustomError {
    /** Falta el valor. */
    REQUIRED,
    NOT_FINITE,
    NOT_POSITIVE,
    OUT_OF_RANGE,
    AT_POLE,
    AT_EQUATOR,
    NOT_A_POLE,
    OPPOSITE_PARALLELS,
    WRONG_HEMISPHERE,
    UNSUPPORTED_METHOD,

    /** La rejilla (de datum o de geoide) no está en el catálogo de rejillas. */
    UNKNOWN_GRID,

    /** El valor no se entiende (una cadena de transformación mal formada). */
    INVALID,

    /** La proyección no se puede construir con esos parámetros aunque cada uno sea válido por separado. */
    PROJECTION_FAILED,
}

/** Las claves de los campos del editor, para marcar el que falla (S035/RF-6). */
object CustomFields {
    const val NAME = "name"
    const val SEMI_MAJOR_AXIS = "semiMajorAxis"
    const val INVERSE_FLATTENING = "inverseFlattening"
    const val PRIME_MERIDIAN = "primeMeridian"
    const val METHOD = "method"
    const val TRANSFORMATION = "transformation"
    const val GRID = "grid"
    const val HEIGHT = "height"
    const val ACCURACY = "accuracy"

    fun parameter(key: String) = "param:$key"

    fun helmert(key: String) = "helmert:$key"

    fun pivot(axis: String) = "pivot:$axis"
}

/** Los métodos de proyección que ofrece el editor y los parámetros de cada uno, con los nombres de la biblioteca (S035/RF-4). */
object CustomCrsMethods {
    private val TM = listOf("latitudeOfOriginDeg", "longitudeOfOriginDeg", "scaleFactor", "falseEasting", "falseNorthing")
    private val OM_A = listOf(
        "latitudeOfCentreDeg",
        "longitudeOfCentreDeg",
        "azimuthDeg",
        "rectifiedGridAngleDeg",
        "scaleFactor",
        "falseEasting",
        "falseNorthing",
    )

    private val keys: Map<String, List<String>> = mapOf(
        "tm" to TM,
        "lcc1" to TM,
        "lcc2" to listOf(
            "latitudeOfFalseOriginDeg",
            "longitudeOfFalseOriginDeg",
            "standardParallel1Deg",
            "standardParallel2Deg",
            "eastingAtFalseOrigin",
            "northingAtFalseOrigin",
        ),
        "omerca" to OM_A,
        "omercb" to OM_A.dropLast(2) + listOf("eastingAtCentre", "northingAtCentre"),
        "stereo" to TM,
        "polara" to TM,
        "polarb" to listOf("latitudeOfStandardParallelDeg", "longitudeOfOriginDeg", "falseEasting", "falseNorthing"),
        "polarc" to listOf("latitudeOfStandardParallelDeg", "longitudeOfOriginDeg", "eastingAtFalseOrigin", "northingAtFalseOrigin"),
        "merca" to listOf("longitudeOfOriginDeg", "scaleFactor", "falseEasting", "falseNorthing"),
        "mercb" to listOf("longitudeOfOriginDeg", "standardParallelDeg", "falseEasting", "falseNorthing"),
    )

    /** Los que se ofrecen al crear un SC desde cero (S035/RF-4); los otros solo se conservan si se parte de un SC de la biblioteca que los usa. */
    val offered: List<String> = listOf("tm", "lcc1", "lcc2", "omerca", "omercb")

    /** Los parámetros de [method] en el orden del editor, o `null` si no es un método conocido. `geo` no tiene ninguno. */
    fun parameterKeys(method: String): List<String>? = if (method == CustomCrsDraft.GEOGRAPHIC) emptyList() else keys[method]

    /** Del parámetro que da S032 a la clave del campo del editor, según el método. */
    internal fun keyOf(method: String, parameter: ProjectionParameter): String? = when (parameter) {
        ProjectionParameter.LATITUDE_OF_ORIGIN -> if (method == "lcc2") "latitudeOfFalseOriginDeg" else "latitudeOfOriginDeg"
        ProjectionParameter.LONGITUDE_OF_ORIGIN -> if (method == "lcc2") "longitudeOfFalseOriginDeg" else "longitudeOfOriginDeg"
        ProjectionParameter.LATITUDE_OF_CENTRE -> "latitudeOfCentreDeg"
        ProjectionParameter.LONGITUDE_OF_CENTRE -> "longitudeOfCentreDeg"
        ProjectionParameter.STANDARD_PARALLEL -> if (method == "mercb") "standardParallelDeg" else "latitudeOfStandardParallelDeg"
        ProjectionParameter.STANDARD_PARALLEL_1 -> "standardParallel1Deg"
        ProjectionParameter.STANDARD_PARALLEL_2 -> "standardParallel2Deg"
        ProjectionParameter.AZIMUTH -> "azimuthDeg"
        ProjectionParameter.RECTIFIED_GRID_ANGLE -> "rectifiedGridAngleDeg"
        ProjectionParameter.SCALE_FACTOR -> "scaleFactor"
        ProjectionParameter.FALSE_EASTING -> when (method) {
            "lcc2", "polarc" -> "eastingAtFalseOrigin"
            "omercb" -> "eastingAtCentre"
            else -> "falseEasting"
        }
        ProjectionParameter.FALSE_NORTHING -> when (method) {
            "lcc2", "polarc" -> "northingAtFalseOrigin"
            "omercb" -> "northingAtCentre"
            else -> "falseNorthing"
        }
        ProjectionParameter.METHOD -> null
    }
}

/** Lo que da construir un SC personalizado (S035/RF-8). */
sealed interface CustomBuildResult {
    /** El SC se puede usar ya. */
    data class Ready(val definition: CrsDefinition) : CustomBuildResult

    /** El SC se puede usar cuando se descarguen [grids]. */
    data class NeedsGrids(val definition: CrsDefinition, val grids: List<GridEntry>) : CustomBuildResult

    /** El SC no se puede guardar: [errors] por campo. */
    data class Invalid(val errors: Map<String, CustomError>) : CustomBuildResult
}

/**
 * Valida y construye un SC personalizado a partir del borrador del editor (S035/RF-2 a RF-6, RF-8). La validación da **un** error por campo; con
 * el mapa vacío el SC se puede guardar.
 */
object CustomCrsBuilder {
    private const val MAX_TRANSLATION_M = 1.0e6
    private const val MAX_ROTATION_ARC_SEC = 3600.0
    private const val MAX_SCALE_PPM = 1.0e6
    private const val MAX_UNDULATION_M = 500.0
    private const val MIN_INVERSE_FLATTENING = 1.0
    private const val DEFAULT_DATUM_NAME = "Personalizado"
    private const val GRS80_TEXT = "6378137.0,298.257222101"
    private const val WGS84_TEXT = "6378137.0,298.257223563"

    /**
     * El SC que se guardaría con [draft], o por qué no se puede: `Ready` si se puede usar ya, `NeedsGrids` si faltan rejillas por descargar (la de
     * datum o la del geoide; S035/RF-8) o `Invalid` con los errores de [validate]. [isInstalled] dice si una rejilla descargada está instalada.
     */
    fun build(draft: CustomCrsDraft, catalog: CrsCatalog, isInstalled: (String) -> Boolean = { false }): CustomBuildResult {
        val errors = validate(draft, catalog)
        if (errors.isNotEmpty()) return CustomBuildResult.Invalid(errors)
        val (name, steps) = transformationOf(draft)
        val projected = draft.method != CustomCrsDraft.GEOGRAPHIC
        val keys = CustomCrsMethods.parameterKeys(draft.method).orEmpty()
        val definition = CrsDefinition(
            epsg = CUSTOM_EPSG,
            name = draft.name.trim(),
            projected = projected,
            datumCode = 0,
            datumName = draft.datumName.trim().ifEmpty { DEFAULT_DATUM_NAME },
            ellipsoidSemiMajorM = draft.semiMajorAxisM!!,
            ellipsoidInverseFlattening = draft.inverseFlattening!!,
            primeMeridianDeg = draft.primeMeridianDeg,
            method = if (projected) draft.method else CustomCrsDraft.GEOGRAPHIC,
            parameters = if (projected) keys.associateWith { draft.parameters.getValue(it)!! } else emptyMap(),
            unitMetres = draft.unit.metres,
            area = draft.area,
            frame = if (draft.frame == CorrectionFrame.WGS84) "WGS84" else "ETRS89",
            transformationName = name,
            transformationAccuracyM = draft.transformationAccuracyM,
            transformationArea = null,
            transformationSteps = steps,
        )
        val missing = CrsCatalog.parseSteps(steps).filterIsInstance<StepSpec.Grid>().map { it.name } +
            listOfNotNull((draft.height as? CustomHeight.Geoid)?.grid)
        val grids = missing.distinct().mapNotNull { catalog.grids[it]?.takeUnless { grid -> grid.bundled || isInstalled(grid.name) } }
        return if (grids.isEmpty()) CustomBuildResult.Ready(definition) else CustomBuildResult.NeedsGrids(definition, grids)
    }

    private fun transformationOf(draft: CustomCrsDraft): Pair<String, String> {
        val target = if (draft.frame == CorrectionFrame.WGS84) WGS84_TEXT else GRS80_TEXT
        val source = "${draft.semiMajorAxisM},${draft.inverseFlattening}"
        fun helmert(v: HelmertValues, convention: RotationConvention, kind: HelmertKind, pivot: String?): String {
            val (rx, ry, rz) = if (kind == HelmertKind.ROTATIONS_6 ||
                kind == HelmertKind.SEVEN
            ) {
                Triple(v.rx, v.ry, v.rz)
            } else {
                Triple(0.0, 0.0, 0.0)
            }
            val s = if (kind == HelmertKind.TRANSLATIONS_SCALE_4 || kind == HelmertKind.SEVEN) v.scalePpm else 0.0
            val base = "H;A=$source;B=$target;inv=0;conv=${convention.code};p=${v.tx},${v.ty},${v.tz},$rx,$ry,$rz,$s"
            return if (pivot == null) base else "$base;px=$pivot"
        }
        return when (val t = draft.transformation) {
            CustomTransformation.None -> "No datum shift" to "null"
            is CustomTransformation.Helmert -> {
                val count = when (t.kind) {
                    HelmertKind.TRANSLATIONS_3 -> 3
                    HelmertKind.TRANSLATIONS_SCALE_4 -> 4
                    HelmertKind.ROTATIONS_6 -> 6
                    HelmertKind.SEVEN -> 7
                }
                "Custom Helmert $count-parameter (${t.convention.code})" to helmert(t.values, t.convention, t.kind, null)
            }
            is CustomTransformation.MolodenskyBadekas ->
                "Custom Molodensky-Badekas 10-parameter (${t.convention.code})" to
                    helmert(t.values, t.convention, HelmertKind.SEVEN, "${t.pivotX},${t.pivotY},${t.pivotZ}")
            is CustomTransformation.Grid -> "Custom grid shift ${t.name}" to "G;grid=${t.name};inv=0"
            is CustomTransformation.Kept -> t.name to t.stepsText
        }
    }

    /** Los errores del borrador por campo (clave de [CustomFields]); vacío si es válido. */
    fun validate(draft: CustomCrsDraft, catalog: CrsCatalog): Map<String, CustomError> {
        val errors = linkedMapOf<String, CustomError>()
        if (draft.name.isBlank()) errors[CustomFields.NAME] = CustomError.REQUIRED
        ellipsoid(draft, errors)
        draft.primeMeridianDeg.let {
            if (!it.isFinite()) {
                errors[CustomFields.PRIME_MERIDIAN] = CustomError.NOT_FINITE
            } else if (abs(it) > 180.0) {
                errors[CustomFields.PRIME_MERIDIAN] = CustomError.OUT_OF_RANGE
            }
        }
        projection(draft, errors)
        transformation(draft, catalog, errors)
        draft.transformationAccuracyM?.let {
            if (!it.isFinite()) {
                errors[CustomFields.ACCURACY] = CustomError.NOT_FINITE
            } else if (it < 0.0) {
                errors[CustomFields.ACCURACY] = CustomError.NOT_POSITIVE
            }
        }
        height(draft.height, catalog, errors)
        return errors
    }

    private fun ellipsoid(draft: CustomCrsDraft, errors: MutableMap<String, CustomError>) {
        val a = draft.semiMajorAxisM
        when {
            a == null -> errors[CustomFields.SEMI_MAJOR_AXIS] = CustomError.REQUIRED
            !a.isFinite() -> errors[CustomFields.SEMI_MAJOR_AXIS] = CustomError.NOT_FINITE
            a <= 0.0 -> errors[CustomFields.SEMI_MAJOR_AXIS] = CustomError.NOT_POSITIVE
        }
        val f = draft.inverseFlattening
        when {
            f == null -> errors[CustomFields.INVERSE_FLATTENING] = CustomError.REQUIRED
            !f.isFinite() -> errors[CustomFields.INVERSE_FLATTENING] = CustomError.NOT_FINITE
            f != 0.0 && f <= MIN_INVERSE_FLATTENING -> errors[CustomFields.INVERSE_FLATTENING] = CustomError.OUT_OF_RANGE
        }
    }

    private fun projection(draft: CustomCrsDraft, errors: MutableMap<String, CustomError>) {
        val method = draft.method
        val keys = CustomCrsMethods.parameterKeys(method)
        if (keys == null) {
            errors[CustomFields.METHOD] = CustomError.UNSUPPORTED_METHOD
            return
        }
        if (keys.isEmpty()) return
        val missing = keys.filter { draft.parameters[it] == null }
        missing.forEach { errors[CustomFields.parameter(it)] = CustomError.REQUIRED }
        if (method == "tm") {
            // La Transversa de Mercator se valida con lo que ya hay escrito, para marcar a la vez todo lo que falla.
            tmProblems(draft.parameters).forEach { (key, error) -> errors[CustomFields.parameter(key)] = error }
            return
        }
        if (missing.isNotEmpty()) return
        val values = keys.associateWith { draft.parameters.getValue(it)!! }
        val parameters = CrsBuilder.parameters(method, values)
        if (parameters == null) {
            errors[CustomFields.METHOD] = CustomError.UNSUPPORTED_METHOD
            return
        }
        // Los problemas de los parámetros no dependen del elipsoide: si este falla se usa GRS 1980 solo para llegar a ellos.
        val ellipsoid = draft.ellipsoidOrNull() ?: Ellipsoid.GRS80
        val result = try {
            ProjectionFactory.create(ProjectionSetup(ellipsoid, parameters, draft.unit, 0.0))
        } catch (_: IllegalArgumentException) {
            errors[CustomFields.METHOD] = CustomError.PROJECTION_FAILED
            return
        }
        if (result is ProjectionResult.Invalid) {
            result.problems.forEach { problem ->
                val key = CustomCrsMethods.keyOf(method, problem.parameter)
                if (key != null) errors.putIfAbsent(CustomFields.parameter(key), problem.kind.toCustom())
            }
        }
    }

    /** La Transversa de Mercator no pasa por `ProjectionFactory`: sus reglas son las de latitud, longitud, escala y falsos finitos. */
    private fun tmProblems(v: Map<String, Double?>): Map<String, CustomError> = buildMap {
        fun angle(key: String, limit: Double) {
            val x = v[key] ?: return
            if (!x.isFinite()) {
                put(key, CustomError.NOT_FINITE)
            } else if (abs(x) > limit) {
                put(key, CustomError.OUT_OF_RANGE)
            }
        }
        angle("latitudeOfOriginDeg", 90.0)
        angle("longitudeOfOriginDeg", 180.0)
        val scale = v["scaleFactor"]
        if (scale != null && !scale.isFinite()) {
            put("scaleFactor", CustomError.NOT_FINITE)
        } else if (scale != null && scale <= 0.0) {
            put("scaleFactor", CustomError.NOT_POSITIVE)
        }
        listOf("falseEasting", "falseNorthing").forEach { if (v[it]?.isFinite() == false) put(it, CustomError.NOT_FINITE) }
    }

    private fun ProblemKind.toCustom(): CustomError = when (this) {
        ProblemKind.UNSUPPORTED_METHOD -> CustomError.UNSUPPORTED_METHOD
        ProblemKind.NOT_FINITE -> CustomError.NOT_FINITE
        ProblemKind.OUT_OF_RANGE -> CustomError.OUT_OF_RANGE
        ProblemKind.AT_POLE -> CustomError.AT_POLE
        ProblemKind.AT_EQUATOR -> CustomError.AT_EQUATOR
        ProblemKind.NOT_A_POLE -> CustomError.NOT_A_POLE
        ProblemKind.OPPOSITE_PARALLELS -> CustomError.OPPOSITE_PARALLELS
        ProblemKind.NOT_POSITIVE -> CustomError.NOT_POSITIVE
        ProblemKind.WRONG_HEMISPHERE -> CustomError.WRONG_HEMISPHERE
    }

    private fun CustomCrsDraft.ellipsoidOrNull(): Ellipsoid? {
        val a = semiMajorAxisM ?: return null
        val f = inverseFlattening ?: return null
        if (!a.isFinite() || a <= 0.0 || !f.isFinite() || (f != 0.0 && f <= MIN_INVERSE_FLATTENING)) return null
        return Ellipsoid(a, if (f == 0.0) Double.POSITIVE_INFINITY else f)
    }

    private fun transformation(draft: CustomCrsDraft, catalog: CrsCatalog, errors: MutableMap<String, CustomError>) {
        when (val t = draft.transformation) {
            CustomTransformation.None -> Unit
            is CustomTransformation.Helmert -> helmert(t.kind, t.values, errors)
            is CustomTransformation.MolodenskyBadekas -> {
                helmert(HelmertKind.SEVEN, t.values, errors)
                listOf("x" to t.pivotX, "y" to t.pivotY, "z" to t.pivotZ).forEach { (axis, value) ->
                    if (!value.isFinite()) errors[CustomFields.pivot(axis)] = CustomError.NOT_FINITE
                }
            }
            is CustomTransformation.Grid ->
                if (catalog.grids[t.name]?.kind != GridKind.HORIZONTAL) errors[CustomFields.GRID] = CustomError.UNKNOWN_GRID
            is CustomTransformation.Kept ->
                try {
                    CrsCatalog.parseSteps(t.stepsText)
                } catch (_: RuntimeException) {
                    errors[CustomFields.TRANSFORMATION] = CustomError.INVALID
                }
        }
    }

    private fun helmert(kind: HelmertKind, v: HelmertValues, errors: MutableMap<String, CustomError>) {
        val used = buildList {
            add(Triple("tx", v.tx, MAX_TRANSLATION_M))
            add(Triple("ty", v.ty, MAX_TRANSLATION_M))
            add(Triple("tz", v.tz, MAX_TRANSLATION_M))
            if (kind == HelmertKind.ROTATIONS_6 || kind == HelmertKind.SEVEN) {
                add(Triple("rx", v.rx, MAX_ROTATION_ARC_SEC))
                add(Triple("ry", v.ry, MAX_ROTATION_ARC_SEC))
                add(Triple("rz", v.rz, MAX_ROTATION_ARC_SEC))
            }
            if (kind == HelmertKind.TRANSLATIONS_SCALE_4 || kind == HelmertKind.SEVEN) add(Triple("scalePpm", v.scalePpm, MAX_SCALE_PPM))
        }
        used.forEach { (key, value, limit) ->
            if (!value.isFinite()) {
                errors[CustomFields.helmert(key)] = CustomError.NOT_FINITE
            } else if (abs(value) > limit || (key == "scalePpm" && abs(value) >= limit)) {
                errors[CustomFields.helmert(key)] = CustomError.OUT_OF_RANGE
            }
        }
    }

    private fun height(height: CustomHeight, catalog: CrsCatalog, errors: MutableMap<String, CustomError>) {
        when (height) {
            CustomHeight.Ellipsoidal -> Unit
            is CustomHeight.Geoid ->
                if (catalog.grids[height.grid]?.kind != GridKind.GEOID) errors[CustomFields.HEIGHT] = CustomError.UNKNOWN_GRID
            is CustomHeight.ConstantGeoid -> {
                val n = height.undulationM
                if (!n.isFinite()) {
                    errors[CustomFields.HEIGHT] = CustomError.NOT_FINITE
                } else if (abs(n) > MAX_UNDULATION_M) {
                    errors[CustomFields.HEIGHT] = CustomError.OUT_OF_RANGE
                }
            }
        }
    }
}
