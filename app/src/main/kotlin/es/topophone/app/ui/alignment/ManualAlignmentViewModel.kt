package es.topophone.app.ui.alignment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.di.IoDispatcher
import es.topophone.core.model.alignment.AlignmentIssue
import es.topophone.core.model.alignment.AlignmentSource
import es.topophone.core.model.alignment.ProfileIssue
import es.topophone.core.model.alignment.VerticalVertex
import es.topophone.core.model.format.NumberInput
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.survey.AlignmentDraft
import es.topophone.core.survey.KvVertex
import es.topophone.core.survey.PiAlignmentBuilder
import es.topophone.core.survey.PiIssue
import es.topophone.core.survey.PiResult
import es.topophone.core.survey.PiVertex
import es.topophone.core.survey.ProfileKvBuilder
import es.topophone.core.survey.ProfileValidator
import es.topophone.data.alignment.AlignmentImportResult
import es.topophone.data.alignment.AlignmentImporter
import es.topophone.data.project.ProjectRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Un vértice del eje tal como se escribe (S049/RF-8): Este y Norte, y el radio y la longitud de las clotoides, que pueden quedar vacíos. */
data class VertexRow(val easting: String = "", val northing: String = "", val radius: String = "", val spiral: String = "")

/** Un vértice de la rasante tal como se escribe (S049/RF-8): PK, cota y el parámetro Kv del acuerdo vertical, que puede quedar vacío. */
data class ProfileRow(val chainage: String = "", val elevation: String = "", val kv: String = "")

/** Los campos de una fila, para decir cuál no es un número. */
enum class ManualField { EASTING, NORTHING, RADIUS, SPIRAL, CHAINAGE, ELEVATION, KV }

/** Qué impide crear el eje que se está escribiendo (S049/RF-8, RF-10, RF-12). */
sealed interface ManualProblem {
    data object NameBlank : ManualProblem

    data object InvalidStart : ManualProblem

    data object TooFewVertices : ManualProblem

    /** [row] es el número de la fila, desde 1, y [profile] si es de la rasante. */
    data class InvalidNumber(val row: Int, val field: ManualField, val profile: Boolean) : ManualProblem

    data class Geometry(val issue: PiIssue) : ManualProblem

    data object ProfileTooFew : ManualProblem

    data class Profile(val issue: ProfileIssue) : ManualProblem

    data object GeographicProject : ManualProblem
}

/** Cómo acabó crear el eje (S049/RF-8). */
sealed interface ManualAlignmentResult {
    data class Created(val name: String, val lengthM: Double, val startChainage: Double, val hasProfile: Boolean) : ManualAlignmentResult

    data class Rejected(val issue: AlignmentIssue) : ManualAlignmentResult

    data object WriteError : ManualAlignmentResult
}

data class ManualAlignmentUiState(
    val loaded: Boolean = false,
    val projectUnit: LinearUnit = LinearUnit.METRE,
    val name: String = "",
    val startChainage: String = "0",
    val vertices: List<VertexRow> = listOf(VertexRow(), VertexRow()),
    val profile: List<ProfileRow> = emptyList(),
    /** Lo primero que impide crear el eje, o null si se puede crear. */
    val problem: ManualProblem? = ManualProblem.NameBlank,
    /** Longitud del eje y PK final con lo escrito, si los vértices valen (en metros). */
    val lengthM: Double? = null,
    val startChainageM: Double = 0.0,
    val working: Boolean = false,
    val result: ManualAlignmentResult? = null,
) {
    val canCreate: Boolean get() = loaded && problem == null && !working && result == null
}

/**
 * La definición a mano de un eje y su rasante (S049/RF-8): los vértices del eje con el radio de su curva y, si se quiere, la longitud de sus
 * clotoides, y los vértices de la rasante con su parámetro Kv. Todo se escribe en la unidad del proyecto; con lo escrito se calcula en vivo
 * la longitud del eje y lo primero que impide crearlo.
 */
@HiltViewModel
class ManualAlignmentViewModel @Inject constructor(
    private val importer: AlignmentImporter,
    private val projects: ProjectRepository,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    private val log: AppLog = AppLog.NONE,
) : ViewModel() {
    private val mutable = MutableStateFlow(ManualAlignmentUiState())
    val state: StateFlow<ManualAlignmentUiState> = mutable.asStateFlow()

    private var started = false
    private var project: Project? = null

    /** Prepara la definición para el proyecto [projectId]; solo la primera vez. */
    fun start(projectId: String) {
        if (started) return
        started = true
        viewModelScope.launch(io) {
            val loaded = projects.project(projectId) ?: return@launch
            project = loaded
            mutable.update { evaluate(it.copy(loaded = true, projectUnit = loaded.linearUnit)) }
        }
    }

    fun setName(name: String) = edit { it.copy(name = name) }

    fun setStartChainage(text: String) = edit { it.copy(startChainage = text) }

    fun addVertex() = edit { it.copy(vertices = it.vertices + VertexRow()) }

    fun removeVertex(index: Int) = edit { current ->
        if (index !in current.vertices.indices) current else current.copy(vertices = current.vertices.filterIndexed { i, _ -> i != index })
    }

    fun setVertex(index: Int, row: VertexRow) = edit { current ->
        if (index !in
            current.vertices.indices
        ) {
            current
        } else {
            current.copy(
                vertices = current.vertices.mapIndexed { i, r ->
                    if (i ==
                        index
                    ) {
                        row
                    } else {
                        r
                    }
                },
            )
        }
    }

    fun addProfileVertex() = edit { it.copy(profile = it.profile + ProfileRow()) }

    fun removeProfileVertex(index: Int) = edit { current ->
        if (index !in current.profile.indices) current else current.copy(profile = current.profile.filterIndexed { i, _ -> i != index })
    }

    fun setProfileVertex(index: Int, row: ProfileRow) = edit { current ->
        if (index !in
            current.profile.indices
        ) {
            current
        } else {
            current.copy(profile = current.profile.mapIndexed { i, r -> if (i == index) row else r })
        }
    }

    private fun edit(change: (ManualAlignmentUiState) -> ManualAlignmentUiState) = mutable.update { evaluate(change(it)) }

    /** Calcula lo primero que impide crear el eje con lo escrito, y su longitud si los vértices valen (RF-8, RF-10). */
    private fun evaluate(current: ManualAlignmentUiState): ManualAlignmentUiState {
        val built = build(current)
        return current.copy(problem = built.problem, lengthM = built.lengthM, startChainageM = built.startChainageM)
    }

    private class Built(val problem: ManualProblem?, val lengthM: Double?, val startChainageM: Double, val draft: AlignmentDraft? = null)

    private fun build(current: ManualAlignmentUiState): Built {
        val unit = current.projectUnit
        val start = (if (current.startChainage.isBlank()) 0.0 else NumberInput.parse(current.startChainage))?.let(unit::toMetres)
        if (project?.crs?.utmZone == null && project != null) return Built(ManualProblem.GeographicProject, null, 0.0)
        val startM = start ?: 0.0
        val vertices = ArrayList<PiVertex>()
        var problem: ManualProblem? = if (current.name.isBlank()) {
            ManualProblem.NameBlank
        } else if (start ==
            null
        ) {
            ManualProblem.InvalidStart
        } else {
            null
        }
        for ((i, row) in current.vertices.withIndex()) {
            val e = number(row.easting, required = true)
            val n = number(row.northing, required = true)
            val r = number(row.radius, required = false)
            val s = number(row.spiral, required = false)
            val bad = when {
                e == null -> ManualField.EASTING
                n == null -> ManualField.NORTHING
                r == null -> ManualField.RADIUS
                s == null -> ManualField.SPIRAL
                else -> null
            }
            if (bad != null) {
                if (problem == null) problem = ManualProblem.InvalidNumber(i + 1, bad, profile = false)
                continue
            }
            vertices += PiVertex(unit.toMetres(e!!), unit.toMetres(n!!), unit.toMetres(r!!), unit.toMetres(s!!))
        }
        if (current.vertices.size < 2 && problem == null) problem = ManualProblem.TooFewVertices
        if (problem != null) return Built(problem, null, startM)
        val elements = when (val result = PiAlignmentBuilder.build(vertices, startM)) {
            is PiResult.Failed -> return Built(ManualProblem.Geometry(result.issue), null, startM)
            is PiResult.Built -> result.elements
        }
        val length = elements.sumOf { it.length }
        val profile = profileOf(current, startM, length)
        if (profile.problem != null) return Built(profile.problem, length, startM)
        return Built(null, length, startM, AlignmentDraft(elements, profile = profile.vertices))
    }

    private class ProfileBuilt(val problem: ManualProblem?, val vertices: List<VerticalVertex>?)

    /** La rasante escrita, validada contra el PK del eje que va de [startM] a [startM] más [lengthM] (RF-8, RF-10). */
    private fun profileOf(current: ManualAlignmentUiState, startM: Double, lengthM: Double): ProfileBuilt {
        if (current.profile.isEmpty()) return ProfileBuilt(null, null)
        val unit = current.projectUnit
        val kvVertices = ArrayList<KvVertex>()
        for ((i, row) in current.profile.withIndex()) {
            val pk = number(row.chainage, required = true)
            val z = number(row.elevation, required = true)
            val kv = number(row.kv, required = false)
            val bad = when {
                pk == null -> ManualField.CHAINAGE
                z == null -> ManualField.ELEVATION
                kv == null -> ManualField.KV
                else -> null
            }
            if (bad != null) return ProfileBuilt(ManualProblem.InvalidNumber(i + 1, bad, profile = true), null)
            kvVertices += KvVertex(unit.toMetres(pk!!), unit.toMetres(z!!), if (kv == 0.0) null else unit.toMetres(kv!!))
        }
        if (kvVertices.size < 2) return ProfileBuilt(ManualProblem.ProfileTooFew, null)
        val vertices = ProfileKvBuilder.build(kvVertices)
        val issue = ProfileValidator.validate(vertices, startM, startM + lengthM)
        return if (issue != null) ProfileBuilt(ManualProblem.Profile(issue), null) else ProfileBuilt(null, vertices)
    }

    /** Un número escrito (con coma o punto); vacío vale 0 si no es [required], y null si no es un número (o falta). */
    private fun number(text: String, required: Boolean): Double? {
        if (text.isBlank()) return if (required) null else 0.0
        return NumberInput.parse(text)
    }

    /** Crea el eje con lo escrito (RF-8); si no se puede, dice por qué (RF-12). */
    fun create() {
        val current = mutable.value
        val project = project ?: return
        if (!current.canCreate) return
        val draft = build(current).draft ?: return
        mutable.update { it.copy(working = true) }
        viewModelScope.launch(io) {
            val name = current.name.trim()
            val result = when (val imported = importer.importDraft(project.id, project.crs, name, null, AlignmentSource.MANUAL, draft)) {
                is AlignmentImportResult.Failed -> ManualAlignmentResult.WriteError
                is AlignmentImportResult.Done -> {
                    val item = imported.summary.imported.firstOrNull()
                    if (item == null) {
                        ManualAlignmentResult.Rejected(imported.summary.ignored.first().issue)
                    } else {
                        log.info(LogCategory.APP, "Eje definido a mano")
                        ManualAlignmentResult.Created(item.name, item.lengthM, item.startChainage, item.hasProfile)
                    }
                }
            }
            mutable.update { it.copy(working = false, result = result) }
        }
    }
}
