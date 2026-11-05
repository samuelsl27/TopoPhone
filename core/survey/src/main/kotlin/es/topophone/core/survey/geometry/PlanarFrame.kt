package es.topophone.core.survey.geometry

import es.topophone.core.geodesy.GeodeticPoint
import es.topophone.core.geodesy.LocalTangentPlane

/**
 * Las coordenadas planas de unos vértices, en metros (S026/RF-2, RF-11): las de cuadrícula del SC del proyecto o, en un SC
 * geográfico, el plano tangente local del primer vértice ([local]), el mismo de S041/RF-15.
 */
class PlanarFrame(val east: DoubleArray, val north: DoubleArray, val local: Boolean = false) {
    companion object {
        /** El plano de los vértices, o null si faltan Este y Norte en alguno y no se puede usar el plano local. */
        fun of(vertices: List<GeometryVertex>): PlanarFrame? {
            if (vertices.all { it.easting != null && it.northing != null }) {
                return PlanarFrame(
                    DoubleArray(vertices.size) { vertices[it].easting!! },
                    DoubleArray(vertices.size) { vertices[it].northing!! },
                )
            }
            if (vertices.any { it.latitudeDeg == null || it.longitudeDeg == null }) return null
            val origin = vertices[0].geodetic()
            val east = DoubleArray(vertices.size)
            val north = DoubleArray(vertices.size)
            for (i in vertices.indices) {
                val enu = LocalTangentPlane.between(origin, vertices[i].geodetic())
                east[i] = enu.east
                north[i] = enu.north
            }
            return PlanarFrame(east, north, local = true)
        }

        private fun GeometryVertex.geodetic() = GeodeticPoint(latitudeDeg!!, longitudeDeg!!, ellipsoidalHeightM ?: 0.0)
    }
}
