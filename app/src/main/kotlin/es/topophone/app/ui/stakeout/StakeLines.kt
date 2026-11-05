package es.topophone.app.ui.stakeout

import es.topophone.core.model.line.ProjectLine
import es.topophone.data.line.LineRepository
import es.topophone.data.line.VertexPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/** Una línea del proyecto con los puntos de sus vértices tal como están ahora (S027/RF-1, RF-15). */
data class StakeLineData(val line: ProjectLine, val vertices: List<VertexPoint>)

/**
 * Las líneas del proyecto que se replantean (S027): la línea con sus vértices, o null si no existe o está en la papelera. Los puntos en
 * la papelera no son vértices (S025/RF-25).
 */
interface StakeLines {
    fun observe(lineId: String): Flow<StakeLineData?>

    companion object {
        /** Sin líneas del proyecto: solo se replantean las de un dibujo. */
        val NONE = object : StakeLines {
            override fun observe(lineId: String): Flow<StakeLineData?> = flowOf(null)
        }
    }
}

class RoomStakeLines @Inject constructor(private val lines: LineRepository) : StakeLines {
    override fun observe(lineId: String): Flow<StakeLineData?> = combine(lines.line(lineId), lines.vertexPoints(lineId)) { line, vertices ->
        line?.takeIf { it.deletedAtMs == null }?.let { StakeLineData(it, vertices) }
    }
}
