package es.topophone.app.ui.cogo

import es.topophone.core.model.cogo.CogoEntry
import es.topophone.core.model.cogo.CogoOperation
import es.topophone.core.model.cogo.CogoRef
import es.topophone.core.model.point.PointNames
import es.topophone.core.survey.TerrainView
import es.topophone.core.survey.cogo.CogoPointBuilder
import es.topophone.core.survey.cogo.CogoPointResult
import es.topophone.data.cogo.CogoRepository
import es.topophone.data.point.PointRepository
import es.topophone.data.point.PointSaveResult
import es.topophone.data.project.ProjectRepository
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Un resultado de COGO que se puede guardar como punto del proyecto (S038/RF-19): su posición, su altura opcional y su rastro. */
data class CogoResult(val key: String, val easting: Double, val northing: Double, val height: Double?, val ref: CogoRef)

/** El punto de entrada tal como se copia al rastro del punto guardado, por valor (S038/RF-22). */
internal fun CogoSlotValue.Found.toEntry(role: String) = CogoEntry(role, name, point.easting, point.northing, point.height)

/** Lo que está mal en el nombre de un punto que se va a guardar (S038/RF-20). */
enum class NameProblem { EMPTY, REPEATED, EXISTS }

/** Un resultado con el nombre que se le propone y el usuario puede cambiar. */
data class CogoNameRow(val key: String, val name: String, val problem: NameProblem? = null)

/** Cómo va el guardado (S038/RF-19 a RF-21). */
sealed interface CogoSaveStatus {
    data object Idle : CogoSaveStatus

    data object Saving : CogoSaveStatus

    data class Saved(val count: Int) : CogoSaveStatus

    /** Hay un nombre vacío, repetido o que ya existe: no se ha guardado ninguno (S038/RF-20). */
    data object NameProblems : CogoSaveStatus

    /** Alguno de los resultados [keys] cae fuera del ámbito del SC del proyecto: no se ha guardado ninguno (S038/RF-21). */
    data class OutOfScope(val keys: List<String>) : CogoSaveStatus
}

data class CogoSaveUi(
    val open: Boolean = false,
    val rows: List<CogoNameRow> = emptyList(),
    val status: CogoSaveStatus = CogoSaveStatus.Idle,
)

/**
 * Guardar como puntos del proyecto los resultados marcados de COGO (S038/RF-19 a RF-21, RF-23, RF-30): propone los nombres que seguirían
 * a los del proyecto (S011/RF-16) y los deja editar; comprueba antes de nada los nombres y el ámbito de cada resultado, de modo que se
 * guardan todos o ninguno; y no escribe nada hasta que el usuario pulsa «Guardar puntos», así que salir sin guardar no crea nada.
 */
class CogoSaveController(
    private val points: PointRepository,
    private val cogo: CogoRepository,
    private val projects: ProjectRepository,
    private val scope: CoroutineScope,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
    /** Se llama al guardar bien; con `clearedDraft` el borrador del proyecto se ha vaciado y la pantalla debe quedar vacía (S038/RF-30). */
    private val onSaved: (clearedDraft: Boolean) -> Unit = {},
) {
    private val ui = MutableStateFlow(CogoSaveUi())
    val state: StateFlow<CogoSaveUi> = ui

    private var projectId = ""
    private var results: List<CogoResult> = emptyList()
    private var operation = CogoOperation.TRAVERSE
    private var clearDraft = false

    /** Abre la hoja con los nombres propuestos para [results]; con [clearDraft] se vacía el borrador de la poligonal al guardar (RF-30). */
    fun open(projectId: String, results: List<CogoResult>, operation: CogoOperation, clearDraft: Boolean) {
        if (results.isEmpty()) return
        this.projectId = projectId
        this.results = results
        this.operation = operation
        this.clearDraft = clearDraft
        scope.launch {
            val taken = points.names(projectId).toMutableSet()
            var last = points.lastName(projectId)
            val rows = results.map { result ->
                val name = PointNames.nextFree(last, taken)
                taken += name
                last = name
                CogoNameRow(result.key, name)
            }
            ui.value = CogoSaveUi(open = true, rows = rows)
        }
    }

    fun setName(key: String, name: String) {
        ui.value =
            ui.value.copy(
                rows = ui.value.rows.map {
                    if (it.key ==
                        key
                    ) {
                        it.copy(name = name, problem = null)
                    } else {
                        it
                    }
                },
                status = CogoSaveStatus.Idle,
            )
    }

    /** Cierra la hoja sin guardar nada (S038/RF-23). */
    fun dismiss() {
        ui.value = CogoSaveUi()
    }

    fun save() {
        if (!ui.value.open || ui.value.status == CogoSaveStatus.Saving) return
        scope.launch {
            val current = ui.value
            val taken = points.names(projectId)
            val names = current.rows.map { it.name.trim() }
            val rows = current.rows.mapIndexed { i, row ->
                val name = names[i]
                val problem = when {
                    name.isEmpty() -> NameProblem.EMPTY
                    names.count { it == name } > 1 -> NameProblem.REPEATED
                    name in taken -> NameProblem.EXISTS
                    else -> null
                }
                row.copy(problem = problem)
            }
            if (rows.any { it.problem != null }) {
                ui.value = current.copy(rows = rows, status = CogoSaveStatus.NameProblems)
                return@launch
            }
            val project = projects.observe(projectId).first() ?: return@launch
            val builder = CogoPointBuilder()
            // S058/RF-10: con el proyecto en terreno el cálculo fue en terreno; los puntos y su rastro se guardan en cuadrícula.
            val terrain = TerrainView.of(project.terrain, project.crs) ?: TerrainView.GRID
            val built = results.mapIndexed { i, r ->
                val grid = terrain.toGrid(r.easting, r.northing)
                val entries = r.ref.entries.map { e ->
                    terrain.toGrid(e.easting, e.northing).let { g -> e.copy(easting = g.easting, northing = g.northing) }
                }
                r.key to
                    builder.build(newId(), project, names[i], grid.easting, grid.northing, r.height, r.ref.copy(entries = entries), clock())
            }
            val rejected = built.filter { it.second is CogoPointResult.Rejected }.map { it.first }
            if (rejected.isNotEmpty()) {
                ui.value = current.copy(rows = rows, status = CogoSaveStatus.OutOfScope(rejected))
                return@launch
            }
            ui.value = current.copy(rows = rows, status = CogoSaveStatus.Saving)
            val saved = cogo.savePoints(built.map { (it.second as CogoPointResult.Built).point }, operation, clearDraft)
            ui.value = when (saved) {
                PointSaveResult.Saved -> {
                    onSaved(clearDraft)
                    current.copy(rows = rows, status = CogoSaveStatus.Saved(built.size))
                }
                else -> current.copy(rows = rows, status = CogoSaveStatus.NameProblems) // alguien usó el nombre entre medias
            }
        }
    }
}
