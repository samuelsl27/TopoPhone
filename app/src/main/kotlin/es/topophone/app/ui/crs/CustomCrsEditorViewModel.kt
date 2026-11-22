package es.topophone.app.ui.crs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.CrsProbe
import es.topophone.core.geodesy.catalog.CustomBuildResult
import es.topophone.core.geodesy.catalog.CustomCrsBuilder
import es.topophone.core.geodesy.catalog.CustomCrsDraft
import es.topophone.core.geodesy.catalog.CustomError
import es.topophone.core.geodesy.catalog.CustomFields
import es.topophone.core.geodesy.catalog.GridEntry
import es.topophone.core.geodesy.catalog.GridKind
import es.topophone.core.geodesy.catalog.ProbeResult
import es.topophone.core.geodesy.catalog.frame
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.CrsDefinition
import es.topophone.core.model.project.CustomCrs
import es.topophone.core.model.project.CustomHeight
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.survey.LibraryResources
import es.topophone.data.crs.CustomCrsRepository
import es.topophone.data.crs.CustomCrsSave
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** De dónde parte un SC personalizado nuevo (S035/RF-1). */
sealed interface EditorStart {
    data object Empty : EditorStart

    /** Un SC de la biblioteca: sus elipsoide, proyección, parámetros y transformación. */
    data class Library(val definition: CrsDefinition, val height: CustomHeight = CustomHeight.Ellipsoidal) : EditorStart

    /** Un SC de Mis SC. */
    data class Mine(val crs: CustomCrs) : EditorStart
}

/**
 * Qué edita el editor: un SC nuevo (de [start]) o el de Mis SC [editing]; [corrections] son las del proyecto (o las de los ajustes) y fijan hasta qué
 * datum lleva la transformación (S035/RF-3).
 */
data class CustomCrsEditorRequest(
    val start: EditorStart = EditorStart.Empty,
    val editing: CustomCrs? = null,
    val corrections: CorrectionsDatum = CorrectionsDatum.ETRS89,
)

/** Las coordenadas de prueba que escribe el usuario y lo que dan (S035/RF-7). */
data class ProbeUi(
    val latitude: String = "",
    val longitude: String = "",
    val first: String = "",
    val second: String = "",
    val toCrs: ProbeResult? = null,
    val fromCrs: ProbeResult? = null,
)

data class CustomCrsEditorUiState(
    val request: CustomCrsEditorRequest? = null,
    val form: CustomCrsForm = CustomCrsForm(),
    /** Los campos que fallan: lo que dice el núcleo y los textos que no son números (S035/RF-6). */
    val errors: Map<String, CustomError> = emptyMap(),
    /** Se pulsó «Guardar» con errores: ahora también se marcan los campos que faltan. */
    val attempted: Boolean = false,
    /** Las rejillas (de datum o de geoide) que el SC usa y no están instaladas (S035/RF-8). */
    val missingGrids: List<GridEntry> = emptyList(),
    val nameTaken: Boolean = false,
    val saved: CustomCrs? = null,
    val probe: ProbeUi = ProbeUi(),
    val datumGrids: List<GridEntry> = emptyList(),
    val geoidGrids: List<GridEntry> = emptyList(),
) {
    val frame get() = (request?.corrections ?: CorrectionsDatum.ETRS89).frame()

    /** Los errores que se enseñan: sin los de campos sin escribir hasta que se intenta guardar. */
    val shownErrors: Map<String, CustomError>
        get() = if (attempted) errors else errors.filterValues { it != CustomError.REQUIRED }

    val editing: Boolean get() = request?.editing != null
}

/**
 * El editor de SC personalizados (S035/RF-1 a RF-8): valida cada cambio con el núcleo, calcula las coordenadas de prueba fuera del hilo de la interfaz
 * (RNF: menos de 100 ms por cambio de campo) y guarda en Mis SC.
 */
@HiltViewModel
class CustomCrsEditorViewModel @Inject constructor(private val repository: CustomCrsRepository) : ViewModel() {
    private val catalog = CrsCatalog.bundled
    private val mutable = MutableStateFlow(
        CustomCrsEditorUiState(
            datumGrids = catalog.grids.values.filter { it.kind == GridKind.HORIZONTAL }.sortedBy { it.name },
            geoidGrids = catalog.grids.values.filter { it.kind == GridKind.GEOID }.sortedBy { it.name },
        ),
    )
    val state: StateFlow<CustomCrsEditorUiState> = mutable.asStateFlow()
    private var probeJob: Job? = null

    /** Abre el editor con [request]; no hace nada si ya está abierto con ese mismo (al girar la pantalla). */
    fun open(request: CustomCrsEditorRequest) {
        if (mutable.value.request == request) return
        val draft = when {
            request.editing != null -> CustomCrsDraft.of(request.editing)
            else -> draftOf(request.start)
        }
        mutable.update {
            it.copy(request = request, form = CustomCrsFormMapper.of(draft), attempted = false, nameTaken = false, saved = null)
        }
        revalidate()
    }

    private fun draftOf(start: EditorStart): CustomCrsDraft = when (start) {
        EditorStart.Empty -> CustomCrsDraft.empty()
        is EditorStart.Library -> CustomCrsDraft.of(start.definition, start.height)
        is EditorStart.Mine -> CustomCrsDraft.of(start.crs).copy(name = "")
    }

    /** Parte de otro SC (S035/RF-1): se sustituye lo escrito, salvo el nombre. */
    fun startFrom(start: EditorStart) {
        val name = mutable.value.form.name
        val form = CustomCrsFormMapper.of(draftOf(start)).copy(name = name)
        mutable.update { it.copy(form = form, nameTaken = false) }
        revalidate()
    }

    fun update(change: (CustomCrsForm) -> CustomCrsForm) {
        mutable.update { it.copy(form = change(it.form), nameTaken = false) }
        revalidate()
    }

    fun setProbe(change: (ProbeUi) -> ProbeUi) {
        mutable.update { it.copy(probe = change(it.probe).copy(toCrs = null, fromCrs = null)) }
        recomputeProbe()
    }

    private fun revalidate() {
        val current = mutable.value
        val parsed = CustomCrsFormMapper.toDraft(current.form, current.frame)
        val errors = CustomCrsBuilder.validate(parsed.draft, catalog) + parsed.textErrors
        val missing = if (errors.isEmpty()) {
            (
                CustomCrsBuilder.build(
                    parsed.draft,
                    catalog,
                    LibraryResources.current::isInstalled,
                ) as? CustomBuildResult.NeedsGrids
                )?.grids.orEmpty()
        } else {
            emptyList()
        }
        mutable.update { it.copy(errors = errors, missingGrids = missing) }
        recomputeProbe()
    }

    private fun recomputeProbe() {
        probeJob?.cancel()
        val current = mutable.value
        if (current.errors.isNotEmpty()) {
            mutable.update { it.copy(probe = it.probe.copy(toCrs = null, fromCrs = null)) }
            return
        }
        val probe = current.probe
        val parsed = CustomCrsFormMapper.toDraft(current.form, current.frame)
        probeJob = viewModelScope.launch {
            val results = withContext(Dispatchers.Default) {
                val definition = (CustomCrsBuilder.build(parsed.draft, catalog, LibraryResources.current::isInstalled)).let {
                    when (it) {
                        is CustomBuildResult.Ready -> it.definition
                        is CustomBuildResult.NeedsGrids -> it.definition
                        is CustomBuildResult.Invalid -> return@withContext null
                    }
                }
                val grids = LibraryResources.current.grids
                val lat = CustomCrsFormMapper.number(probe.latitude)
                val lon = CustomCrsFormMapper.number(probe.longitude)
                val first = CustomCrsFormMapper.number(probe.first)
                val second = CustomCrsFormMapper.number(probe.second)
                val to = if (lat != null && lon != null) CrsProbe.toCrs(definition, grids, lat, lon) else null
                val from = if (first != null && second != null) CrsProbe.fromCrs(definition, grids, first, second) else null
                to to from
            } ?: return@launch
            mutable.update { it.copy(probe = it.probe.copy(toCrs = results.first, fromCrs = results.second)) }
        }
    }

    /** Guarda el SC en Mis SC (S035/RF-6, RF-10): si falla algún campo, solo se marcan. */
    fun save() {
        val current = mutable.value
        if (current.errors.isNotEmpty()) {
            mutable.update { it.copy(attempted = true) }
            return
        }
        val parsed = CustomCrsFormMapper.toDraft(current.form, current.frame)
        val definition = when (val built = CustomCrsBuilder.build(parsed.draft, catalog, LibraryResources.current::isInstalled)) {
            is CustomBuildResult.Ready -> built.definition
            is CustomBuildResult.NeedsGrids -> built.definition
            is CustomBuildResult.Invalid -> return
        }
        viewModelScope.launch {
            val editing = current.request?.editing
            val result = if (editing == null) {
                repository.create(definition, parsed.draft.height)
            } else {
                repository.update(editing.id, definition, parsed.draft.height)
            }
            when (result) {
                is CustomCrsSave.Saved -> mutable.update { it.copy(saved = result.crs) }
                CustomCrsSave.NameTaken -> mutable.update { it.copy(nameTaken = true, attempted = true) }
                CustomCrsSave.EmptyName -> mutable.update { it.copy(attempted = true) }
                CustomCrsSave.NotFound -> mutable.update { it.copy(nameTaken = false) }
            }
        }
    }

    /** El SC del proyecto que sale de lo guardado, para devolverlo al formulario que abrió el editor (S035/RF-9, RF-11). */
    fun projectCrsOf(saved: CustomCrs, corrections: CorrectionsDatum): LibraryCrs = saved.toProjectCrs(corrections)

    /** Las claves de los campos de proyección con error, para que la pantalla los marque. */
    fun hasError(field: String): Boolean = mutable.value.shownErrors.containsKey(field)

    companion object {
        /** El campo del nombre repetido es el mismo que el del nombre vacío. */
        const val NAME_FIELD = CustomFields.NAME
    }
}

/** El SC elegido en la biblioteca, para partir de él: su definición y su altura (S035/RF-1). */
fun LibraryCrs.asEditorStart(): EditorStart.Library = EditorStart.Library(
    definition,
    when {
        geoidGrid != null -> CustomHeight.Geoid(geoidGrid.orEmpty())
        geoidConstantM != null -> CustomHeight.ConstantGeoid(geoidConstantM ?: 0.0)
        else -> CustomHeight.Ellipsoidal
    },
)

/** Un punto de partida del editor a partir de unas coordenadas de prueba por defecto: el centro del proyecto si lo hay. */
fun defaultProbeFor(centre: GeoPoint?): ProbeUi = ProbeUi(
    latitude = centre?.latitudeDeg?.toString().orEmpty(),
    longitude = centre?.longitudeDeg?.toString().orEmpty(),
)
