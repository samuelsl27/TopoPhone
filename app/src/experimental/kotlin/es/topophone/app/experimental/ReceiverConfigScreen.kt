package es.topophone.app.experimental

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.preferences.ThemeState
import es.topophone.receiver.experimental.ReachConfigEntry
import es.topophone.receiver.experimental.ReachConfigView
import es.topophone.receiver.experimental.ReachPart
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

const val CONFIG_LIST_TAG = "experimental_config_list"
const val CONFIG_COUNT_TAG = "experimental_config_count"
const val CONFIG_NO_OUTPUT_TAG = "experimental_config_no_output"
const val CONFIG_INCOMPATIBLE_TAG = "experimental_config_incompatible"
const val CONFIG_RELOAD_TAG = "experimental_config_reload"
const val CONFIG_LOADING_TAG = "experimental_config_loading"
const val CONFIG_UNAVAILABLE_TAG = "experimental_config_unavailable"

fun configEntryTag(path: String) = "experimental_config_entry_$path"

/** La ruta de «Configuración del receptor» dentro del grafo del receptor (S044/RF-14). */
@Serializable
data object ExperimentalConfigDestination

sealed interface ConfigUiState {
    /** No hay un receptor con IP privada al que preguntar. */
    data object Unavailable : ConfigUiState

    data object Loading : ConfigUiState

    data class Loaded(val part: ReachPart<ReachConfigView>) : ConfigUiState
}

/** Lee los ajustes del receptor en uso al abrir la pantalla y otra vez con «Volver a leer»; nunca escribe nada (S044/RF-14, RF-15). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReceiverConfigViewModel @Inject constructor(private val session: ExperimentalSession) : ViewModel() {

    private val reloadTick = MutableStateFlow(0)

    val state: StateFlow<ConfigUiState> = combine(session.source, reloadTick) { source, _ -> source }
        .flatMapLatest<ReachSource, ConfigUiState> { source ->
            if (source is ReachSource.Ready) {
                flow {
                    emit(ConfigUiState.Loading)
                    emit(ConfigUiState.Loaded(session.readConfig(source.endpoint)))
                }
            } else {
                flowOf(ConfigUiState.Unavailable)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ConfigUiState.Loading)

    fun onReload() = reloadTick.update { it + 1 }
}

@Composable
fun ReceiverConfigRoute(themeState: ThemeState, onToggleSun: () -> Unit, onBack: () -> Unit) {
    val viewModel = hiltViewModel<ReceiverConfigViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    ReceiverConfigScreen(state, viewModel::onReload, themeState, onToggleSun, onBack)
}

/** «Configuración del receptor»: lo que el receptor devuelve de posicionamiento y de salida, solo para leer (S044/RF-14, RF-15). */
@Composable
fun ReceiverConfigScreen(state: ConfigUiState, onReload: () -> Unit, themeState: ThemeState, onToggleSun: () -> Unit, onBack: () -> Unit) {
    SectionScaffold(
        title = stringResource(R.string.experimental_config_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                ConfigUiState.Unavailable ->
                    Text(stringResource(R.string.experimental_config_unavailable), modifier = Modifier.testTag(CONFIG_UNAVAILABLE_TAG))
                ConfigUiState.Loading -> Text(
                    stringResource(R.string.experimental_loading),
                    modifier = Modifier.testTag(CONFIG_LOADING_TAG),
                )
                is ConfigUiState.Loaded -> when (val part = state.part) {
                    is ReachPart.Incompatible -> {
                        Text(
                            stringResource(R.string.experimental_incompatible),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag(CONFIG_INCOMPATIBLE_TAG),
                        )
                        ReloadButton(onReload)
                    }
                    is ReachPart.Ok -> {
                        Text(
                            stringResource(R.string.experimental_config_count, part.value.count),
                            modifier = Modifier.testTag(CONFIG_COUNT_TAG),
                        )
                        ReloadButton(onReload)
                        ConfigList(part.value)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReloadButton(onReload: () -> Unit) {
    OutlinedButton(
        onClick = onReload,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(CONFIG_RELOAD_TAG),
    ) { Text(stringResource(R.string.experimental_reload)) }
}

@Composable
private fun ConfigList(view: ReachConfigView) {
    LazyColumn(modifier = Modifier.fillMaxSize().testTag(CONFIG_LIST_TAG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { Section(stringResource(R.string.experimental_config_positioning)) }
        items(view.positioning, key = { "p:" + it.path }) { Entry(it) }
        item { Section(stringResource(R.string.experimental_config_output)) }
        if (view.output.isEmpty()) {
            item { Text(stringResource(R.string.experimental_config_no_output), modifier = Modifier.testTag(CONFIG_NO_OUTPUT_TAG)) }
        } else {
            items(view.output, key = { "o:" + it.path }) { Entry(it) }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

/** `ruta: valor`; con una clave sensible el valor se sustituye por «oculto» (S044/RNF-T11). */
@Composable
private fun Entry(entry: ReachConfigEntry) {
    val value = if (entry.hidden) stringResource(R.string.experimental_config_hidden) else entry.value
    Text(
        stringResource(R.string.experimental_info_row, entry.path, value),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag(configEntryTag(entry.path)),
    )
}
