package es.topophone.core.survey

import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.TerrainPlane
import kotlin.math.abs

/** Los campos del plano de terreno que pueden fallar (S058/RF-4, RF-5). */
enum class TerrainField { ORIGIN, FACTOR, CRS }

/** Por qué falla un campo del plano de terreno; la interfaz lo traduce a un texto de `strings.xml`. */
enum class TerrainError {
    /** Falta el valor. */
    REQUIRED,
    NOT_FINITE,

    /** El factor no está entre [TerrainPlaneValidator.MIN_FACTOR] y [TerrainPlaneValidator.MAX_FACTOR], o el origen no es una latitud y una longitud. */
    OUT_OF_RANGE,

    /** El origen cae fuera del ámbito del SC del proyecto (S006/RF-30). */
    OUT_OF_AREA,

    /** El SC del proyecto es geográfico: no hay coordenadas de terreno (S058/RF-2). */
    NOT_PROJECTED,
}

/** Valida el plano de terreno que se escribe en el formulario del proyecto (S058/RF-2, RF-4, RF-5). */
object TerrainPlaneValidator {
    const val MIN_FACTOR = 0.9
    const val MAX_FACTOR = 1.1

    /** Los errores de [originLatitudeDeg], [originLongitudeDeg] y [factor] en el SC [crs], por campo; vacío si el plano vale. */
    fun validate(
        originLatitudeDeg: Double?,
        originLongitudeDeg: Double?,
        factor: Double?,
        crs: ProjectCrs,
        calculator: ProjectPositionCalculator = ProjectPositionCalculator(),
    ): Map<TerrainField, TerrainError> {
        val errors = linkedMapOf<TerrainField, TerrainError>()
        if (!crs.isProjected) errors[TerrainField.CRS] = TerrainError.NOT_PROJECTED
        when {
            factor == null -> errors[TerrainField.FACTOR] = TerrainError.REQUIRED
            !factor.isFinite() -> errors[TerrainField.FACTOR] = TerrainError.NOT_FINITE
            factor < MIN_FACTOR || factor > MAX_FACTOR -> errors[TerrainField.FACTOR] = TerrainError.OUT_OF_RANGE
        }
        when {
            originLatitudeDeg == null || originLongitudeDeg == null -> errors[TerrainField.ORIGIN] = TerrainError.REQUIRED
            !originLatitudeDeg.isFinite() || !originLongitudeDeg.isFinite() -> errors[TerrainField.ORIGIN] = TerrainError.NOT_FINITE
            abs(originLatitudeDeg) > 90.0 || abs(originLongitudeDeg) > 180.0 -> errors[TerrainField.ORIGIN] = TerrainError.OUT_OF_RANGE
            crs.isProjected && TerrainFrame.of(TerrainPlane(originLatitudeDeg, originLongitudeDeg, 1.0), crs, calculator) == null ->
                errors[TerrainField.ORIGIN] = TerrainError.OUT_OF_AREA
        }
        return errors
    }
}

/** El factor que se propone para un plano de terreno (S058/RF-3). */
object TerrainProposal {

    /**
     * El factor combinado en el origen (S008/RF-15): el factor de escala de la proyección por el de altura, con la altura elipsoidal del origen
     * [ellipsoidalHeightM] (0 si no se conoce). `null` si el SC es geográfico o el origen no se puede calcular en él.
     */
    fun factorAt(
        originLatitudeDeg: Double,
        originLongitudeDeg: Double,
        ellipsoidalHeightM: Double?,
        crs: ProjectCrs,
        calculator: ProjectPositionCalculator = ProjectPositionCalculator(),
    ): Double? {
        if (!crs.isProjected) return null
        val ground = GeodeticPosition(originLatitudeDeg, originLongitudeDeg, ellipsoidalHeightM ?: 0.0)
        return calculator.fromGround(ground, crs, HeightSystem.ELLIPSOIDAL).combinedFactor
    }
}
