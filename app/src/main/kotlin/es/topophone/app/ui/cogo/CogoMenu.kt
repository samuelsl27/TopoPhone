package es.topophone.app.ui.cogo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.preferences.ThemeState
import es.topophone.data.project.ProjectRepository
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

const val COGO_INVERSE_TAG = "cogo_inverse"
const val COGO_TRAVERSE_TAG = "cogo_traverse"
const val COGO_INTERSECTION_TAG = "cogo_intersection"
const val COGO_GEOGRAPHIC_TAG = "cogo_geographic"

private const val STOP_TIMEOUT_MS = 5_000L

/** Lo que muestra la pantalla de elección de COGO (S038/RF-1, RF-2). */
data class CogoMenuUi(val loaded: Boolean = false, val geographic: Boolean = false)

/** Lo que el usuario elige en COGO (S038/RF-1). */
class CogoMenuActions(val onInverse: () -> Unit = {}, val onTraverse: () -> Unit = {}, val onIntersection: () -> Unit = {})

/** Si el SC del proyecto es proyectado: COGO es geometría plana y no se ofrece con coordenadas geográficas (S038/RF-2). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CogoMenuViewModel @Inject constructor(private val projects: ProjectRepository) : ViewModel() {
    private val projectId = MutableStateFlow<String?>(null)

    val state: StateFlow<CogoMenuUi> = projectId.flatMapLatest { id ->
        if (id ==
            null
        ) {
            flowOf(CogoMenuUi())
        } else {
            projects.observe(id).map {
                it?.let { project -> CogoMenuUi(true, !project.crs.isUtm) }
                    ?: CogoMenuUi()
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CogoMenuUi())

    fun start(id: String) {
        projectId.value = id
    }
}

@Composable
fun CogoMenuRoute(
    projectId: String,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    actions: CogoMenuActions,
    viewModel: CogoMenuViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    LaunchedEffect(projectId) { viewModel.start(projectId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    CogoMenuScreen(themeState, onToggleSun, state, actions, onBack)
}

/** «COGO»: Inversa, Poligonal e Intersección; con un SC geográfico no ofrece ninguna y lo dice (S038/RF-1, RF-2). */
@Composable
fun CogoMenuScreen(themeState: ThemeState, onToggleSun: () -> Unit, state: CogoMenuUi, actions: CogoMenuActions, onBack: () -> Unit) {
    SectionScaffold(
        title = stringResource(R.string.cogo_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        if (!state.loaded) return@SectionScaffold
        Column(modifier = Modifier.padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.geographic) {
                Text(
                    stringResource(R.string.cogo_geographic_unavailable),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag(COGO_GEOGRAPHIC_TAG),
                )
            } else {
                Option(R.string.cogo_inverse, R.string.cogo_inverse_hint, COGO_INVERSE_TAG, actions.onInverse)
                Option(R.string.cogo_traverse, R.string.cogo_traverse_hint, COGO_TRAVERSE_TAG, actions.onTraverse)
                Option(R.string.cogo_intersection, R.string.cogo_intersection_hint, COGO_INTERSECTION_TAG, actions.onIntersection)
            }
        }
    }
}

@Composable
private fun Option(title: Int, hint: Int, tag: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(tag).clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(hint), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
