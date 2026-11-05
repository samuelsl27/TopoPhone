package es.topophone.core.survey.geometry

/** Las coordenadas planas de unos vértices, en metros (S026/RF-2): las de cuadrícula del SC del proyecto. */
class PlanarFrame(val east: DoubleArray, val north: DoubleArray) {
    companion object {
        /** El plano de los vértices, o null si no se puede construir. */
        fun of(vertices: List<GeometryVertex>): PlanarFrame? {
            if (vertices.any { it.easting == null || it.northing == null }) return null
            return PlanarFrame(
                DoubleArray(vertices.size) { vertices[it].easting!! },
                DoubleArray(vertices.size) { vertices[it].northing!! },
            )
        }
    }
}
