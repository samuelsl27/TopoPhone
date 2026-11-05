package es.topophone.app.ui.geometry

import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.geometry.GeometryCalculator
import es.topophone.core.survey.geometry.GeometryResult
import es.topophone.core.survey.geometry.GeometryVertex
import es.topophone.core.survey.geometry.GroundScale
import es.topophone.core.survey.geometry.SegmentScale
import es.topophone.data.line.VertexPoint

/** Del origen de los vértices (líneas del proyecto o de un dibujo) al cálculo de `core:survey` (S026). */
internal object GeometryInput {

    fun fromVertexPoints(vertices: List<VertexPoint>): List<GeometryVertex> = vertices.map {
        GeometryVertex(
            easting = it.easting,
            northing = it.northing,
            height = it.height,
            latitudeDeg = it.latitudeDeg,
            longitudeDeg = it.longitudeDeg,
            ellipsoidalHeightM = it.ellipsoidalHeightM,
        )
    }

    /**
     * Calcula la información con la distancia elegida. El proyecto no guarda una altura de referencia (el punto central no la
     * tiene), así que sin altura elipsoidal en un vértice el terreno usa el factor de escala y lo avisa (S026/RF-10).
     */
    fun compute(vertices: List<GeometryVertex>, closed: Boolean, mode: DistanceMode, crs: ProjectCrs): GeometryResult {
        val scale = if (mode == DistanceMode.GROUND) GroundScale(crs, fallbackHeightM = null) else SegmentScale.Grid
        return GeometryCalculator.of(vertices, closed, scale)
    }
}
