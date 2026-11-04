package es.topophone.app.ui.collector

import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeKey
import es.topophone.core.model.codes.PointAttribute
import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.codes.SurveyCode
import es.topophone.core.model.line.LineSummary
import es.topophone.core.model.line.ProjectLine
import es.topophone.core.model.point.Point
import es.topophone.data.line.LineActionResult
import es.topophone.data.line.LineRepository
import es.topophone.data.line.MeasuredResult
import es.topophone.data.point.PointSaveResult
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** De dónde saca el recolector la línea activa y cómo guarda un punto de línea (S025/RF-2 a RF-9). */
interface CollectorLines {
    /** La línea activa del proyecto, si la hay (RF-3, RF-9). */
    fun active(projectId: String): Flow<ProjectLine?>

    /** Las líneas abiertas del proyecto, para continuarlas (RF-5). */
    fun openLines(projectId: String): Flow<List<LineSummary>>

    /** Guarda el punto con [save] y su vértice, o la línea nueva, en una sola transacción (RF-2, RF-3, RF-7, RF-8). */
    suspend fun saveMeasured(point: Point, geometry: CodeGeometry?, save: suspend (Point) -> PointSaveResult): MeasuredResult

    suspend fun finish(id: String): LineActionResult

    suspend fun continueLine(id: String): LineActionResult

    suspend fun undoVertex(id: String): LineActionResult

    companion object {
        /** Sin líneas: el punto se guarda solo. */
        val NONE = object : CollectorLines {
            override fun active(projectId: String): Flow<ProjectLine?> = flowOf(null)

            override fun openLines(projectId: String): Flow<List<LineSummary>> = flowOf(emptyList())

            override suspend fun saveMeasured(
                point: Point,
                geometry: CodeGeometry?,
                save: suspend (Point) -> PointSaveResult,
            ): MeasuredResult =
                save(point).let { if (it == PointSaveResult.Saved) MeasuredResult.PointOnly else MeasuredResult.PointNotSaved(it) }

            override suspend fun finish(id: String): LineActionResult = LineActionResult.NOT_FOUND

            override suspend fun continueLine(id: String): LineActionResult = LineActionResult.NOT_FOUND

            override suspend fun undoVertex(id: String): LineActionResult = LineActionResult.NOT_FOUND
        }
    }
}

class RoomCollectorLines @Inject constructor(private val lines: LineRepository) : CollectorLines {
    override fun active(projectId: String): Flow<ProjectLine?> = lines.active(projectId)

    override fun openLines(projectId: String): Flow<List<LineSummary>> = lines.summaries(projectId).map { all -> all.filter { !it.closed } }

    override suspend fun saveMeasured(point: Point, geometry: CodeGeometry?, save: suspend (Point) -> PointSaveResult): MeasuredResult =
        lines.saveMeasured(point, geometry, save)

    override suspend fun finish(id: String): LineActionResult = lines.finish(id)

    override suspend fun continueLine(id: String): LineActionResult = lines.continueLine(id)

    override suspend fun undoVertex(id: String): LineActionResult = lines.undoVertex(id)
}

/**
 * La línea activa tal como la ve el recolector (S025/RF-3): su nombre y su número de vértices. [codeKey] es la clave del código de la
 * línea; [attributes] son los valores de la línea, que el punto hereda mientras se mide con su código (RF-8b).
 */
data class CollectorLineUi(
    val id: String,
    val name: String,
    val vertexCount: Int,
    val geometry: CodeGeometry,
    val codeKey: String?,
    val attributes: List<PointAttribute> = emptyList(),
) {
    companion object {
        fun of(line: ProjectLine) = CollectorLineUi(
            line.id,
            line.name,
            line.vertices.size,
            line.geometry,
            line.code?.let { CodeKey.of(it.code) },
            line.code?.attributes.orEmpty(),
        )
    }
}

/** Qué hace el recolector con el código elegido respecto de la línea activa (S025/RF-3, RF-8, RF-8b, plan D11). */
object CollectorLineRules {
    /** Si el código [selected] es de línea o de polígono. */
    fun isLineCode(selected: SurveyCode?): Boolean = selected != null && isLineGeometry(selected.geometry)

    /** Si [geometry] es la de una línea o un polígono. */
    fun isLineGeometry(geometry: CodeGeometry?): Boolean = geometry == CodeGeometry.LINE || geometry == CodeGeometry.POLYGON

    /** Si el punto que se mida con [selected] se añade a la línea activa [line]: los valores de los atributos salen de ella (RF-8b). */
    fun appendsToActive(selected: SurveyCode?, line: CollectorLineUi?): Boolean =
        isLineCode(selected) && line != null && line.codeKey == CodeKey.of(selected!!.code)

    /** El código del punto: con una línea activa del mismo código, los valores de la línea; si no, los del formulario (RF-8b). */
    fun pointCode(selected: SurveyCode?, line: CollectorLineUi?, fromForm: () -> PointCode?): PointCode? =
        if (appendsToActive(selected, line)) {
            PointCode(selected!!.code, selected.description, selected.color, selected.symbol, line!!.attributes)
        } else {
            fromForm()
        }
}
