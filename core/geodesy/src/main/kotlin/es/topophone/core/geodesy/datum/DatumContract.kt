package es.topophone.core.geodesy.datum

import es.topophone.core.geodesy.GeodeticPoint

/** Zona donde una transformación declara ser válida: rectángulo en geográficas, en grados (S033/RF-14, RF-15). */
data class AreaOfUse(val southDeg: Double, val northDeg: Double, val westDeg: Double, val eastDeg: Double) {
    fun contains(latitudeDeg: Double, longitudeDeg: Double): Boolean = latitudeDeg in southDeg..northDeg && longitudeDeg in westDeg..eastDeg
}

/** Cómo se describe una transformación en la ficha del SC (S033/RF-14): nombre, código EPSG, precisión declarada en metros y ámbito. */
data class TransformationInfo(val name: String, val epsgCode: Int? = null, val accuracyM: Double? = null, val areaOfUse: AreaOfUse? = null)

/** Avisos que acompañan a una coordenada calculada. */
enum class TransformWarning {
    /** El punto está fuera del ámbito declarado de una transformación de parámetros; se calcula igualmente (S033/RF-15). */
    OUTSIDE_AREA_OF_USE,
}

/**
 * Resultado de transformar un punto. Un dato fuera de dominio nunca lanza una excepción (ADR-5): el tipo cerrado obliga a quien llama a
 * decidir y dice por qué se rechaza (S033/RF-8, RF-9, RF-10, RF-12).
 */
sealed interface TransformResult {
    data class Ok(val point: GeodeticPoint, val warnings: List<TransformWarning> = emptyList()) : TransformResult

    /** El punto queda fuera de la rejilla; no se extrapola (S033/RF-8). */
    data class OutsideGrid(val gridName: String?) : TransformResult

    /** La rejilla no está instalada o no se puede leer; no se calcula sin ella (S033/RF-9, RF-12). */
    data class GridUnavailable(val gridName: String) : TransformResult

    /** La inversa de la rejilla no converge a ≤ 0,1 mm, o la entrada no es un número finito (S033/RF-10). */
    data class NotConverged(val gridName: String?) : TransformResult
}

/** Una transformación entre dos datums, en geográficas 3D (grados y metros), aplicable en los dos sentidos (S033/RF-5). */
interface DatumTransformation {
    val info: TransformationInfo

    fun forward(point: GeodeticPoint): TransformResult

    fun inverse(point: GeodeticPoint): TransformResult
}

/** Qué parámetro de una transformación tiene un problema. */
enum class DatumParameter {
    TRANSLATION_X,
    TRANSLATION_Y,
    TRANSLATION_Z,
    ROTATION_X,
    ROTATION_Y,
    ROTATION_Z,
    SCALE,
    PIVOT_X,
    PIVOT_Y,
    PIVOT_Z,
}

enum class ProblemKind {
    /** El valor es NaN o infinito (S033/RF-6). */
    NOT_FINITE,
}

data class ParameterProblem(val parameter: DatumParameter, val kind: ProblemKind)
