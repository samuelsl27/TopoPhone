package es.topophone.app.ui.codes

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.export.writeToUri
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.formats.codes.CodeLibraryCsvWriter
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeIndex
import es.topophone.core.model.codes.CodeLibrary
import es.topophone.core.model.codes.CodeSearch
import es.topophone.core.model.codes.SurveyCode
import es.topophone.core.model.preferences.ThemeState
import es.topophone.data.codes.CodeLibraryRepository
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val CODE_SEARCH_TAG = "code_search"
const val CODE_ADD_TAG = "code_add"
const val CODE_IMPORT_TAG = "code_import"
const val CODE_EXPORT_TAG = "code_export"
const val CODE_MESSAGE_TAG = "code_message"
const val CODE_READONLY_TAG = "code_readonly"
const val CODE_EMPTY_TAG = "code_empty"

fun codeRowTag(codeId: String) = "code_row_$codeId"

/** Cómo acabó exportar la biblioteca (S023/RF-17). */
enum class ExportMessage { SAVED, FAILED }

/** [codes] son los códigos que pasan el buscador, ordenados (S023/RF-11); [library] es null si no existe. */
data class CodeLibraryUiState(
    val loaded: Boolean = false,
    val library: CodeLibrary? = null,
    val query: String = "",
    val codes: List<SurveyCode> = emptyList(),
    val message: ExportMessage? = null,
) {
    /** Solo las bibliotecas del usuario se editan (S023/RF-3). */
    val editable: Boolean get() = library != null && !library.isDefault
}

data class CodeLibraryActions(
    val onQueryChange: (String) -> Unit = {},
    val onAdd: () -> Unit = {},
    val onEdit: (String) -> Unit = {},
    val onImport: () -> Unit = {},
    val onExport: () -> Unit = {},
    val onDismissMessage: () -> Unit = {},
)

/** Una biblioteca con su buscador (S023/RF-11) y su exportación a CSV (S023/RF-17). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CodeLibraryViewModel @Inject constructor(private val repository: CodeLibraryRepository) : ViewModel() {
    private val libraryId = MutableStateFlow<String?>(null)
    private val query = MutableStateFlow("")
    private val message = MutableStateFlow<ExportMessage?>(null)

    private val indexed = libraryId.flatMapLatest { id ->
        if (id ==
            null
        ) {
            flowOf<Pair<CodeLibrary?, CodeIndex?>>(null to null)
        } else {
            repository.library(id).map {
                it to
                    it?.let { lib -> CodeSearch.index(lib.codes) }
            }
        }
    }

    val state: StateFlow<CodeLibraryUiState> = combine(libraryId, indexed, query, message) { id, (library, index), text, export ->
        CodeLibraryUiState(
            loaded = id != null,
            library = library,
            query = text,
            codes = index?.filter(text).orEmpty(),
            message = export,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CodeLibraryUiState())

    fun open(id: String) {
        libraryId.value = id
    }

    fun search(text: String) {
        query.value = text
    }

    /** Escribe la biblioteca como CSV en UTF-8 (S023/RF-17); false si no se pudo. */
    suspend fun writeCsv(out: OutputStream): Boolean {
        val library = state.value.library ?: return false
        return withContext(Dispatchers.IO) {
            try {
                val text = StringBuilder().also { CodeLibraryCsvWriter.write(library, it) }
                out.write(text.toString().toByteArray(Charsets.UTF_8))
                out.flush()
                true
            } catch (_: IOException) {
                false
            }
        }
    }

    fun exported(saved: Boolean) {
        message.value = if (saved) ExportMessage.SAVED else ExportMessage.FAILED
    }

    fun dismissMessage() {
        message.value = null
    }

    fun fileName(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String =
        CodeLibraryCsvWriter.fileName(state.value.library?.name.orEmpty(), now, zone)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

@Composable
fun CodeLibraryRoute(
    libraryId: String,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    onAdd: (String) -> Unit = {},
    onEdit: (libraryId: String, codeId: String) -> Unit = { _, _ -> },
    onImport: (String) -> Unit = {},
    viewModel: CodeLibraryViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    LaunchedEffect(libraryId) { viewModel.open(libraryId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    // S023/RF-17: el selector de ficheros; si el usuario cancela, no se escribe nada.
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch { viewModel.exported(writeToUri(resolver, uri, viewModel::writeCsv)) }
    }
    CodeLibraryScreen(
        themeState,
        onToggleSun,
        state,
        CodeLibraryActions(
            onQueryChange = viewModel::search,
            onAdd = { onAdd(libraryId) },
            onEdit = { onEdit(libraryId, it) },
            onImport = { onImport(libraryId) },
            onExport = {
                viewModel.dismissMessage()
                create.launch(viewModel.fileName())
            },
            onDismissMessage = viewModel::dismissMessage,
        ),
        onBack,
    )
}

@Composable
fun CodeLibraryScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: CodeLibraryUiState,
    actions: CodeLibraryActions,
    onBack: () -> Unit,
) {
    SectionScaffold(
        title = state.library?.name ?: stringResource(R.string.codes_libraries_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        val library = state.library
        if (library == null) {
            if (state.loaded) {
                Text(stringResource(R.string.codes_not_found), modifier = Modifier.padding(padding).padding(16.dp))
            }
            return@SectionScaffold
        }
        Column(modifier = Modifier.padding(padding).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (library.isDefault) {
                    Text(stringResource(R.string.codes_readonly_note), modifier = Modifier.testTag(CODE_READONLY_TAG))
                }
                state.message?.let { ExportBanner(it, actions.onDismissMessage) }
                OutlinedTextField(
                    value = state.query,
                    onValueChange = actions.onQueryChange,
                    label = { Text(stringResource(R.string.codes_library_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(CODE_SEARCH_TAG),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.editable) {
                        Button(onClick = actions.onAdd, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(CODE_ADD_TAG)) {
                            Text(stringResource(R.string.codes_add))
                        }
                        OutlinedButton(
                            onClick = actions.onImport,
                            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(CODE_IMPORT_TAG),
                        ) {
                            Text(stringResource(R.string.codes_import_csv))
                        }
                    }
                    OutlinedButton(
                        onClick = actions.onExport,
                        modifier = Modifier.heightIn(min = MinTouchTarget).testTag(CODE_EXPORT_TAG),
                    ) {
                        Text(stringResource(R.string.codes_export_csv))
                    }
                }
            }
            if (state.codes.isEmpty()) {
                val text = when {
                    library.codes.isEmpty() -> stringResource(R.string.codes_none)
                    else -> stringResource(R.string.codes_no_match, state.query.trim())
                }
                Text(text, modifier = Modifier.padding(16.dp).testTag(CODE_EMPTY_TAG))
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(state.codes, key = { it.id }) { code ->
                        CodeRow(code, if (state.editable) ({ actions.onEdit(code.id) }) else null)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportBanner(message: ExportMessage, onDismiss: () -> Unit) {
    val failed = message == ExportMessage.FAILED
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().testTag(CODE_MESSAGE_TAG),
    ) {
        Text(
            stringResource(if (failed) R.string.codes_export_failed else R.string.codes_export_saved),
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget)) {
            Text(stringResource(R.string.codes_dismiss))
        }
    }
}

/** Un código: su color junto al texto del código (nunca solo el color, RNF-T8), su descripción y su geometría con texto. */
@Composable
private fun CodeRow(code: SurveyCode, onClick: (() -> Unit)?) {
    val geometry = stringResource(geometryLabel(code.geometry))
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ColorSwatch(code.color)
                Text(code.code, style = MaterialTheme.typography.titleMedium)
            }
        },
        supportingContent = {
            Text(if (code.description.isBlank()) geometry else "${code.description} · $geometry")
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .heightIn(min = MinTouchTarget)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .testTag(codeRowTag(code.id)),
    )
}

/** El color del código como una muestra; es decorativa, porque el código y la geometría ya van en texto. */
@Composable
internal fun ColorSwatch(rgb: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(Color(0xFF000000.toInt() or (rgb and 0xFFFFFF)))
            .clearAndSetSemantics {},
    )
}

internal fun geometryLabel(geometry: CodeGeometry): Int = when (geometry) {
    CodeGeometry.POINT -> R.string.codes_geometry_point
    CodeGeometry.LINE -> R.string.codes_geometry_line
    CodeGeometry.POLYGON -> R.string.codes_geometry_polygon
}
