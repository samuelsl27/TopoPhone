package es.topophone.app.ui.section

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.topophone.app.ui.points.importSourceOf
import es.topophone.core.model.preferences.ThemeState

/** Qué fichero se está eligiendo con el selector de documentos en la pantalla «Secciones» (S050/RNF-T5). */
private enum class Picking { NONE, CSV, LANDXML, SUPERELEVATION }

/**
 * La pantalla «Secciones» del eje [alignmentId] del proyecto [projectId] (S050/RF-1). Los ficheros se eligen con el selector de
 * documentos y su contenido decide si valen (RNF-T5); [onImportSections] abre la importación con el tipo y el fichero elegidos.
 */
@Composable
fun SectionsRoute(
    projectId: String,
    alignmentId: String,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    onImportSections: (kind: SectionImportKind, fileUri: String) -> Unit,
    onCalculate: () -> Unit,
    onBack: () -> Unit,
    viewModel: SectionsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(projectId, alignmentId) { viewModel.start(projectId, alignmentId) }
    var picking by rememberSaveable { mutableStateOf(Picking.NONE) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val what = picking
        picking = Picking.NONE
        if (uri != null) {
            when (what) {
                Picking.CSV -> onImportSections(SectionImportKind.CSV, uri.toString())
                Picking.LANDXML -> onImportSections(SectionImportKind.LANDXML, uri.toString())
                Picking.SUPERELEVATION -> viewModel.readCsv(importSourceOf(context.contentResolver, uri))
                Picking.NONE -> Unit
            }
        }
    }
    fun pick(what: Picking) {
        picking = what
        picker.launch(arrayOf("*/*")) // RNF-T5: cualquier tipo; el contenido decide si vale
    }
    SectionsScreen(
        themeState = themeState,
        onToggleSun = onToggleSun,
        state = state,
        actions = SectionsActions(
            onImportCsv = { pick(Picking.CSV) },
            onImportLandXml = { pick(Picking.LANDXML) },
            onSuperelevationCsv = { pick(Picking.SUPERELEVATION) },
            onCalculate = onCalculate,
            onStartTemplate = viewModel::startTemplate,
            onCancelTemplate = viewModel::cancelTemplate,
            onTemplate = { change -> viewModel.updateTemplate(change) },
            onSegment = { left, index, change -> viewModel.updateSegment(left, index, change) },
            onAddSegment = viewModel::addSegment,
            onRemoveSegment = viewModel::removeSegment,
            onSaveTemplate = viewModel::saveTemplate,
            onDeleteTemplate = viewModel::deleteTemplate,
            onDeleteSection = viewModel::deleteSection,
            onStartSuperelevation = viewModel::startSuperelevation,
            onCancelSuperelevation = viewModel::cancelSuperelevation,
            onSuperRow = { index, change -> viewModel.updateSuperelevationRow(index, change) },
            onAddSuperRow = viewModel::addSuperelevationRow,
            onRemoveSuperRow = viewModel::removeSuperelevationRow,
            onSaveSuperelevation = viewModel::saveSuperelevation,
            onCsvRole = viewModel::setCsvRole,
            onApplyCsv = viewModel::applyCsv,
        ),
        onBack = onBack,
    )
}

/** La importación de las secciones de [fileUri] al eje [alignmentId] (S050/RF-4, RF-5). */
@Composable
fun SectionImportRoute(
    projectId: String,
    alignmentId: String,
    kind: SectionImportKind,
    fileUri: Uri,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    onBack: () -> Unit,
    viewModel: SectionImportViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(projectId, alignmentId, fileUri) {
        viewModel.start(projectId, alignmentId, kind, importSourceOf(context.contentResolver, fileUri))
    }
    SectionImportScreen(
        themeState = themeState,
        onToggleSun = onToggleSun,
        state = state,
        actions = SectionImportActions(
            onUnit = viewModel::setUnit,
            onHeightKind = viewModel::setHeightKind,
            onEncoding = viewModel::setEncoding,
            onSeparator = viewModel::setSeparator,
            onDecimal = viewModel::setDecimal,
            onHasHeader = viewModel::setHasHeader,
            onRole = viewModel::setRole,
            onToggle = viewModel::toggle,
            onSelectKind = viewModel::selectKind,
            onImport = viewModel::import,
        ),
        onBack = onBack,
    )
}

/** La pantalla de calcular un punto de la sección y verla en el eje [alignmentId] del proyecto [projectId] (S050/RF-9 a RF-15). */
@Composable
fun SectionPointRoute(
    projectId: String,
    alignmentId: String,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    onBack: () -> Unit,
    viewModel: SectionPointViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(projectId, alignmentId) { viewModel.start(projectId, alignmentId) }
    SectionPointScreen(
        themeState = themeState,
        onToggleSun = onToggleSun,
        state = state,
        actions = SectionPointActions(
            onChainage = viewModel::setChainage,
            onMode = viewModel::setMode,
            onCode = viewModel::setCode,
            onOffset = viewModel::setOffset,
            onName = viewModel::setName,
            onSave = viewModel::save,
            onAnother = viewModel::another,
            onShowSection = viewModel::setShowSection,
        ),
        onBack = onBack,
    )
}
