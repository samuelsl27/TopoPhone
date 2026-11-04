package es.topophone.app.ui.points

import es.topophone.core.model.line.LineSummary
import es.topophone.core.model.line.ProjectLine
import es.topophone.data.line.LineRepository
import es.topophone.data.line.LineSaveResult
import es.topophone.data.line.TrashedLine
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Lo que la lista de objetos, la papelera y la ficha del punto necesitan de las líneas del proyecto (S025/RF-21 a RF-25): las líneas junto a
 * los puntos, ocultarlas, la papelera de líneas y qué líneas usa un punto.
 */
interface ObjectLines {
    fun summaries(projectId: String): Flow<List<LineSummary>>

    /** Las líneas del proyecto con sus vértices, para dibujarlas en el mapa (RF-22). */
    fun lines(projectId: String): Flow<List<ProjectLine>>

    suspend fun setHidden(id: String, hidden: Boolean)

    /** Los nombres de las líneas activas que tienen alguno de [pointIds] como vértice (RF-25). */
    suspend fun linesUsing(pointIds: List<String>): List<String>

    fun trash(projectId: String): Flow<List<TrashedLine>>

    suspend fun restore(id: String, newName: String?): LineSaveResult

    suspend fun deletePermanently(id: String)

    /** El nombre que se propone para restaurar una línea cuyo nombre está ocupado (RF-24). */
    suspend fun suggestName(projectId: String, code: String?): String

    companion object {
        /** Sin líneas: la lista solo tiene puntos. */
        val NONE = object : ObjectLines {
            override fun summaries(projectId: String): Flow<List<LineSummary>> = flowOf(emptyList())

            override fun lines(projectId: String): Flow<List<ProjectLine>> = flowOf(emptyList())

            override suspend fun setHidden(id: String, hidden: Boolean) = Unit

            override suspend fun linesUsing(pointIds: List<String>): List<String> = emptyList()

            override fun trash(projectId: String): Flow<List<TrashedLine>> = flowOf(emptyList())

            override suspend fun restore(id: String, newName: String?): LineSaveResult = LineSaveResult.NotFound

            override suspend fun deletePermanently(id: String) = Unit

            override suspend fun suggestName(projectId: String, code: String?): String = ""
        }
    }
}

class RoomObjectLines @Inject constructor(private val lines: LineRepository) : ObjectLines {
    override fun summaries(projectId: String): Flow<List<LineSummary>> = lines.summaries(projectId)

    override fun lines(projectId: String): Flow<List<ProjectLine>> = lines.lines(projectId)

    override suspend fun setHidden(id: String, hidden: Boolean) = lines.setHidden(id, hidden)

    override suspend fun linesUsing(pointIds: List<String>): List<String> = lines.linesUsing(pointIds)

    override fun trash(projectId: String): Flow<List<TrashedLine>> = lines.trash(projectId)

    override suspend fun restore(id: String, newName: String?): LineSaveResult = lines.restore(id, newName)

    override suspend fun deletePermanently(id: String) = lines.deletePermanently(id)

    override suspend fun suggestName(projectId: String, code: String?): String = lines.suggestName(projectId, code)
}
