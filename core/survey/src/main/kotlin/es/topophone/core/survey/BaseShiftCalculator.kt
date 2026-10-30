package es.topophone.core.survey

import es.topophone.core.geodesy.Ecef
import es.topophone.core.geodesy.GeodeticPoint
import es.topophone.core.geodesy.LocalTangentPlane
import es.topophone.core.model.point.BaseShiftClass
import es.topophone.core.model.point.BaseShiftLimits
import es.topophone.core.model.point.BaseShiftVector
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.TypedCoordinates
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs

/**
 * El punto cuyas coordenadas se dan por buenas (S020, «Punto conocido»): Este y Norte en el SC del proyecto (latitud
 * y longitud en grados si el SC es geográfico) y la altura en el sistema de alturas del proyecto, o null si no la tiene.
 */
data class KnownPoint(val easting: Double, val northing: Double, val heightM: Double?)

/** El resultado de calcular un desplazamiento de base (S020/RF-3 a RF-6). */
sealed interface BaseShiftResult {
    /**
     * @property vector ΔX, ΔY, ΔZ en metros.
     * @property deltaEastM Este, [deltaNorthM] Norte y [deltaHeightM] altura del vector en el SC del proyecto, en metros.
     * @property horizontalOnly el vector no corrige la altura (RF-6).
     * @property classification qué hace la app según el módulo (RF-5).
     */
    data class Computed(
        val vector: BaseShiftVector,
        val deltaEastM: Double,
        val deltaNorthM: Double,
        val deltaHeightM: Double,
        val horizontalOnly: Boolean,
        val classification: BaseShiftClass,
    ) : BaseShiftResult

    data class Rejected(val reason: PositionUnavailable) : BaseShiftResult
}

/**
 * Del punto conocido y la posición medida al desplazamiento de base (S020/RF-3 a RF-6):
 * 1. el conocido pasa del SC del proyecto a geográficas y a ECEF, en el datum del SC ([ManualCoordinates]); la
 *    medida, tal como la dio el receptor, en el datum de las correcciones, **sin transformar entre ambos** (RF-3);
 * 2. con altura, Δ = conocido − medido;
 * 3. sin altura y si se acepta, el conocido se coloca a la altura de la medida, se toman su Este y su Norte en el
 *    plano tangente local de la medida y Δ es ese (Este, Norte, 0) devuelto a ECEF (RF-6);
 * 4. el equivalente en el SC es la diferencia entre la medida con Δ y sin él, proyectadas (RF-4), o en el plano local
 *    de la medida si el SC no las da.
 */
class BaseShiftCalculator(
    private val manual: ManualCoordinates = ManualCoordinates(),
    private val positions: ProjectPositionCalculator = ProjectPositionCalculator(),
) {
    fun compute(
        known: KnownPoint,
        measured: GeodeticPosition,
        crs: ProjectCrs,
        heightSystem: HeightSystem,
        acceptHorizontalOnly: Boolean,
    ): BaseShiftResult {
        val measuredHeight = measured.ellipsoidalHeightM ?: return BaseShiftResult.Rejected(PositionUnavailable.NO_HEIGHT)
        if (known.heightM == null && !acceptHorizontalOnly) return BaseShiftResult.Rejected(PositionUnavailable.NO_HEIGHT)

        val typed = TypedCoordinates(
            form = if (crs.isUtm) EntryForm.PROJECT_CRS else EntryForm.GEOGRAPHIC,
            first = known.easting,
            second = known.northing,
            height = known.heightM,
            heightKind = HeightKind.PROJECT,
        )
        val ground = when (val result = manual.compute(typed, crs, heightSystem)) {
            is ManualResult.Rejected -> return BaseShiftResult.Rejected(result.reason)
            is ManualResult.Computed -> result.coordinates.ground
        }

        val measuredPoint = GeodeticPoint(measured.latitudeDeg, measured.longitudeDeg, measuredHeight)
        val measuredEcef = Ecef.fromGeodetic(measuredPoint.latitudeDeg, measuredPoint.longitudeDeg, measuredHeight)
        val knownHeight = ground.ellipsoidalHeightM
        val vector = if (knownHeight != null) {
            Ecef.fromGeodetic(ground.latitudeDeg, ground.longitudeDeg, knownHeight) - measuredEcef
        } else {
            val enu = LocalTangentPlane.between(measuredPoint, GeodeticPoint(ground.latitudeDeg, ground.longitudeDeg, measuredHeight))
            val moved = LocalTangentPlane.offset(measuredPoint, enu.east, enu.north, 0.0)
            Ecef.fromGeodetic(moved.latitudeDeg, moved.longitudeDeg, moved.ellipsoidalHeightM) - measuredEcef
        }
        val shift = BaseShiftVector(vector.x, vector.y, vector.z)
        val module = shift.moduleM

        val shiftedPoint = (measuredEcef + vector).toGeodetic()
        val deltas = equivalentInProject(measuredPoint, shiftedPoint, crs, heightSystem)
        return BaseShiftResult.Computed(
            vector = shift,
            deltaEastM = deltas[0],
            deltaNorthM = deltas[1],
            deltaHeightM = deltas[2],
            horizontalOnly = knownHeight == null,
            classification = BaseShiftLimits.classify(module),
        )
    }

    /** Este, Norte y altura de pasar de [from] a [to] en el SC del proyecto; en el plano local de [from] si no hay SC. */
    private fun equivalentInProject(from: GeodeticPoint, to: GeodeticPoint, crs: ProjectCrs, heightSystem: HeightSystem): DoubleArray {
        val a = positions.fromGround(GeodeticPosition(from.latitudeDeg, from.longitudeDeg, from.ellipsoidalHeightM), crs, heightSystem)
        val b = positions.fromGround(GeodeticPosition(to.latitudeDeg, to.longitudeDeg, to.ellipsoidalHeightM), crs, heightSystem)
        val local = LocalTangentPlane.between(from, to)
        val east = if (a.easting != null && b.easting != null) b.easting - a.easting else local.east
        val north = if (a.northing != null && b.northing != null) b.northing - a.northing else local.north
        val height = if (a.height != null && b.height != null) b.height - a.height else local.up
        return doubleArrayOf(east, north, height)
    }
}
