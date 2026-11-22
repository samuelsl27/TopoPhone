package es.topophone.core.geodesy.catalog

import es.topophone.core.geodesy.GeodeticPoint
import es.topophone.core.geodesy.datum.GridSource
import es.topophone.core.geodesy.datum.TransformResult
import es.topophone.core.model.project.CrsDefinition
import kotlin.math.cos
import kotlin.math.hypot

/** Por qué una coordenada de prueba no se puede calcular (S035/RF-7, RF-8). */
sealed interface ProbeFailure {
    /** Falta la rejilla [gridName]: se ofrece descargarla (S035/RF-8). */
    data class MissingGrid(val gridName: String) : ProbeFailure

    /** El punto queda fuera de la rejilla de la transformación. */
    data object OutsideGrid : ProbeFailure

    /** La definición no se puede construir, o la coordenada no está en el dominio de la proyección. */
    data object NotComputable : ProbeFailure
}

/** Lo que da una coordenada de prueba (S035/RF-7): el resultado y lo que se aleja de la entrada al volver, en metros (ida y vuelta). */
sealed interface ProbeResult {
    /**
     * Del datum de las correcciones al SC: [first] y [second] son el Este y el Norte en la unidad del SC, o la latitud y la longitud en grados en
     * un SC geográfico. [datumLatitudeDeg] y [datumLongitudeDeg] son la posición en el datum del propio SC.
     */
    data class ToCrs(
        val first: Double,
        val second: Double,
        val datumLatitudeDeg: Double,
        val datumLongitudeDeg: Double,
        val closureM: Double,
    ) : ProbeResult

    /** Del SC al datum de las correcciones: latitud y longitud en grados. */
    data class FromCrs(
        val latitudeDeg: Double,
        val longitudeDeg: Double,
        val datumLatitudeDeg: Double,
        val datumLongitudeDeg: Double,
        val closureM: Double,
    ) : ProbeResult

    data class Failed(val reason: ProbeFailure) : ProbeResult
}

/**
 * Las coordenadas de prueba del editor de SC (S035/RF-7): lleva una posición del datum de las correcciones al SC y de vuelta, con lo que el
 * usuario compara con los valores de su cliente. No usa el catálogo: solo la definición y las rejillas.
 */
object CrsProbe {

    /** Una posición en el datum de las correcciones ([latitudeDeg], [longitudeDeg]) en el SC [definition]. */
    fun toCrs(definition: CrsDefinition, grids: GridSource, latitudeDeg: Double, longitudeDeg: Double): ProbeResult {
        val resolved = CrsDefinitions.resolve(definition, grids) ?: return ProbeResult.Failed(ProbeFailure.NotComputable)
        resolved.transformation.missingGrid()?.let { return ProbeResult.Failed(ProbeFailure.MissingGrid(it)) }
        val frame = GeodeticPoint(latitudeDeg, longitudeDeg, 0.0)
        val datum = when (val r = resolved.transformation.inverse(frame)) {
            is TransformResult.Ok -> r.point
            is TransformResult.GridUnavailable -> return ProbeResult.Failed(ProbeFailure.MissingGrid(r.gridName))
            is TransformResult.OutsideGrid, is TransformResult.NotConverged -> return ProbeResult.Failed(ProbeFailure.OutsideGrid)
        }
        val projection = resolved.projection
        val unit = definition.unitMetres
        if (projection == null) {
            val back = (resolved.transformation.forward(datum) as? TransformResult.Ok)?.point
            val closure = back?.let { metresBetween(it.latitudeDeg, it.longitudeDeg, latitudeDeg, longitudeDeg) } ?: Double.NaN
            return ProbeResult.ToCrs(datum.latitudeDeg, datum.longitudeDeg, datum.latitudeDeg, datum.longitudeDeg, closure)
        }
        val projected = projection.forward(datum.latitudeDeg, datum.longitudeDeg) ?: return ProbeResult.Failed(ProbeFailure.NotComputable)
        val again = projection.inverse(projected.eastingM, projected.northingM) ?: return ProbeResult.Failed(ProbeFailure.NotComputable)
        val back = (
            resolved.transformation.forward(
                GeodeticPoint(again.latitudeDeg, again.longitudeDeg, datum.ellipsoidalHeightM),
            ) as? TransformResult.Ok
            )?.point
        val closure = back?.let { metresBetween(it.latitudeDeg, it.longitudeDeg, latitudeDeg, longitudeDeg) } ?: Double.NaN
        return ProbeResult.ToCrs(projected.eastingM / unit, projected.northingM / unit, datum.latitudeDeg, datum.longitudeDeg, closure)
    }

    /**
     * Unas coordenadas del SC [definition] en el datum de las correcciones: [first] y [second] son el Este y el Norte en la unidad del SC, o la
     * latitud y la longitud en grados si es geográfico.
     */
    fun fromCrs(definition: CrsDefinition, grids: GridSource, first: Double, second: Double): ProbeResult {
        val resolved = CrsDefinitions.resolve(definition, grids) ?: return ProbeResult.Failed(ProbeFailure.NotComputable)
        resolved.transformation.missingGrid()?.let { return ProbeResult.Failed(ProbeFailure.MissingGrid(it)) }
        val projection = resolved.projection
        val datumLat: Double
        val datumLon: Double
        if (projection == null) {
            datumLat = first
            datumLon = second
        } else {
            val unit = definition.unitMetres
            val point = projection.inverse(first * unit, second * unit) ?: return ProbeResult.Failed(ProbeFailure.NotComputable)
            datumLat = point.latitudeDeg
            datumLon = point.longitudeDeg
        }
        val frame = when (val r = resolved.transformation.forward(GeodeticPoint(datumLat, datumLon, 0.0))) {
            is TransformResult.Ok -> r.point
            is TransformResult.GridUnavailable -> return ProbeResult.Failed(ProbeFailure.MissingGrid(r.gridName))
            is TransformResult.OutsideGrid, is TransformResult.NotConverged -> return ProbeResult.Failed(ProbeFailure.OutsideGrid)
        }
        val closure = closureFromCrs(resolved, projection != null, definition.unitMetres, frame, first, second)
        return ProbeResult.FromCrs(frame.latitudeDeg, frame.longitudeDeg, datumLat, datumLon, closure)
    }

    private fun closureFromCrs(
        resolved: ResolvedCrs,
        projected: Boolean,
        unit: Double,
        frame: GeodeticPoint,
        first: Double,
        second: Double,
    ): Double {
        val datum = (resolved.transformation.inverse(frame) as? TransformResult.Ok)?.point ?: return Double.NaN
        if (!projected) return metresBetween(datum.latitudeDeg, datum.longitudeDeg, first, second)
        val again = resolved.projection!!.forward(datum.latitudeDeg, datum.longitudeDeg) ?: return Double.NaN
        return hypot(again.eastingM - first * unit, again.northingM - second * unit)
    }

    /** Metros entre dos posiciones cercanas, con la esfera media: sobra para mostrar un cierre del orden de milímetros. */
    private fun metresBetween(latitudeADeg: Double, longitudeADeg: Double, latitudeBDeg: Double, longitudeBDeg: Double): Double {
        val perDegree = METRES_PER_DEGREE
        return hypot(
            (latitudeADeg - latitudeBDeg) * perDegree,
            (longitudeADeg - longitudeBDeg) * perDegree * cos(Math.toRadians(latitudeBDeg)),
        )
    }

    private const val METRES_PER_DEGREE = 111_319.49
}
