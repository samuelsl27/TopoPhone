package es.topophone.app.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.codes.ProjectLibraryChoice
import es.topophone.app.ui.crs.CrsLibraryHost
import es.topophone.app.ui.crs.CrsLibraryRequest
import es.topophone.app.ui.crs.CustomCrsFormMapper
import es.topophone.app.ui.crs.HiltCrsLibraryHost
import es.topophone.app.ui.map.MapEntryPoint
import es.topophone.app.ui.map.MapLibreMap
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.model.codes.LibraryChoice
import es.topophone.core.model.format.AngleAxis
import es.topophone.core.model.format.AngleInput
import es.topophone.core.model.format.NumberInput
import es.topophone.core.model.map.MapSettings
import es.topophone.core.model.map.MapViewport
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CentralPoint
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.DatumCheck
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.NameError
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.project.ProjectDraft
import es.topophone.core.model.project.SpainCrs
import es.topophone.core.model.project.TerrainPlane
import es.topophone.core.model.project.isCustom
import es.topophone.core.survey.CentralPointRejection
import es.topophone.core.survey.CentralPointResolver
import es.topophone.core.survey.CentralPointResult
import es.topophone.core.survey.CrsChange
import es.topophone.core.survey.CrsChangePlan
import es.topophone.core.survey.TerrainError
import es.topophone.core.survey.TerrainField
import es.topophone.core.survey.TerrainPlaneValidator
import es.topophone.core.survey.TerrainProposal
import es.topophone.core.survey.TerrainView
import es.topophone.data.codes.CodeLibraryRepository
import es.topophone.data.codes.LibrarySummary
import es.topophone.data.crs.CrsChangeResult
import es.topophone.data.crs.ProjectCrsHistoryRepository
import es.topophone.data.crs.TerrainChange
import es.topophone.data.map.MapPreferences
import es.topophone.data.project.ProjectPreferences
import es.topophone.data.project.ProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val FORM_NAME_TAG = "project_form_name"
const val FORM_AUTHOR_TAG = "project_form_author"
const val FORM_DESCRIPTION_TAG = "project_form_description"
const val FORM_SAVE_TAG = "project_form_save"
const val DATUM_WARNING_TAG = "project_datum_warning"
const val ZONE_PROPOSED_TAG = "project_zone_proposed"
const val CRS_LIBRARY_OPEN_TAG = "project_crs_library_open"
const val CRS_LIBRARY_CHANGE_TAG = "project_crs_library_change"
const val CRS_LIBRARY_SPAIN_TAG = "project_crs_library_spain"
const val CRS_LIBRARY_SUMMARY_TAG = "project_crs_library_summary"

fun crsOptionTag(crs: ProjectCrs) = "project_crs_${crs.name}"

fun geodeticDatumOptionTag(datum: GeodeticDatum) = "project_geodetic_datum_${datum.name}"

fun heightOptionTag(height: HeightSystem) = "project_height_${height.name}"

fun linearOptionTag(unit: LinearUnit) = "project_linear_${unit.name}"

fun angularOptionTag(unit: AngularUnit) = "project_angular_${unit.name}"

fun datumOptionTag(datum: CorrectionsDatum) = "project_datum_${datum.name}"

/**
 * Estado del formulario de proyecto (S006/RF-5 a RF-12); [savedId] cuando ya se ha guardado y [proposedCrs]
 * cuando el SC de un proyecto nuevo se ha propuesto por la posición del receptor (S008/RF-11).
 */
data class ProjectFormUiState(
    val draft: ProjectDraft = ProjectDefaults().toDraft(),
    val loaded: Boolean = false,
    val editing: Boolean = false,
    val error: NameError? = null,
    val savedId: String? = null,
    val proposedCrs: ProjectCrs? = null,
    /** Por qué no se fijó el punto central en el último intento (S006/RF-34). */
    val centralPointError: CentralPointRejection? = null,
    /** El mapa base elegido en Ajustes, para el selector del punto central (S006/RF-33). */
    val mapSettings: MapSettings = MapSettings(),
    /** Las bibliotecas de códigos que se pueden vincular y el nombre de la que el proyecto lleva ya (S023/RF-19, RF-21). */
    val libraries: List<LibrarySummary> = emptyList(),
    val linkedLibraryName: String? = null,
    /** El SC y las alturas del proyecto al abrirlo para editarlo: contra ellos se mira si se cambia de SC (S035/RF-17 a RF-20). */
    val originalCrs: ProjectCrs? = null,
    val originalHeightSystem: HeightSystem? = null,
    /** Los puntos que el SC nuevo no calcula, a la espera de que el usuario decida continuar o cancelar (S035/RF-19). */
    val crsChangePlan: CrsChangePlan? = null,
    /** Se intentó elegir un SC de otro datum (S035/RF-17). */
    val otherDatum: Boolean = false,
    /** El plano de terreno que se está escribiendo (S058/RF-1 a RF-5); con [TerrainForm.terrain] en falso el proyecto va en cuadrícula. */
    val terrainForm: TerrainForm = TerrainForm(),
    /** El plano de terreno con que se abrió el proyecto para editarlo (S058/RF-19). */
    val originalTerrain: TerrainPlane? = null,
)

/**
 * Lo que el usuario escribe del plano de terreno en el formulario (S058/RF-1 a RF-5): los números como texto, el factor que se propone en el origen y los
 * errores, que solo se enseñan tras intentar guardar ([attempted]) salvo los de un número que no se entiende.
 */
data class TerrainForm(
    val terrain: Boolean = false,
    val originLatitude: String = "",
    val originLongitude: String = "",
    val factor: String = "",
    val proposed: Double? = null,
    val errors: Map<TerrainField, TerrainError> = emptyMap(),
    val attempted: Boolean = false,
    /** El proyecto todavía no tiene centro: el origen hay que escribirlo (S058/RF-3). */
    val noCenter: Boolean = false,
) {
    /** Los errores que se enseñan: sin los de lo que falta hasta intentar guardar. */
    val shownErrors: Map<TerrainField, TerrainError> get() = if (attempted) errors else errors.filterValues { it != TerrainError.REQUIRED }
}

/** Acciones del formulario de proyecto. */
class ProjectFormActions(
    val onChange: (ProjectDraft) -> Unit = {},
    val onSave: () -> Unit = {},
    val onBack: () -> Unit = {},
    /** Este y Norte, o latitud y longitud, tal como se escribieron (S006/RF-33). */
    val onCentralTyped: (String, String) -> Unit = { _, _ -> },
    val onCentralRemove: () -> Unit = {},
    /** La posición actual del receptor o de una reproducción (S006/RF-33). */
    val onCentralPosition: () -> Unit = {},
    /** Un toque del selector del mapa: dónde cae, o por qué no vale (S006/RF-33, RF-34). */
    val onCentralTap: (Double, Double) -> CentralPointResult = { _, _ -> CentralPointResult.Rejected(CentralPointRejection.NO_POSITION) },
    val onCentralPicked: (CentralPoint) -> Unit = {},
    /** Dónde empieza el mapa del selector (S006/RF-33). */
    val pickerStart: () -> MapViewport? = { null },
    /** Continuar con el cambio de SC aunque algunos puntos queden sin coordenadas (S035/RF-19). */
    val onConfirmCrsChange: () -> Unit = {},
    val onCancelCrsChange: () -> Unit = {},
    /** Se eligió un SC de otro datum: no se aplica y se avisa (S035/RF-17). */
    val onOtherDatum: () -> Unit = {},
    /** Elegir cuadrícula (falso) o terreno (verdadero) (S058/RF-1). */
    val onTerrainMode: (Boolean) -> Unit = {},
    /** Cambia el origen (latitud y longitud) o el factor, tal como se escriben (S058/RF-3). */
    val onTerrainText: (latitude: String, longitude: String, factor: String) -> Unit = { _, _, _ -> },
    val onTerrainUseProposal: () -> Unit = {},
    val onTerrainUseCenter: () -> Unit = {},
)

/**
 * Proyecto nuevo, desde los valores por defecto de Ajustes (S006/RF-5, RF-6, RF-12) con el huso de la posición
 * del receptor (S008/RF-11), o editado (S006/RF-14, RF-15).
 */
@HiltViewModel
class ProjectFormViewModel @Inject constructor(
    private val repository: ProjectRepository,
    private val preferences: ProjectPreferences,
    private val session: ReceiverSession,
    private val mapPreferences: MapPreferences,
    private val codeLibraries: CodeLibraryRepository,
    private val crsHistory: ProjectCrsHistoryRepository,
    private val centers: ProjectCenterProvider,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ProjectFormUiState())
    val state: StateFlow<ProjectFormUiState> = mutableState.asStateFlow()

    private var projectId: String? = null
    private var folderId: String? = null
    private var started = false
    private val resolver = CentralPointResolver()

    private companion object {
        const val PICKER_ZOOM = 16.0
    }

    fun start(projectId: String?, folderId: String?) {
        if (started) return
        started = true
        viewModelScope.launch { mapPreferences.settings.collect { settings -> mutableState.update { it.copy(mapSettings = settings) } } }
        this.projectId = projectId
        this.folderId = folderId
        viewModelScope.launch {
            val project = projectId?.let { repository.project(it) }
            val libraries = codeLibraries.libraries().first()
            val loaded = if (project != null) {
                ProjectFormUiState(draft = project.toDraft(), loaded = true, editing = true) // S006/RF-14
                    .copy(
                        linkedLibraryName = codeLibraries.projectCopy(project.id).first()?.name,
                        originalCrs = project.crs,
                        originalHeightSystem = project.heightSystem,
                        originalTerrain = project.terrain,
                        terrainForm = formOf(project.terrain),
                    )
            } else {
                newProject(preferences.defaults.first().toDraft().withExistingLibrary(libraries)) // S006/RF-6, S023/RF-19
            }
            mutableState.update { loaded.copy(mapSettings = it.mapSettings, libraries = libraries) }
        }
    }

    /** Una biblioteca de Ajustes que ya no existe cuenta como ninguna (S023/RF-19). */
    private fun ProjectDraft.withExistingLibrary(libraries: List<LibrarySummary>): ProjectDraft {
        val choice = libraryChoice
        return if (choice is LibraryChoice.Library &&
            libraries.none { it.id == choice.id }
        ) {
            copy(libraryChoice = LibraryChoice.Keep)
        } else {
            this
        }
    }

    /** Con la posición del receptor y un SC UTM por defecto, el SC UTM de su huso y territorio (S008/RF-11). */
    private fun newProject(defaults: ProjectDraft): ProjectFormUiState {
        val snapshot = session.state.value
        val fix = snapshot.gga?.takeIf { snapshot.connection is ConnectionState.Receiving && it.hasPosition }
        val proposed = fix?.let { ProjectCrs.suggested(defaults.crs, it.latitudeDeg, it.longitudeDeg) }
            ?: return ProjectFormUiState(draft = defaults, loaded = true)
        return ProjectFormUiState(draft = defaults.withProposedCrs(proposed), loaded = true, proposedCrs = proposed)
    }

    fun update(draft: ProjectDraft) {
        mutableState.update { it.copy(draft = draft, error = null, centralPointError = null, otherDatum = false) }
        revalidateTerrain() // S058/RF-2, RF-5: el plano tiene que valer en el SC nuevo
    }

    private fun formOf(plane: TerrainPlane?): TerrainForm = if (plane == null) {
        TerrainForm()
    } else {
        TerrainForm(
            terrain = true,
            originLatitude = plane.originLatitudeDeg.toString(),
            originLongitude = plane.originLongitudeDeg.toString(),
            factor = plane.factor.toString(),
        )
    }

    /** Cuadrícula o terreno (S058/RF-1): al elegir terreno se propone el origen (el centro del proyecto) y el factor combinado en él (RF-3). */
    fun setTerrainMode(terrain: Boolean) {
        if (!terrain) {
            mutableState.update { it.copy(terrainForm = TerrainForm(), draft = it.draft.copy(terrain = null)) }
            return
        }
        mutableState.update { it.copy(terrainForm = it.terrainForm.copy(terrain = true, attempted = false)) }
        viewModelScope.launch { fillTerrainDefaults(keepWritten = true) }
    }

    /** El origen del proyecto (su centro) y el factor propuesto en él; con [keepWritten] no pisa lo que el usuario ya escribió. */
    private suspend fun fillTerrainDefaults(keepWritten: Boolean) {
        val state = mutableState.value
        val center = centers.centerOf(projectId, state.draft.centralPoint)
        val form = state.terrainForm
        val lat = if (keepWritten && form.originLatitude.isNotBlank()) form.originLatitude else center?.latitudeDeg?.toString().orEmpty()
        val lon = if (keepWritten && form.originLongitude.isNotBlank()) form.originLongitude else center?.longitudeDeg?.toString().orEmpty()
        mutableState.update { it.copy(terrainForm = it.terrainForm.copy(noCenter = center == null)) }
        setTerrainText(
            lat,
            lon,
            if (keepWritten &&
                form.factor.isNotBlank()
            ) {
                form.factor
            } else {
                ""
            },
            proposeFactor = form.factor.isBlank() || !keepWritten,
        )
    }

    /** Lo que se escribe del origen y del factor; se valida en el acto (S058/RF-4, RF-5). */
    fun setTerrainText(latitude: String, longitude: String, factor: String) =
        setTerrainText(latitude, longitude, factor, proposeFactor = false)

    private fun setTerrainText(latitude: String, longitude: String, factor: String, proposeFactor: Boolean) {
        viewModelScope.launch {
            val state = mutableState.value
            val lat = CustomCrsFormMapper.number(latitude)
            val lon = CustomCrsFormMapper.number(longitude)
            val proposed = if (lat != null && lon != null) {
                TerrainProposal.factorAt(lat, lon, centers.meanEllipsoidalHeight(projectId), state.draft.crs)
            } else {
                null
            }
            val text = if (proposeFactor && factor.isBlank() && proposed != null) proposed.toString() else factor
            mutableState.update {
                it.copy(
                    terrainForm = it.terrainForm.copy(
                        originLatitude = latitude,
                        originLongitude = longitude,
                        factor = text,
                        proposed = proposed,
                    ),
                )
            }
            revalidateTerrain()
        }
    }

    /** Usa el factor propuesto en el origen (S058/RF-3). */
    fun useTerrainProposal() {
        val form = mutableState.value.terrainForm
        form.proposed?.let { setTerrainText(form.originLatitude, form.originLongitude, it.toString()) }
    }

    /** Vuelve a poner como origen el centro del proyecto (S058/RF-3). */
    fun useTerrainCenter() {
        viewModelScope.launch { fillTerrainDefaults(keepWritten = false) }
    }

    /** Valida lo escrito contra el SC del borrador y, si vale, lo pone como plano del borrador; si no, el borrador queda sin plano (S058/RF-4, RF-5). */
    private fun revalidateTerrain() {
        val state = mutableState.value
        val form = state.terrainForm
        if (!form.terrain) {
            mutableState.update { it.copy(draft = it.draft.copy(terrain = null)) }
            return
        }
        val lat = CustomCrsFormMapper.number(form.originLatitude)
        val lon = CustomCrsFormMapper.number(form.originLongitude)
        val factor = CustomCrsFormMapper.number(form.factor)
        val errors = linkedMapOf<TerrainField, TerrainError>()
        errors.putAll(TerrainPlaneValidator.validate(lat, lon, factor, state.draft.crs))
        // Un texto que no es un número se marca aunque no esté vacío.
        if (form.originLatitude.isNotBlank() && lat == null || form.originLongitude.isNotBlank() && lon == null) {
            errors[TerrainField.ORIGIN] = TerrainError.NOT_FINITE
        }
        if (form.factor.isNotBlank() && factor == null) errors[TerrainField.FACTOR] = TerrainError.NOT_FINITE
        val plane = if (errors.isEmpty() && lat != null && lon != null && factor != null) TerrainPlane(lat, lon, factor) else null
        mutableState.update { it.copy(terrainForm = it.terrainForm.copy(errors = errors), draft = it.draft.copy(terrain = plane)) }
    }

    /** Fija el punto central con lo escrito en el SC del proyecto; si no vale, no cambia nada y dice por qué (S006/RF-33, RF-34). */
    fun fixCentralTyped(first: String, second: String) {
        val draft = mutableState.value.draft
        val crs = draft.crs
        val projected = crs.isUtm
        val a = if (projected) {
            NumberInput.parse(
                first,
            )?.let(draft.linearUnit::toMetres)
        } else {
            AngleInput.parse(first, draft.angularUnit, AngleAxis.LATITUDE)
        }
        val b = if (projected) {
            NumberInput.parse(
                second,
            )?.let(draft.linearUnit::toMetres)
        } else {
            AngleInput.parse(second, draft.angularUnit, AngleAxis.LONGITUDE)
        }
        val result = if (a == null || b == null) {
            CentralPointResult.Rejected(CentralPointRejection.INVALID_NUMBER)
        } else {
            // S058/RF-10: con terreno, lo escrito es de terreno y el punto se calcula desde su cuadrícula.
            val grid = if (projected) TerrainView.of(draft.terrain, crs)?.toGrid(a, b) else null
            resolver.fromTyped(if (projected) EntryForm.PROJECT_CRS else EntryForm.GEOGRAPHIC, grid?.easting ?: a, grid?.northing ?: b, crs)
        }
        applyCentral(result)
    }

    /** Un toque del selector del mapa: el punto, si cae en el ámbito del SC del proyecto, o el motivo (S006/RF-33, RF-34). */
    fun resolveCentralTap(latitudeDeg: Double, longitudeDeg: Double): CentralPointResult =
        resolver.fromTap(latitudeDeg, longitudeDeg, mutableState.value.draft.crs)

    /** El punto aceptado en el selector del mapa (S006/RF-33). */
    fun setCentralPoint(point: CentralPoint) = applyCentral(CentralPointResult.Fixed(point))

    /** Donde empieza el mapa del selector: el punto central si lo hay; si no, la posición del receptor; si no, España entera. */
    fun pickerStart(): MapViewport? {
        mutableState.value.draft.centralPoint?.let { return MapViewport(it.latitudeDeg, it.longitudeDeg, PICKER_ZOOM) }
        val snapshot = session.state.value
        val fix = snapshot.gga?.takeIf { snapshot.connection is ConnectionState.Receiving && it.hasPosition } ?: return null
        return MapViewport(fix.latitudeDeg, fix.longitudeDeg, PICKER_ZOOM)
    }

    /** Fija el punto central con la posición actual del receptor o de una reproducción; sin ella, no cambia nada y dice por qué (S006/RF-33, RF-34). */
    fun fixCentralFromReceiver() {
        val snapshot = session.state.value
        val fix = snapshot.gga?.takeIf { snapshot.connection is ConnectionState.Receiving }
        applyCentral(resolver.fromFix(fix, mutableState.value.draft.crs))
    }

    /** Borra el punto central: el centro del proyecto vuelve a calcularse sin él (S006/RF-35). */
    fun clearCentralPoint() = mutableState.update { it.copy(draft = it.draft.copy(centralPoint = null), centralPointError = null) }

    private fun applyCentral(result: CentralPointResult) = mutableState.update {
        when (result) {
            is CentralPointResult.Fixed -> it.copy(draft = it.draft.copy(centralPoint = result.point), centralPointError = null)
            is CentralPointResult.Rejected -> it.copy(centralPointError = result.reason)
        }
    }

    fun save() {
        val draft = mutableState.value.draft
        val editingId = projectId
        val terrainForm = mutableState.value.terrainForm
        if (terrainForm.terrain && terrainForm.errors.isNotEmpty()) {
            // S058/RF-4, RF-5: con un plano que no vale no se guarda; se marcan los campos.
            mutableState.update { it.copy(terrainForm = it.terrainForm.copy(attempted = true)) }
            return
        }
        viewModelScope.launch {
            if (editingId != null && crsChanged() && !confirmedChange) {
                // S035/RF-19: antes de cambiar el SC se dice qué puntos no se calculan en el nuevo.
                val plan = crsHistory.preview(editingId, draft.crs, draft.heightSystem)
                if (plan != null && !plan.isClean) {
                    mutableState.update { it.copy(crsChangePlan = plan) }
                    return@launch
                }
            }
            commit(editingId, draft)
        }
    }

    private var confirmedChange = false

    private fun crsChanged(): Boolean {
        val state = mutableState.value
        return state.editing &&
            (
                state.draft.crs != state.originalCrs ||
                    state.draft.heightSystem != state.originalHeightSystem ||
                    state.draft.terrain != state.originalTerrain
                )
    }

    /** El usuario aceptó que algunos puntos queden sin coordenadas en el SC nuevo (S035/RF-19). */
    fun confirmCrsChange() {
        confirmedChange = true
        mutableState.update { it.copy(crsChangePlan = null) }
        save()
    }

    fun cancelCrsChange() {
        confirmedChange = false
        mutableState.update { it.copy(crsChangePlan = null) }
    }

    /** Se eligió un SC de otro datum: no se cambia nada y se avisa (S035/RF-17). */
    fun rejectOtherDatum() = mutableState.update { it.copy(otherDatum = true) }

    private suspend fun commit(editingId: String?, draft: ProjectDraft) {
        val state = mutableState.value
        val changed = editingId != null && crsChanged()
        // Con cambio de SC, el resto del formulario se guarda con el SC de antes y el cambio lo hace el historial, que recalcula los puntos (S035/RF-18, RF-20).
        val toSave = if (changed) {
            draft.copy(
                crs = state.originalCrs ?: draft.crs,
                heightSystem = state.originalHeightSystem ?: draft.heightSystem,
                terrain = state.originalTerrain,
            )
        } else {
            draft
        }
        val result = if (editingId != null) repository.update(editingId, toSave) else repository.create(draft, folderId)
        when (result) {
            is SaveResult.Invalid -> mutableState.update { it.copy(error = result.error) } // S006/RF-7, RF-14
            is SaveResult.Saved -> {
                if (editingId == null) repository.markOpened(result.id) // S006/RF-12: se abre al guardarlo
                if (changed && editingId != null) {
                    // S058/RF-19: el SC, las alturas y el plano de terreno cambian a la vez, por el historial.
                    when (crsHistory.apply(editingId, draft.crs, draft.heightSystem, TerrainChange.Set(draft.terrain))) {
                        CrsChangeResult.OtherDatum -> {
                            mutableState.update { it.copy(otherDatum = true) }
                            return
                        }
                        CrsChangeResult.InvalidTerrain -> {
                            mutableState.update { it.copy(terrainForm = it.terrainForm.copy(attempted = true)) }
                            return
                        }
                        else -> Unit
                    }
                }
                confirmedChange = false
                mutableState.update { it.copy(savedId = result.id) }
            }
        }
    }
}

/** Entrada del formulario: une el ViewModel con la navegación; al guardar, abre el proyecto. */
@Composable
fun ProjectFormRoute(
    projectId: String?,
    folderId: String?,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    navigation: SurveyNavigation,
    viewModel: ProjectFormViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.start(projectId, folderId) }
    val context = LocalContext.current
    val environment =
        remember { EntryPointAccessors.fromApplication(context.applicationContext, MapEntryPoint::class.java).mapEnvironment() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val saved = state.savedId
    LaunchedEffect(saved) {
        when {
            saved == null -> Unit
            state.editing -> navigation.onBack() // se vuelve a donde se estaba
            else -> navigation.onProjectSaved(saved)
        }
    }
    ProjectFormScreen(
        themeState,
        onToggleSun,
        state,
        ProjectFormActions(
            viewModel::update,
            viewModel::save,
            navigation.onBack,
            viewModel::fixCentralTyped,
            viewModel::clearCentralPoint,
            viewModel::fixCentralFromReceiver,
            viewModel::resolveCentralTap,
            viewModel::setCentralPoint,
            viewModel::pickerStart,
            viewModel::confirmCrsChange,
            viewModel::cancelCrsChange,
            viewModel::rejectOtherDatum,
            viewModel::setTerrainMode,
            viewModel::setTerrainText,
            viewModel::useTerrainProposal,
            viewModel::useTerrainCenter,
        ),
        map = { modifier, onReady -> MapLibreMap(environment, onReady, modifier) },
        library = HiltCrsLibraryHost,
    )
}

/** Formulario de proyecto nuevo o editado (S006/RF-5 a RF-11). */
@Composable
fun ProjectFormScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: ProjectFormUiState,
    actions: ProjectFormActions,
    /** El mapa del selector del punto central; sin él no se ofrece «Elegir en el mapa» (S006/RF-33). */
    map: MapSlot? = null,
    /** La biblioteca de SC (S034): sin ella no se ofrece elegir uno de fuera de España. */
    library: CrsLibraryHost? = null,
) {
    val draft = state.draft
    var picking by rememberSaveable { mutableStateOf(false) }
    var libraryOpen by rememberSaveable { mutableStateOf(false) }
    val libraryHooks = library?.let {
        CrsLibraryHooks(
            onOpen = { libraryOpen = true },
            onBackToSpain = {
                val spain = SpainCrs.ETRS89_UTM30
                if (state.editing && !sameDatumAsOriginal(state, spain)) actions.onOtherDatum() else actions.onChange(draft.withCrs(spain))
            },
            host = it,
        )
    }
    if (picking && map != null) {
        CentralPointPicker(
            themeState,
            onToggleSun,
            map,
            state.mapSettings,
            start = remember { actions.pickerStart() },
            current = draft.centralPoint,
            resolve = actions.onCentralTap,
            onAccept = {
                actions.onCentralPicked(it)
                picking = false
            },
            onCancel = { picking = false },
        )
        return
    }
    if (libraryOpen && library != null) {
        library.Flow(
            themeState,
            onToggleSun,
            CrsLibraryRequest(
                draft.correctionsDatum,
                draft.centralPoint?.let { GeoPoint(it.latitudeDeg, it.longitudeDeg) },
                (draft.crs as? LibraryCrs)?.geoidGrid,
            ),
            onChosen = { chosen ->
                if (state.editing && !sameDatumAsOriginal(state, chosen)) {
                    // S035/RF-17: al editar solo se cambia a un SC del mismo datum.
                    actions.onOtherDatum()
                    libraryOpen = false
                    return@Flow
                }
                actions.onChange(
                    draft.copy(
                        crs = chosen,
                        correctionsDatum = chosen.correctionsDatum, // S035: un SC personalizado fija el datum de sus correcciones
                        heightSystem = if (chosen.geoidGrid !=
                            null
                        ) {
                            HeightSystem.ORTHOMETRIC
                        } else {
                            HeightSystem.ELLIPSOIDAL
                        },
                    ),
                )
                libraryOpen = false
            },
            onDismiss = { libraryOpen = false },
        )
    }
    state.crsChangePlan?.let { CrsChangeDialog(it, actions) }
    SectionScaffold(
        title = stringResource(if (state.editing) R.string.project_edit else R.string.project_new),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = actions.onBack,
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = draft.name,
                onValueChange = { actions.onChange(draft.copy(name = it)) },
                label = { Text(stringResource(R.string.project_name)) },
                isError = state.error != null,
                supportingText = state.error?.let { error -> { Text(stringResource(nameErrorText(error))) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(FORM_NAME_TAG),
            )
            OutlinedTextField(
                value = draft.author,
                onValueChange = { actions.onChange(draft.copy(author = it)) },
                label = { Text(stringResource(R.string.project_author)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(FORM_AUTHOR_TAG),
            )
            OutlinedTextField(
                value = draft.description,
                onValueChange = { actions.onChange(draft.copy(description = it)) },
                label = { Text(stringResource(R.string.project_description)) },
                modifier = Modifier.fillMaxWidth().testTag(FORM_DESCRIPTION_TAG),
            )
            CrsChoices(
                draft.crs,
                draft.heightSystem,
                datumLocked = state.editing,
                zoneProposed = state.proposedCrs == draft.crs,
                otherDatum = state.otherDatum,
                library = libraryHooks,
                onCrs = { actions.onChange(draft.withCrs(it)) },
            ) { actions.onChange(draft.copy(heightSystem = it)) } // S006/RF-8, S008/RF-1 a RF-3, RF-11, RF-17, RF-19
            if (draft.crs.isProjected) TerrainSection(state.terrainForm, actions) // S058/RF-1, RF-2
            ChoiceGroup(stringResource(R.string.project_linear_unit), LinearUnit.entries, draft.linearUnit, ::linearOptionTag, {
                linearUnitLabel(it)
            }) {
                actions.onChange(draft.copy(linearUnit = it))
            } // S006/RF-9
            ChoiceGroup(stringResource(R.string.project_angular_unit), AngularUnit.entries, draft.angularUnit, ::angularOptionTag, {
                angularUnitLabel(it)
            }) {
                actions.onChange(draft.copy(angularUnit = it))
            } // S006/RF-9
            ChoiceGroup(
                stringResource(
                    R.string.project_corrections_datum,
                ),
                CorrectionsDatum.entries,
                draft.correctionsDatum,
                ::datumOptionTag,
                {
                    datumLabel(it)
                },
                // S034/RF-11: la transformación de un SC de la biblioteca es para un datum de correcciones concreto.
                enabled = draft.crs !is LibraryCrs,
            ) {
                actions.onChange(draft.copy(correctionsDatum = it))
            } // S006/RF-10
            if (draft.crs is LibraryCrs) {
                Text(stringResource(R.string.crs_library_corrections_locked), style = MaterialTheme.typography.bodySmall)
            }
            // S006/RF-11, S008/RF-18
            if (DatumCheck.mismatch(draft.crs, draft.correctionsDatum)) DatumWarning(draft.crs, draft.correctionsDatum)
            CentralPointSection(
                draft.crs,
                draft.linearUnit,
                draft.angularUnit,
                draft.centralPoint,
                state.centralPointError,
                actions.onCentralTyped,
                actions.onCentralRemove,
                actions.onCentralPosition,
                onPick = if (map != null) ({ picking = true }) else null,
                terrain = rememberTerrainView(draft.terrain, draft.crs),
            ) // S006/RF-32, RF-33, RF-34, RF-35
            ProjectLibraryChoice(
                state.libraries,
                draft.libraryChoice,
                state.linkedLibraryName,
            ) { actions.onChange(draft.copy(libraryChoice = it)) } // S023/RF-19, RF-21
            Button(onClick = actions.onSave, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(FORM_SAVE_TAG)) {
                Text(stringResource(R.string.project_save))
            }
        }
    }
}

/**
 * Aviso de datum de las correcciones distinto del del SC, también en la vista de proyecto: el de
 * ITRF/WGS84 sobre ETRS89 con sus 0,9 m (S006/RF-11) y el general (S008/RF-18).
 */
@Composable
internal fun DatumWarning(crs: ProjectCrs, corrections: CorrectionsDatum) {
    val text = if (DatumCheck.itrfOverEtrs89(crs, corrections)) {
        stringResource(R.string.project_datum_warning)
    } else {
        stringResource(R.string.project_datum_warning_general, datumLabel(corrections), datumName(crs.datum))
    }
    Text(text, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(DATUM_WARNING_TAG))
}

/**
 * SC del proyecto en tres grupos: datum, proyección de ese datum (UTM en un huso o geográficas) y altura
 * (S008/RF-1 a RF-3). Con [datumLocked] (al editar) el datum no se puede cambiar (S008/RF-19), y con
 * [zoneProposed] se dice que la proyección es la propuesta por la posición (S008/RF-11).
 */
@Composable
internal fun CrsChoices(
    crs: ProjectCrs,
    heightSystem: HeightSystem,
    datumLocked: Boolean,
    zoneProposed: Boolean = false,
    otherDatum: Boolean = false,
    library: CrsLibraryHooks? = null,
    onCrs: (ProjectCrs) -> Unit,
    onHeight: (HeightSystem) -> Unit,
) {
    Text(stringResource(R.string.project_crs), style = MaterialTheme.typography.titleMedium)
    if (crs is LibraryCrs) {
        // S034/RF-13: un SC de la biblioteca se elige y se cambia desde su ficha; aquí solo se resume.
        LibraryCrsSummary(crs, heightSystem, library, canChange = true) // S035/RF-17: también al editar, a un SC del mismo datum
        if (otherDatum) OtherDatumNotice()
        return
    }
    ChoiceGroup(
        stringResource(R.string.project_geodetic_datum),
        GeodeticDatum.entries,
        crs.datum,
        ::geodeticDatumOptionTag,
        { datumOptionLabel(it) },
        enabled = !datumLocked,
    ) { onCrs((crs as? SpainCrs ?: SpainCrs.ETRS89_UTM30).closestIn(it)) }
    ChoiceGroup(
        stringResource(R.string.project_projection),
        ProjectCrs.entries.filter { it.datum == crs.datum },
        crs,
        ::crsOptionTag,
        { projectionLabel(it) },
    ) { onCrs(it) }
    if (zoneProposed) {
        Text(
            stringResource(R.string.project_zone_proposed),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag(ZONE_PROPOSED_TAG),
        )
    }
    ChoiceGroup(stringResource(R.string.project_height), crs.heightSystems, heightSystem, ::heightOptionTag, { heightLabel(it) }) {
        onHeight(it)
    }
    if (library != null) {
        OutlinedButton(
            onClick = library.onOpen,
            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(CRS_LIBRARY_OPEN_TAG),
        ) { Text(stringResource(R.string.crs_library_open)) } // S034/RF-1, S035/RF-17
    }
    if (otherDatum) OtherDatumNotice()
}

const val TERRAIN_MODE_TAG = "project_terrain_mode"
const val TERRAIN_LATITUDE_TAG = "project_terrain_latitude"
const val TERRAIN_LONGITUDE_TAG = "project_terrain_longitude"
const val TERRAIN_FACTOR_TAG = "project_terrain_factor"
const val TERRAIN_PROPOSED_TAG = "project_terrain_proposed"
const val TERRAIN_USE_PROPOSED_TAG = "project_terrain_use_proposed"
const val TERRAIN_USE_CENTER_TAG = "project_terrain_use_center"
const val TERRAIN_NO_CENTER_TAG = "project_terrain_no_center"

fun terrainOptionTag(terrain: Boolean) = if (terrain) "project_terrain_ground" else "project_terrain_grid"

const val CRS_OTHER_DATUM_TAG = "project_crs_other_datum"
const val CRS_CHANGE_CONTINUE_TAG = "project_crs_change_continue"
const val CRS_CHANGE_CANCEL_TAG = "project_crs_change_cancel"
const val CRS_CHANGE_DIALOG_TAG = "project_crs_change_dialog"

/** El texto de un error del plano de terreno (S058/RF-4, RF-5). */
@Composable
private fun terrainErrorText(field: TerrainField, error: TerrainError): String = stringResource(
    when {
        error == TerrainError.REQUIRED -> R.string.terrain_error_required
        error == TerrainError.NOT_FINITE -> R.string.terrain_error_not_finite
        error == TerrainError.OUT_OF_AREA -> R.string.terrain_error_out_of_area
        field == TerrainField.FACTOR -> R.string.terrain_error_factor_range
        else -> R.string.terrain_error_origin_range
    },
)

/** Cuadrícula o terreno, y con terreno el origen, el factor y el que se propone en el origen (S058/RF-1 a RF-5). */
@Composable
internal fun TerrainSection(form: TerrainForm, actions: ProjectFormActions) {
    Text(stringResource(R.string.terrain_title), style = MaterialTheme.typography.titleMedium)
    ChoiceGroup(
        stringResource(R.string.terrain_title),
        listOf(false, true),
        form.terrain,
        ::terrainOptionTag,
        { stringResource(if (it) R.string.terrain_ground else R.string.terrain_grid) },
    ) { actions.onTerrainMode(it) }
    if (!form.terrain) return
    val errors = form.shownErrors
    Text(stringResource(R.string.terrain_hint), style = MaterialTheme.typography.bodySmall)
    if (form.noCenter) {
        Text(
            stringResource(R.string.terrain_no_center),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag(TERRAIN_NO_CENTER_TAG),
        )
    }
    OutlinedTextField(
        value = form.originLatitude,
        onValueChange = { actions.onTerrainText(it, form.originLongitude, form.factor) },
        label = { Text(stringResource(R.string.terrain_origin_latitude)) },
        isError = errors.containsKey(TerrainField.ORIGIN),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        modifier = Modifier.fillMaxWidth().testTag(TERRAIN_LATITUDE_TAG),
    )
    OutlinedTextField(
        value = form.originLongitude,
        onValueChange = { actions.onTerrainText(form.originLatitude, it, form.factor) },
        label = { Text(stringResource(R.string.terrain_origin_longitude)) },
        isError = errors.containsKey(TerrainField.ORIGIN),
        supportingText = errors[TerrainField.ORIGIN]?.let { error -> { Text(terrainErrorText(TerrainField.ORIGIN, error)) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        modifier = Modifier.fillMaxWidth().testTag(TERRAIN_LONGITUDE_TAG),
    )
    OutlinedButton(
        onClick = actions.onTerrainUseCenter,
        modifier = Modifier.heightIn(min = MinTouchTarget).testTag(TERRAIN_USE_CENTER_TAG),
    ) {
        Text(stringResource(R.string.terrain_use_center))
    }
    OutlinedTextField(
        value = form.factor,
        onValueChange = { actions.onTerrainText(form.originLatitude, form.originLongitude, it) },
        label = { Text(stringResource(R.string.terrain_factor)) },
        isError = errors.containsKey(TerrainField.FACTOR),
        supportingText = errors[TerrainField.FACTOR]?.let { error -> { Text(terrainErrorText(TerrainField.FACTOR, error)) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        modifier = Modifier.fillMaxWidth().testTag(TERRAIN_FACTOR_TAG),
    )
    form.proposed?.let { proposed ->
        Text(
            stringResource(R.string.terrain_proposed, java.lang.String.format(java.util.Locale.ROOT, "%.8f", proposed)),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag(TERRAIN_PROPOSED_TAG),
        )
        OutlinedButton(
            onClick = actions.onTerrainUseProposal,
            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(TERRAIN_USE_PROPOSED_TAG),
        ) { Text(stringResource(R.string.terrain_use_proposed)) }
    }
}

@Composable
private fun OtherDatumNotice() {
    Text(
        stringResource(R.string.crs_change_other_datum),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag(CRS_OTHER_DATUM_TAG),
    )
}

/** ¿Es [chosen] del mismo datum que el SC con que se abrió el proyecto? Sin SC original (proyecto nuevo) todo vale (S035/RF-17). */
private fun sameDatumAsOriginal(state: ProjectFormUiState, chosen: ProjectCrs): Boolean =
    state.originalCrs?.let { CrsChange.sameDatum(it, chosen) } ?: true

/** Los puntos que el SC nuevo no calcula: continuar o cancelar el cambio (S035/RF-19). */
@Composable
private fun CrsChangeDialog(plan: CrsChangePlan, actions: ProjectFormActions) {
    AlertDialog(
        onDismissRequest = actions.onCancelCrsChange,
        title = { Text(stringResource(R.string.crs_change_issues_title)) },
        text = {
            Text(
                stringResource(R.string.crs_change_issues_message, plan.issues.size, plan.total),
                modifier = Modifier.testTag(CRS_CHANGE_DIALOG_TAG),
            )
        },
        confirmButton = {
            TextButton(onClick = actions.onConfirmCrsChange, modifier = Modifier.testTag(CRS_CHANGE_CONTINUE_TAG)) {
                Text(stringResource(R.string.crs_change_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onCancelCrsChange, modifier = Modifier.testTag(CRS_CHANGE_CANCEL_TAG)) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

/** Lo que el formulario necesita para ofrecer la biblioteca de SC (S034/RF-1). */
internal class CrsLibraryHooks(val onOpen: () -> Unit, val onBackToSpain: () -> Unit, val host: CrsLibraryHost)

/** El SC de la biblioteca del proyecto: su nombre, la transformación, la altura y las rejillas que faltan (S034/RF-9, RF-13, RF-15, RF-18). */
@Composable
private fun LibraryCrsSummary(crs: LibraryCrs, heightSystem: HeightSystem, library: CrsLibraryHooks?, canChange: Boolean) {
    val definition = crs.definition
    Column(modifier = Modifier.fillMaxWidth().testTag(CRS_LIBRARY_SUMMARY_TAG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(definition.name, style = MaterialTheme.typography.titleSmall)
        Text(
            (
                if (definition.isCustom) {
                    stringResource(
                        R.string.crs_custom_label,
                    )
                } else {
                    stringResource(R.string.crs_epsg_code, definition.epsg)
                }
                ) +
                " · " + definition.datumName,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(stringResource(R.string.crs_summary_transformation, definition.transformationName), style = MaterialTheme.typography.bodySmall)
        Text(
            if (heightSystem == HeightSystem.ORTHOMETRIC && crs.geoidGrid != null) {
                stringResource(R.string.crs_summary_height_geoid, crs.geoidGrid.orEmpty())
            } else {
                stringResource(R.string.crs_summary_height_ellipsoidal)
            },
            style = MaterialTheme.typography.bodySmall,
        )
        library?.host?.MissingGrids(crs)
        if (library != null && canChange) {
            OutlinedButton(onClick = library.onOpen, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(CRS_LIBRARY_CHANGE_TAG)) {
                Text(stringResource(R.string.crs_library_change))
            }
            OutlinedButton(
                onClick = library.onBackToSpain,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(CRS_LIBRARY_SPAIN_TAG),
            ) {
                Text(stringResource(R.string.crs_library_back_to_spain))
            }
        }
    }
}

@Composable
private fun datumOptionLabel(datum: GeodeticDatum): String = stringResource(
    when (datum) {
        GeodeticDatum.ETRS89 -> R.string.project_datum_option_etrs89
        GeodeticDatum.REGCAN95 -> R.string.project_datum_option_regcan95
        GeodeticDatum.ED50 -> R.string.project_datum_option_ed50
    },
)

@Composable
private fun projectionLabel(crs: ProjectCrs): String {
    val zone = crs.utmZone
    return if (zone !=
        null
    ) {
        stringResource(R.string.project_projection_utm, zone)
    } else {
        stringResource(R.string.project_projection_geographic)
    }
}

@Composable
internal fun heightLabel(height: HeightSystem): String = stringResource(
    when (height) {
        HeightSystem.ORTHOMETRIC -> R.string.project_height_orthometric
        HeightSystem.ELLIPSOIDAL -> R.string.project_height_ellipsoidal
    },
)

/** Opciones excluyentes en filas de 56 dp (RNF-T8), con su botón de radio. */
@Composable
internal fun <T> ChoiceGroup(
    title: String,
    options: List<T>,
    selected: T,
    tag: (T) -> String,
    label: @Composable (T) -> String,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    Column(modifier = Modifier.selectableGroup()) {
        options.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouchTarget)
                    .selectable(selected = option == selected, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(option) })
                    .testTag(tag(option)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = option == selected, onClick = null, enabled = enabled)
                Text(label(option), modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

internal fun nameErrorText(error: NameError): Int = when (error) {
    NameError.EMPTY -> R.string.project_name_empty
    NameError.DUPLICATE -> R.string.project_name_duplicate
}

@Composable
internal fun linearUnitLabel(unit: LinearUnit): String = stringResource(
    when (unit) {
        LinearUnit.METRE -> R.string.unit_metre
        LinearUnit.INTERNATIONAL_FOOT -> R.string.unit_foot
        LinearUnit.US_SURVEY_FOOT -> R.string.unit_us_survey_foot
    },
)

@Composable
internal fun angularUnitLabel(unit: AngularUnit): String = stringResource(
    when (unit) {
        AngularUnit.DECIMAL_DEGREES -> R.string.unit_decimal_degrees
        AngularUnit.DMS -> R.string.unit_dms
        AngularUnit.GON -> R.string.unit_gon
    },
)

@Composable
internal fun datumLabel(datum: CorrectionsDatum): String = stringResource(
    when (datum) {
        CorrectionsDatum.ETRS89 -> R.string.datum_etrs89
        CorrectionsDatum.REGCAN95 -> R.string.datum_regcan95
        CorrectionsDatum.ITRF_WGS84 -> R.string.datum_itrf_wgs84
    },
)
