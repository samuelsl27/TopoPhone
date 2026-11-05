package es.topophone.core.survey.geometry

import es.topophone.core.model.geometry.DistanceMode

/**
 * Un vértice de la información geométrica (S026): su posición en el plano del SC, su altura del proyecto y su posición geodésica.
 * Todo es opcional; qué hace falta depende de lo que se calcule (Este y Norte, o la posición geodésica en un SC geográfico; la
 * altura elipsoidal, para el factor combinado).
 */
data class GeometryVertex(
    val easting: Double? = null,
    val northing: Double? = null,
    val height: Double? = null,
    val latitudeDeg: Double? = null,
    val longitudeDeg: Double? = null,
    val ellipsoidalHeightM: Double? = null,
)

/** Un segmento de la tabla (S026/RF-2): longitudes en metros del modo activo, azimut en radianes y pendiente en %. */
data class SegmentInfo(
    val index: Int,
    val from: Int,
    val to: Int,
    val chainage: Double,
    val length2D: Double,
    val length3D: Double?,
    val azimuthRad: Double,
    val deltaHeight: Double?,
    val slopePercent: Double?,
)

/** Avisos de la información (S026/RF-6, RF-10, RF-11). */
enum class GeometryNotice {
    LOCAL_PLANE,
    NO_FACTOR,
    FACTOR_WITHOUT_HEIGHT,
}

/** La información de una línea o un polígono (S026/RF-1 a RF-3, RF-6). */
data class GeometryInfo(
    val mode: DistanceMode,
    val closed: Boolean,
    val vertexCount: Int,
    val segmentCount: Int,
    val length2D: Double,
    val length3D: Double?,
    val perimeter: Double?,
    val area: Double?,
    val crosses: Boolean,
    val missingHeightSegments: List<Int>,
    val notices: Set<GeometryNotice>,
    val segments: List<SegmentInfo>,
)

sealed interface GeometryResult {
    data class Info(val info: GeometryInfo) : GeometryResult

    /** Menos de 2 vértices, o menos de 3 en un polígono (S026/RF-7). */
    data object TooFewVertices : GeometryResult

    /** Algún vértice no tiene ni Este y Norte ni posición geodésica (S026/RF-11). */
    data object NoPosition : GeometryResult
}

/**
 * El factor por el que se dividen las distancias de cuadrícula (S026/RF-9, RF-10): 1 en cuadrícula; el combinado de S008 en terreno.
 */
interface SegmentScale {
    val mode: DistanceMode

    /** El factor del segmento entre [a] y [b], calculado en su centro. */
    fun segment(a: GeometryVertex, b: GeometryVertex): Double

    /** El factor del área, calculado en el centro del polígono. */
    fun polygon(vertices: List<GeometryVertex>): Double

    /** Los avisos que produjo el cálculo hecho con este objeto. */
    val notices: Set<GeometryNotice> get() = emptySet()

    data object Grid : SegmentScale {
        override val mode = DistanceMode.GRID

        override fun segment(a: GeometryVertex, b: GeometryVertex) = 1.0

        override fun polygon(vertices: List<GeometryVertex>) = 1.0
    }
}
