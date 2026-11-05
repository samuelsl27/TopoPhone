package es.topophone.core.survey.geometry

import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.ProjectPositionCalculator

/**
 * Distancias de terreno: la de cuadrícula entre el factor combinado de S008 (S026/RF-10). Cada segmento usa el factor de su centro
 * (el punto medio de las posiciones de sus extremos, con la media de sus alturas elipsoidales) y el área, el del centro del
 * polígono (la media de todos sus vértices).
 *
 * Si a alguno le falta la altura elipsoidal se usa [fallbackHeightM], la del punto central del proyecto; si tampoco la hay, el
 * factor de escala sin corrección de altura, con aviso. Sin factor (SC geográfico, vértice sin posición o fuera del área del SC)
 * se usa 1, con aviso. Una instancia sirve para un solo cálculo: acumula los avisos.
 */
class GroundScale(
    private val crs: ProjectCrs,
    private val fallbackHeightM: Double?,
    private val calculator: ProjectPositionCalculator = ProjectPositionCalculator(),
) : SegmentScale {
    override val mode = DistanceMode.GROUND

    private val found = mutableSetOf<GeometryNotice>()

    override val notices: Set<GeometryNotice> get() = found.toSet()

    override fun segment(a: GeometryVertex, b: GeometryVertex): Double = factorAt(listOf(a, b))

    override fun polygon(vertices: List<GeometryVertex>): Double = factorAt(vertices)

    private fun factorAt(points: List<GeometryVertex>): Double {
        if (crs.utmZone == null || points.any { it.latitudeDeg == null || it.longitudeDeg == null }) {
            found += GeometryNotice.NO_FACTOR
            return 1.0
        }
        val lat = points.sumOf { it.latitudeDeg!! } / points.size
        val lon = points.sumOf { it.longitudeDeg!! } / points.size
        val heights = points.map { it.ellipsoidalHeightM }
        val height = if (heights.all { it != null }) heights.sumOf { it!! } / heights.size else fallbackHeightM
        val position = calculator.fromGround(GeodeticPosition(lat, lon, height), crs, HeightSystem.ELLIPSOIDAL)
        position.combinedFactor?.let { return it }
        val scale = position.scaleFactor
        if (scale == null) {
            found += GeometryNotice.NO_FACTOR
            return 1.0
        }
        found += GeometryNotice.FACTOR_WITHOUT_HEIGHT
        return scale
    }
}
