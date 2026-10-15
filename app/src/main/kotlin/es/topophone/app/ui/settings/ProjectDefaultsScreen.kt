package es.topophone.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.projects.ChoiceGroup
import es.topophone.app.ui.projects.FORM_AUTHOR_TAG
import es.topophone.app.ui.projects.angularOptionTag
import es.topophone.app.ui.projects.angularUnitLabel
import es.topophone.app.ui.projects.crsLabel
import es.topophone.app.ui.projects.crsOptionTag
import es.topophone.app.ui.projects.datumLabel
import es.topophone.app.ui.projects.datumOptionTag
import es.topophone.app.ui.projects.linearOptionTag
import es.topophone.app.ui.projects.linearUnitLabel
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.project.ProjectPreferences
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

const val PROJECT_DEFAULTS_TAG = "project_defaults"

/** Valores por defecto de los proyectos nuevos; cada cambio se guarda al momento (S006/RF-13). */
@HiltViewModel
class ProjectDefaultsViewModel @Inject constructor(private val preferences: ProjectPreferences) : ViewModel() {
    val state: StateFlow<ProjectDefaults> =
        preferences.defaults.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ProjectDefaults())

    fun update(defaults: ProjectDefaults) {
        viewModelScope.launch { preferences.saveDefaults(defaults) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

@Composable
fun ProjectDefaultsRoute(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    onBack: () -> Unit,
    viewModel: ProjectDefaultsViewModel = hiltViewModel(),
) {
    val defaults by viewModel.state.collectAsStateWithLifecycle()
    ProjectDefaultsScreen(themeState, onToggleSun, defaults, viewModel::update, onBack)
}

/** "Proyectos nuevos" en Ajustes: autor, SC, unidades y datum con que empieza cada proyecto (S006/RF-13). */
@Composable
fun ProjectDefaultsScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    defaults: ProjectDefaults,
    onChange: (ProjectDefaults) -> Unit,
    onBack: () -> Unit,
) {
    SectionScaffold(
        title = stringResource(R.string.settings_project_defaults),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp).testTag(PROJECT_DEFAULTS_TAG),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.settings_project_defaults_help), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = defaults.author,
                onValueChange = { onChange(defaults.copy(author = it)) },
                label = { Text(stringResource(R.string.project_author)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(FORM_AUTHOR_TAG),
            )
            ChoiceGroup(stringResource(R.string.project_crs), ProjectCrs.entries, defaults.crs, ::crsOptionTag, { crsLabel(it) }) {
                onChange(defaults.copy(crs = it))
            }
            ChoiceGroup(stringResource(R.string.project_linear_unit), LinearUnit.entries, defaults.linearUnit, ::linearOptionTag, {
                linearUnitLabel(it)
            }) {
                onChange(defaults.copy(linearUnit = it))
            }
            ChoiceGroup(stringResource(R.string.project_angular_unit), AngularUnit.entries, defaults.angularUnit, ::angularOptionTag, {
                angularUnitLabel(it)
            }) {
                onChange(defaults.copy(angularUnit = it))
            }
            ChoiceGroup(
                stringResource(R.string.project_corrections_datum),
                CorrectionsDatum.entries,
                defaults.correctionsDatum,
                ::datumOptionTag,
                { datumLabel(it) },
            ) { onChange(defaults.copy(correctionsDatum = it)) }
        }
    }
}
