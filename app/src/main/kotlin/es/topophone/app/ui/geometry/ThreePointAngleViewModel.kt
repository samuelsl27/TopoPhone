package es.topophone.app.ui.geometry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointSearch
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.TerrainPlane
import es.topophone.core.survey.geometry.AnglePoint
import es.topophone.core.survey.geometry.AngleResult
import es.topophone.core.survey.geometry.GeometryVertex
import es.topophone.core.survey.geometry.GroundScale
import es.topophone.core.survey.geometry.SegmentScale
import es.topophone.core.survey.geometry.ThreePointAngle
import es.topophone.data.geometry.GeometryPreferences
import es.topophone.data.line.LineRepository
import es.topophone.data.point.PointRepository
import es.topophone.data.project.ProjectRepository
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Un punto que se puede elegir como A, B o C. */
data class AnglePointOption(val id: String, val name: String)

/** Lo que muestra el ángulo entre tres puntos (S026/RF-13 a RF-15). */
data class ThreePointAngleUi(
    val loaded: Boolean = false,
    val mode: DistanceMode = DistanceMode.GRID,
    /** S058/RF-9: el proyecto está en terreno; se mide en terreno y no hay elección Cuadrícula/Terreno. */
    val terrain: Boolean = false,
    val linearUnit: LinearUnit = LinearUnit.METRE,
    val angularUnit: AngularUnit = AngularUnit.DMS,
    val geographic: Boolean = false,
    /** El nombre de la línea cuyos vértices se ofrecen, o null si se ofrecen todos los puntos del proyecto. */
    val lineName: String? = null,
    val a: AnglePointOption? = null,
    val b: AnglePointOption? = null,
    val c: AnglePointOption? = null,
    /** El punto que se está eligiendo y lo que se escribe para buscarlo. */
    val picking: AnglePoint? = null,
    val query: String = "",
    val candidates: List<AnglePointOption> = emptyList(),
    /** Null hasta que están elegidos los tres puntos. */
    val result: AngleResult? = null,
)

/**
 * El ángulo en B entre las direcciones a A y a C (S026/RF-13 a RF-15). Los tres puntos salen del proyecto o, desde la ficha de una
 * línea, de sus vértices; se leen con su estado actual, de modo que editar un punto recalcula. No guarda nada.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ThreePointAngleViewModel @Inject constructor(
    private val points: PointRepository,
    private val lines: LineRepository,
    private val projects: ProjectRepository,
    private val preferences: GeometryPreferences,
) : ViewModel() {
    private val scope = MutableStateFlow<Pair<String, String?>?>(null)
    private val own = MutableStateFlow(Own())

    private data class Own(
        val a: AnglePointOption? = null,
        val b: AnglePointOption? = null,
        val c: AnglePointOption? = null,
        val picking: AnglePoint? = null,
        val query: String = "",
    )

    private data class Context(
        val crs: ProjectCrs,
        val linearUnit: LinearUnit,
        val angularUnit: AngularUnit,
        val lineName: String?,
        val terrain: TerrainPlane?,
    )

    private val context = scope.flatMapLatest { pair ->
        if (pair == null) {
            flowOf(null)
        } else {
            val lineName = pair.second?.let { lines.line(it) } ?: flowOf(null)
            combine(projects.observe(pair.first), lineName) { project, line ->
                project?.let { Context(it.crs, it.linearUnit, it.angularUnit, line?.name, it.terrain) }
            }
        }
    }

    /** Los puntos entre los que se elige, solo mientras el selector está abierto (no se cargan al abrir la pantalla). */
    private val candidates: Flow<List<AnglePointOption>> =
        combine(scope, own.map { it.picking != null }.distinctUntilChanged()) { pair, picking -> pair to picking }
            .flatMapLatest { (pair, picking) ->
                when {
                    pair == null || !picking -> flowOf(emptyList())
                    pair.second != null -> lines.vertexPoints(pair.second!!).map { vertices ->
                        vertices.distinctBy { it.pointId }.map { AnglePointOption(it.pointId, it.name) }
                    }
                    else -> points.summaries(pair.first).map { all -> all.map { AnglePointOption(it.id, it.name) } }
                }
            }

    private fun pointOf(option: AnglePointOption?): Flow<Point?> = if (option == null) flowOf(null) else points.point(option.id)

    val state: StateFlow<ThreePointAngleUi> = combine(
        context,
        own,
        candidates,
        preferences.distanceMode,
        own.map { Triple(it.a, it.b, it.c) }.distinctUntilChanged().flatMapLatest { (a, b, c) ->
            combine(pointOf(a), pointOf(b), pointOf(c)) { pa, pb, pc -> Triple(pa, pb, pc) }
        },
    ) { context, own, candidates, chosenMode, vertices ->
        val mode = if (context != null && GeometryInput.terrainOf(context.terrain, context.crs).isTerrain) DistanceMode.GRID else chosenMode
        if (context == null) {
            ThreePointAngleUi(loaded = false, mode = mode)
        } else {
            val (pa, pb, pc) = vertices
            // S058/RF-9: en terreno los vértices pasan a sus coordenadas de terreno; el ángulo no cambia y las distancias van por k.
            val view = GeometryInput.terrainOf(context.terrain, context.crs)
            val va = pa?.toVertex(context.crs)?.let { GeometryInput.inTerrain(it, view) }
            val vb = pb?.toVertex(context.crs)?.let { GeometryInput.inTerrain(it, view) }
            val vc = pc?.toVertex(context.crs)?.let { GeometryInput.inTerrain(it, view) }
            val result = if (va != null && vb != null && vc != null) {
                val scale = if (mode == DistanceMode.GROUND) GroundScale(context.crs, fallbackHeightM = null) else SegmentScale.Grid
                ThreePointAngle.of(va, vb, vc, scale)
            } else {
                null
            }
            ThreePointAngleUi(
                loaded = true,
                mode = mode,
                terrain = view.isTerrain,
                linearUnit = context.linearUnit,
                angularUnit = context.angularUnit,
                geographic = !context.crs.isUtm,
                lineName = context.lineName,
                a = own.a,
                b = own.b,
                c = own.c,
                picking = own.picking,
                query = own.query,
                candidates = candidates.filter { c ->
                    own.query.isBlank() || PointSearch.normalize(c.name).contains(PointSearch.normalize(own.query.trim()))
                },
                result = result,
            )
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ThreePointAngleUi())

    /** [lineId] limita los puntos a los vértices de esa línea. */
    fun start(projectId: String, lineId: String? = null) {
        scope.value = projectId to lineId
    }

    fun openPicker(slot: AnglePoint) {
        own.value = own.value.copy(picking = slot, query = "")
    }

    fun setQuery(query: String) {
        own.value = own.value.copy(query = query)
    }

    fun closePicker() {
        own.value = own.value.copy(picking = null, query = "")
    }

    /** Elige el punto de [id] para el punto que se está eligiendo; el nombre se lee del punto, no de la lista del selector. */
    fun choose(id: String) {
        val slot = own.value.picking ?: return
        viewModelScope.launch {
            val point = points.point(id).first() ?: return@launch
            val option = AnglePointOption(point.id, point.name)
            own.update { current ->
                val chosen = when (slot) {
                    AnglePoint.A -> current.copy(a = option)
                    AnglePoint.B -> current.copy(b = option)
                    AnglePoint.C -> current.copy(c = option)
                }
                if (current.picking == slot) chosen.copy(picking = null, query = "") else chosen
            }
        }
    }

    /** Elige cuadrícula o terreno, para todos los proyectos (RF-9). */
    fun setMode(mode: DistanceMode) {
        viewModelScope.launch { preferences.setDistanceMode(mode) }
    }

    private fun Point.toVertex(crs: ProjectCrs): GeometryVertex {
        val geographic = !crs.isUtm
        return GeometryVertex(
            easting = if (geographic) null else coordinates.easting,
            northing = if (geographic) null else coordinates.northing,
            height = coordinates.height,
            latitudeDeg = coordinates.ground.latitudeDeg,
            longitudeDeg = coordinates.ground.longitudeDeg,
            ellipsoidalHeightM = coordinates.ground.ellipsoidalHeightM,
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
