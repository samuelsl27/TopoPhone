package es.topophone.app.ui.cogo

import es.topophone.core.model.format.DistanceInput
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointSearch
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.survey.TerrainView
import es.topophone.core.survey.cogo.CogoPoint
import es.topophone.data.point.PointRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Lo que el usuario eligió para un punto de entrada de COGO: uno del proyecto o un Este y un Norte escritos (S038/RF-3). */
sealed interface CogoSelection {
    data class OfProject(val pointId: String, val name: String) : CogoSelection

    /** Los textos tal como se escriben, en la unidad lineal del proyecto. */
    data class Typed(val easting: String = "", val northing: String = "") : CogoSelection
}

/** El valor de un punto de entrada ya resuelto con el estado de ahora (S038/RF-3, RF-28). */
sealed interface CogoSlotValue {
    /** Nada elegido. */
    data object Empty : CogoSlotValue

    /** Un punto con sus coordenadas; [name] es el del punto del proyecto, o null si se escribió a mano. */
    data class Found(val point: CogoPoint, val name: String?) : CogoSlotValue

    /** Un Este y un Norte escritos a medias o que no son números. */
    data object Incomplete : CogoSlotValue

    /** El punto elegido ya no está activo en el proyecto (borrado o en la papelera, S038/RF-28). */
    data class Missing(val name: String) : CogoSlotValue
}

/** Un punto del proyecto que se puede elegir. */
data class CogoCandidate(val id: String, val name: String, val hasHeight: Boolean)

/** El selector de puntos abierto: para qué entrada, lo escrito en el buscador y los puntos que cumplen. */
data class CogoPickerUi(val slot: String? = null, val query: String = "", val candidates: List<CogoCandidate> = emptyList())

/** El punto del proyecto como entrada de COGO; null si no tiene Este y Norte (SC geográfico). */
internal fun Point.toCogoPoint(): CogoPoint? {
    val east = coordinates.easting ?: return null
    val north = coordinates.northing ?: return null
    val ground = coordinates.ground
    return CogoPoint(east, north, coordinates.height, ground.latitudeDeg, ground.longitudeDeg, ground.ellipsoidalHeightM)
}

/** El punto con su Este y su Norte de terreno; en cuadrícula, el mismo (S058/RF-9). */
internal fun CogoPoint.inTerrain(view: TerrainView): CogoPoint = if (!view.isTerrain) {
    this
} else {
    view.coordinates(easting, northing).let { copy(easting = it.easting, northing = it.northing) }
}

/**
 * Los puntos de entrada de un cálculo COGO (S038/RF-3): cada entrada tiene un nombre (`slot`) y se elige de la lista del proyecto, con
 * búsqueda, o se escribe su Este y su Norte. Un punto del proyecto se lee con su estado actual, de modo que editarlo recalcula (RF-27) y
 * si desaparece se dice (RF-28). Los usan los tres cálculos.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CogoInputs(private val points: PointRepository, private val scope: CoroutineScope) {
    private data class Own(val selections: Map<String, CogoSelection> = emptyMap(), val picking: String? = null, val query: String = "")

    private val own = MutableStateFlow(Own())

    private val selectionsState = MutableStateFlow<Map<String, CogoSelection>>(emptyMap())

    /** Lo elegido para cada entrada. */
    val selections: StateFlow<Map<String, CogoSelection>> = selectionsState

    private fun update(block: (Own) -> Own) {
        own.update(block)
        selectionsState.value = own.value.selections
    }

    fun open(slot: String) = update { it.copy(picking = slot, query = "") }

    fun close() = update { it.copy(picking = null, query = "") }

    fun setQuery(query: String) = update { it.copy(query = query) }

    /** Elige el punto de [id] para la entrada que se está eligiendo; el nombre se lee del punto. */
    fun chooseProject(id: String) {
        val slot = own.value.picking ?: return
        scope.launch {
            val point = points.point(id).first() ?: return@launch
            select(slot, CogoSelection.OfProject(point.id, point.name))
        }
    }

    /** Escribe el Este y el Norte de [slot] (en la unidad lineal del proyecto). */
    fun setTyped(slot: String, easting: String, northing: String) = select(slot, CogoSelection.Typed(easting, northing))

    fun select(slot: String, selection: CogoSelection?) = update {
        val map = if (selection == null) it.selections - slot else it.selections + (slot to selection)
        it.copy(selections = map, picking = if (it.picking == slot) null else it.picking, query = if (it.picking == slot) "" else it.query)
    }

    /** Intercambia dos entradas (S038/RF-9). */
    fun swap(first: String, second: String) = update {
        val a = it.selections[first]
        val b = it.selections[second]
        var map = it.selections - first - second
        if (b != null) map = map + (first to b)
        if (a != null) map = map + (second to a)
        it.copy(selections = map)
    }

    /** Sustituye todas las entradas, al recuperar un borrador (S038/RF-27). */
    fun restore(selections: Map<String, CogoSelection>) = update { it.copy(selections = selections) }

    /** El selector abierto con los puntos del proyecto que cumplen la búsqueda; solo se leen mientras está abierto. */
    fun picker(projectId: Flow<String?>): Flow<CogoPickerUi> = combine(
        projectId,
        own.map { Triple(it.picking != null, it.picking, it.query) }.distinctUntilChanged(),
    ) { id, picking -> id to picking }.flatMapLatest { (id, picking) ->
        val (open, slot, query) = picking
        if (id == null || !open) {
            flowOf(CogoPickerUi())
        } else {
            points.summaries(id).map { all ->
                val needle = PointSearch.normalize(query.trim())
                CogoPickerUi(
                    slot,
                    query,
                    all.filter {
                        needle.isEmpty() || PointSearch.normalize(it.name).contains(needle)
                    }.map { CogoCandidate(it.id, it.name, it.hasHeight) },
                )
            }
        }
    }

    /** Cada entrada resuelta con el estado de ahora de su punto, en la unidad lineal del proyecto. */
    fun values(unit: Flow<LinearUnit>, terrain: Flow<TerrainView> = flowOf(TerrainView.GRID)): Flow<Map<String, CogoSlotValue>> =
        combine(selectionsState, unit, terrain) { map, u, view -> Triple(map, u, view) }
            .flatMapLatest { (map, u, view) ->
                if (map.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    val slots = map.keys.toList()
                    combine(slots.map { resolve(map.getValue(it), u, view) }) { values -> slots.zip(values.toList()).toMap() }
                }
            }

    /** S058/RF-10: con el proyecto en terreno se calcula en terreno: lo escrito ya lo es y los puntos del proyecto se pasan a él. */
    private fun resolve(selection: CogoSelection, unit: LinearUnit, view: TerrainView): Flow<CogoSlotValue> = when (selection) {
        is CogoSelection.Typed -> flowOf(typed(selection, unit))
        is CogoSelection.OfProject -> points.point(selection.pointId).map { point ->
            val cogo = point?.takeIf { it.deletedAtMs == null }?.toCogoPoint()?.inTerrain(view)
            if (cogo == null) CogoSlotValue.Missing(selection.name) else CogoSlotValue.Found(cogo, point.name)
        }
    }

    private fun typed(selection: CogoSelection.Typed, unit: LinearUnit): CogoSlotValue {
        val east = DistanceInput.parseSigned(selection.easting, unit)
        val north = DistanceInput.parseSigned(selection.northing, unit)
        return if (east == null || north == null) CogoSlotValue.Incomplete else CogoSlotValue.Found(CogoPoint(east, north), null)
    }
}
