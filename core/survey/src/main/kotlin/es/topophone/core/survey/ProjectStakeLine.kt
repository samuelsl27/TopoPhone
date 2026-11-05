package es.topophone.core.survey

/** Un vértice de una línea del proyecto: el nombre de su punto, su posición en el plano del SC y su altura, si la tiene (S027/RF-5). */
data class ProjectStakeVertex(val name: String, val easting: Double?, val northing: Double?, val height: Double?)

sealed interface ProjectStakeLineResult {
    /**
     * La línea lista para replantear. Los vértices repetidos seguidos se quitan, así que cada vértice de [line] dice de cuál de los
     * vértices de la entrada sale ([sourceIndex]); [names] son los nombres de los puntos de la entrada.
     */
    class Built(val line: StakeLine, private val names: List<String>, val sourceIndex: IntArray, private val interpolated: BooleanArray) :
        ProjectStakeLineResult {
        /** El nombre del punto del vértice [vertex] de [line]. */
        fun name(vertex: Int): String = names[sourceIndex[vertex]]

        /** La cota del vértice no es la de su punto sino la interpolada entre los vértices con altura que lo rodean (S027/RF-8). */
        fun isInterpolated(vertex: Int): Boolean = interpolated[vertex]
    }

    /** Menos de dos vértices distintos (S027/RF-3). */
    data object TooFewVertices : ProjectStakeLineResult

    /** Algún vértice no tiene Este y Norte: el SC del proyecto no da un plano donde replantear. */
    data object NoPlanePosition : ProjectStakeLineResult
}

/**
 * De los vértices de una línea del proyecto (S025) al [StakeLine] de S041 (S027/RF-2). Todos los vértices son originales y rectos
 * (RF-6), un polígono es una línea cerrada y la altura de un vértice es la de su punto, o falta (RF-8).
 */
object ProjectStakeLine {

    fun of(vertices: List<ProjectStakeVertex>, closed: Boolean): ProjectStakeLineResult {
        if (vertices.size < 2) return ProjectStakeLineResult.TooFewVertices
        if (vertices.any { it.easting == null || it.northing == null }) return ProjectStakeLineResult.NoPlanePosition
        val built = StakeLine.createWithSource(
            DoubleArray(vertices.size) { vertices[it].easting!! },
            DoubleArray(vertices.size) { vertices[it].northing!! },
            if (vertices.any { it.height != null }) DoubleArray(vertices.size) { vertices[it].height ?: Double.NaN } else null,
            closed,
        ) ?: return ProjectStakeLineResult.TooFewVertices
        val line = built.line
        val interpolated = BooleanArray(line.vertexCount) { line.height(it) == null && line.heightAt(line.chainageOf(it)) != null }
        return ProjectStakeLineResult.Built(line, vertices.map { it.name }, built.sourceIndex, interpolated)
    }
}
