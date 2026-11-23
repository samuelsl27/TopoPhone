package es.topophone.app.ui.geometry

import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.TerrainPlane
import es.topophone.core.survey.TerrainView
import es.topophone.core.survey.geometry.GeometryCalculator
import es.topophone.core.survey.geometry.GeometryResult
import es.topophone.core.survey.geometry.GeometryVertex
import es.topophone.core.survey.geometry.GroundPosition
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

    /** Los vértices de la línea de un dibujo y si es un anillo cerrado (S026/RF-1, RF-4). */
    class CadLine(val vertices: List<GeometryVertex>, val closed: Boolean)

    /**
     * La línea de un elemento del dibujo, en el SC del proyecto. Sin [withHeight] (el dibujo no tiene cota) los vértices no llevan
     * altura (RF-4). La posición geodésica solo se calcula si se pide ([withGround]): es lo que hace falta para las distancias de
     * terreno. Un anillo de polígono repite el primer vértice al final; aquí se cierra sin repetirlo.
     */
    fun fromCad(entity: CadEntity, crs: ProjectCrs, withHeight: Boolean, withGround: Boolean): CadLine {
        var count = entity.vertexCount
        val ring = entity.polygonRing &&
            count >= 4 &&
            entity.easting[0] == entity.easting[count - 1] &&
            entity.northing[0] == entity.northing[count - 1]
        if (ring) count -= 1
        val heights = if (withHeight) entity.height else null
        val geographic = !crs.isUtm
        val vertices = List(count) { i ->
            val east = entity.easting[i]
            val north = entity.northing[i]
            val ground = if (geographic || withGround) GroundPosition.of(crs, east, north) else null
            GeometryVertex(
                easting = if (geographic) null else east,
                northing = if (geographic) null else north,
                height = heights?.get(i),
                latitudeDeg = ground?.first,
                longitudeDeg = ground?.second,
            )
        }
        return CadLine(vertices, ring)
    }

    /**
     * Calcula la información con la distancia elegida. El proyecto no guarda una altura de referencia (el punto central no la
     * tiene), así que sin altura elipsoidal en un vértice el terreno usa el factor de escala y lo avisa (S026/RF-10).
     */
    fun compute(
        vertices: List<GeometryVertex>,
        closed: Boolean,
        mode: DistanceMode,
        crs: ProjectCrs,
        terrain: TerrainView = TerrainView.GRID,
    ): GeometryResult {
        // S058/RF-9: en terreno se mide sobre las coordenadas de terreno, sin otra escala: longitudes por k, áreas por k², ángulos iguales.
        val scale = if (mode == DistanceMode.GROUND && !terrain.isTerrain) GroundScale(crs, fallbackHeightM = null) else SegmentScale.Grid
        return GeometryCalculator.of(vertices.map { inTerrain(it, terrain) }, closed, scale)
    }

    /** El vértice con su Este y su Norte de terreno; en cuadrícula, el mismo (S058/RF-9). */
    fun inTerrain(vertex: GeometryVertex, terrain: TerrainView): GeometryVertex {
        val east = vertex.easting
        val north = vertex.northing
        if (!terrain.isTerrain || east == null || north == null) return vertex
        return terrain.coordinates(east, north).let { vertex.copy(easting = it.easting, northing = it.northing) }
    }

    /** El plano de terreno del proyecto, o la cuadrícula (S058/RF-9). */
    fun terrainOf(plane: TerrainPlane?, crs: ProjectCrs): TerrainView = TerrainView.of(plane, crs) ?: TerrainView.GRID
}
