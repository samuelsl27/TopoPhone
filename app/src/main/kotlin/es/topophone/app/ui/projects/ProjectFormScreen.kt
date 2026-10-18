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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.DatumCheck
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.NameError
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.project.ProjectDraft
import es.topophone.data.project.ProjectPreferences
import es.topophone.data.project.ProjectRepository
import es.topophone.data.project.SaveResult
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

fun crsOptionTag(crs: ProjectCrs) = "project_crs_${crs.name}"

fun geodeticDatumOptionTag(datum: GeodeticDatum) = "project_geodetic_datum_${datum.name}"

fun heightOptionTag(height: HeightSystem) = "project_height_${height.name}"

fun linearOptionTag(unit: LinearUnit) = "project_linear_${unit.name}"

fun angularOptionTag(unit: AngularUnit) = "project_angular_${unit.name}"

fun datumOptionTag(datum: CorrectionsDatum) = "project_datum_${datum.name}"

/** Estado del formulario de proyecto (S006/RF-5 a RF-12); [savedId] cuando ya se ha guardado. */
data class ProjectFormUiState(
    val draft: ProjectDraft = ProjectDefaults().toDraft(),
    val loaded: Boolean = false,
    val editing: Boolean = false,
    val error: NameError? = null,
    val savedId: String? = null,
)

/** Acciones del formulario de proyecto. */
class ProjectFormActions(val onChange: (ProjectDraft) -> Unit = {}, val onSave: () -> Unit = {}, val onBack: () -> Unit = {})

/** Proyecto nuevo, desde los valores por defecto de Ajustes (S006/RF-5, RF-6, RF-12), o editado (S006/RF-14, RF-15). */
@HiltViewModel
class ProjectFormViewModel @Inject constructor(private val repository: ProjectRepository, private val preferences: ProjectPreferences) :
    ViewModel() {
    private val mutableState = MutableStateFlow(ProjectFormUiState())
    val state: StateFlow<ProjectFormUiState> = mutableState.asStateFlow()

    private var projectId: String? = null
    private var folderId: String? = null
    private var started = false

    fun start(projectId: String?, folderId: String?) {
        if (started) return
        started = true
        this.projectId = projectId
        this.folderId = folderId
        viewModelScope.launch {
            val project = projectId?.let { repository.project(it) }
            mutableState.value = if (project != null) {
                ProjectFormUiState(draft = project.toDraft(), loaded = true, editing = true) // S006/RF-14
            } else {
                ProjectFormUiState(draft = preferences.defaults.first().toDraft(), loaded = true) // S006/RF-6
            }
        }
    }

    fun update(draft: ProjectDraft) = mutableState.update { it.copy(draft = draft, error = null) }

    fun save() {
        val draft = mutableState.value.draft
        val editingId = projectId
        viewModelScope.launch {
            val result = if (editingId != null) repository.update(editingId, draft) else repository.create(draft, folderId)
            when (result) {
                is SaveResult.Invalid -> mutableState.update { it.copy(error = result.error) } // S006/RF-7, RF-14
                is SaveResult.Saved -> {
                    if (editingId == null) repository.markOpened(result.id) // S006/RF-12: se abre al guardarlo
                    mutableState.update { it.copy(savedId = result.id) }
                }
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
    val state by viewModel.state.collectAsStateWithLifecycle()
    val saved = state.savedId
    LaunchedEffect(saved) {
        when {
            saved == null -> Unit
            state.editing -> navigation.onBack() // se vuelve a donde se estaba
            else -> navigation.onProjectSaved(saved)
        }
    }
    ProjectFormScreen(themeState, onToggleSun, state, ProjectFormActions(viewModel::update, viewModel::save, navigation.onBack))
}

/** Formulario de proyecto nuevo o editado (S006/RF-5 a RF-11). */
@Composable
fun ProjectFormScreen(themeState: ThemeState, onToggleSun: () -> Unit, state: ProjectFormUiState, actions: ProjectFormActions) {
    val draft = state.draft
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
            CrsChoices(draft.crs, draft.heightSystem, datumLocked = state.editing, onCrs = { actions.onChange(draft.withCrs(it)) }) {
                actions.onChange(draft.copy(heightSystem = it))
            } // S006/RF-8, S008/RF-1 a RF-3, RF-17, RF-19
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
            ) {
                actions.onChange(draft.copy(correctionsDatum = it))
            } // S006/RF-10
            // S006/RF-11, S008/RF-18
            if (DatumCheck.mismatch(draft.crs, draft.correctionsDatum)) DatumWarning(draft.crs, draft.correctionsDatum)
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
 * (S008/RF-1 a RF-3). Con [datumLocked] (al editar) el datum no se puede cambiar (S008/RF-19).
 */
@Composable
internal fun CrsChoices(
    crs: ProjectCrs,
    heightSystem: HeightSystem,
    datumLocked: Boolean,
    onCrs: (ProjectCrs) -> Unit,
    onHeight: (HeightSystem) -> Unit,
) {
    Text(stringResource(R.string.project_crs), style = MaterialTheme.typography.titleMedium)
    ChoiceGroup(
        stringResource(R.string.project_geodetic_datum),
        GeodeticDatum.entries,
        crs.datum,
        ::geodeticDatumOptionTag,
        { datumOptionLabel(it) },
        enabled = !datumLocked,
    ) { onCrs(crs.closestIn(it)) }
    ChoiceGroup(
        stringResource(R.string.project_projection),
        ProjectCrs.entries.filter { it.datum == crs.datum },
        crs,
        ::crsOptionTag,
        { projectionLabel(it) },
    ) { onCrs(it) }
    ChoiceGroup(stringResource(R.string.project_height), crs.heightSystems, heightSystem, ::heightOptionTag, { heightLabel(it) }) {
        onHeight(it)
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
