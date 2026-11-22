package es.topophone.app.ui.crs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.R
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.GridEntry
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.ProjectCrs
import es.topophone.data.grids.GridState
import es.topophone.data.grids.GridStore
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

const val MISSING_GRIDS_TAG = "library_missing_grids"

/** Lo que el selector de SC necesita saber del proyecto que se está creando (S034/RF-10, RF-14). */
data class CrsLibraryRequest(
    val corrections: CorrectionsDatum,
    val centre: GeoPoint?,
    val currentGeoid: String?,
    /** ¿Se ofrece Mis SC y crear un SC personalizado (S035/RF-9)? No al partir de uno de la biblioteca en el editor. */
    val allowCustom: Boolean = true,
)

/**
 * Lo que el formulario de proyecto usa de la biblioteca de SC sin saber de Hilt: el flujo de elegir (selector, ficha y descarga) y el aviso de las
 * rejillas que faltan. La implementación real está en [HiltCrsLibraryHost]; las pruebas del formulario ponen una de pega (S034/RF-1, RF-12).
 */
interface CrsLibraryHost {
    @Composable
    fun Flow(
        themeState: ThemeState,
        onToggleSun: () -> Unit,
        request: CrsLibraryRequest,
        onChosen: (LibraryCrs) -> Unit,
        onDismiss: () -> Unit,
    )

    @Composable
    fun MissingGrids(crs: ProjectCrs)

    /**
     * Las rejillas [entries] que faltan, cada una con su tamaño y su «Descargar» (con confirmación); al acabar una descarga se llama a
     * [onInstalled] (S057/RF-10). Las pruebas de pantallas que no descargan no necesitan implementarlo.
     */
    @Composable
    fun GridDownloads(entries: List<GridEntry>, onInstalled: () -> Unit) = Unit
}

/** El [CrsLibraryHost] de la app, con los ViewModel de Hilt. */
object HiltCrsLibraryHost : CrsLibraryHost {
    @Composable
    override fun Flow(
        themeState: ThemeState,
        onToggleSun: () -> Unit,
        request: CrsLibraryRequest,
        onChosen: (LibraryCrs) -> Unit,
        onDismiss: () -> Unit,
    ) = CrsLibraryFlow(themeState, onToggleSun, request, onChosen, onDismiss)

    @Composable
    override fun MissingGrids(crs: ProjectCrs) = LibraryGridsStatus(crs)

    @Composable
    override fun GridDownloads(entries: List<GridEntry>, onInstalled: () -> Unit) = MissingGridDownloads(entries, onInstalled)
}

/** Las rejillas que faltan con su «Descargar» y el diálogo de confirmación y progreso (S057/RF-10, S034/RF-19). */
@Composable
fun MissingGridDownloads(entries: List<GridEntry>, onInstalled: () -> Unit, download: GridDownloadViewModel = hiltViewModel()) {
    val downloadState by download.state.collectAsState()
    entries.forEach { entry -> GridLine(GridItem(entry, GridState.Missing)) { download.ask(it) } }
    GridDownloadDialog(downloadState, onConfirm = { download.confirm(onInstalled) }, onDismiss = download::dismiss)
}

/**
 * El selector de SC, la ficha y la descarga de rejillas, a pantalla completa y por encima del formulario (S034/RF-1 a RF-12, RF-14, RF-19). Al
 * elegir un SC lo anota entre los recientes (RF-8) y devuelve el SC con el geoide que se eligió en su ficha.
 */
@Composable
fun CrsLibraryFlow(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    request: CrsLibraryRequest,
    onChosen: (LibraryCrs) -> Unit,
    onDismiss: () -> Unit,
    picker: CrsPickerViewModel = hiltViewModel(),
    details: CrsDetailsViewModel = hiltViewModel(),
    download: GridDownloadViewModel = hiltViewModel(),
) {
    var epsg by rememberSaveable { mutableStateOf<Int?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    val pickerState by picker.state.collectAsState()
    val detailsState by details.state.collectAsState()
    val downloadState by download.state.collectAsState()
    LaunchedEffect(epsg) { epsg?.let { details.load(it, request.corrections, request.centre, request.currentGeoid) } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = MaterialTheme.colorScheme.background) {
            if (epsg == null) {
                CrsPickerScreen(
                    themeState,
                    onToggleSun,
                    pickerState.copy(allowCustom = request.allowCustom),
                    CrsPickerActions(
                        onQuery = picker::onQuery,
                        onCountry = picker::onCountry,
                        onFavorite = picker::toggleFavorite,
                        onOpen = { epsg = it.epsg },
                        onChooseMine = { onChosen(it.toProjectCrs(request.corrections)) }, // S035/RF-9
                        onCreateCustom = { creating = true }, // S035/RF-1
                        onBack = onDismiss,
                    ),
                )
            } else {
                CrsDetailsScreen(
                    themeState,
                    onToggleSun,
                    detailsState,
                    CrsDetailsActions(
                        onTransformation = details::selectTransformation,
                        onGeoid = details::selectGeoid,
                        onDownload = download::ask,
                        onUse = { chosen ->
                            picker.markUsed(chosen.epsg) // S034/RF-8
                            onChosen(chosen)
                        },
                        onBack = { epsg = null },
                    ),
                )
            }
            GridDownloadDialog(downloadState, onConfirm = { download.confirm { details.refresh() } }, onDismiss = download::dismiss)
            if (creating) {
                // El editor de un SC personalizado nuevo, por encima del selector; al guardarlo se elige directamente (S035/RF-1, RF-9).
                Dialog(onDismissRequest = { creating = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        CustomCrsEditorRoute(
                            themeState,
                            onToggleSun,
                            CustomCrsEditorRequest(corrections = request.corrections, centre = request.centre),
                            onSaved = { saved ->
                                creating = false
                                onChosen(saved.toProjectCrs(request.corrections))
                            },
                            onBack = { creating = false },
                        )
                    }
                }
            }
        }
    }
}

/** Las rejillas que usa un SC de la biblioteca y que no están instaladas (S034/RF-15, RF-18). */
@HiltViewModel
class GridStatusViewModel @Inject constructor(private val store: GridStore) : ViewModel() {
    private val catalog = CrsCatalog.bundled
    private val refreshTick = MutableStateFlow(0)
    val tick: StateFlow<Int> = refreshTick.asStateFlow()

    /** Las rejillas de la transformación y el geoide de [crs] que faltan; vacío con un SC de España o sin rejillas descargables (RF-24). */
    fun missingFor(crs: ProjectCrs): List<GridEntry> {
        val library = crs as? LibraryCrs ?: return emptyList()
        return (library.definition.gridNames + listOfNotNull(library.geoidGrid)).distinct()
            .mapNotNull { catalog.grids[it] }
            .filter { store.state(it.name) == GridState.Missing }
    }

    fun refresh() {
        refreshTick.value++
    }
}

/**
 * El aviso de las rejillas que faltan a un proyecto con un SC de la biblioteca, con el botón «Descargar» (S034/RF-15, RF-18); no se ve si no falta
 * ninguna.
 */
@Composable
fun LibraryGridsStatus(crs: ProjectCrs, status: GridStatusViewModel = hiltViewModel(), download: GridDownloadViewModel = hiltViewModel()) {
    val tick by status.tick.collectAsState()
    val missing = remember(crs, tick) { status.missingFor(crs) }
    val downloadState by download.state.collectAsState()
    if (missing.isNotEmpty()) {
        Column(modifier = Modifier.testTag(MISSING_GRIDS_TAG)) {
            Text(
                stringResource(R.string.library_grids_missing_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                stringResource(R.string.library_grids_missing_text),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            missing.forEach { entry -> GridLine(GridItem(entry, GridState.Missing)) { download.ask(it) } }
        }
    }
    GridDownloadDialog(downloadState, onConfirm = { download.confirm { status.refresh() } }, onDismiss = download::dismiss)
}
