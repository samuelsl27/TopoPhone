package es.topophone.app.ui.stakeout

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.export.ExportFiles
import es.topophone.app.ui.export.ExportResult
import es.topophone.app.ui.export.shareExportIntent
import es.topophone.app.ui.export.writeToUri
import es.topophone.app.ui.projects.SurveyNavigation
import es.topophone.app.ui.projects.unitSymbol
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.formats.csv.ExportFileName
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.LinearUnit
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val STAKEOUT_REPORT_DESIGNS_TAG = "stakeout_report_designs"
const val STAKEOUT_REPORT_WITHIN_TAG = "stakeout_report_within"
const val STAKEOUT_REPORT_OUTSIDE_TAG = "stakeout_report_outside"
const val STAKEOUT_REPORT_NOT_EVALUATED_TAG = "stakeout_report_not_evaluated"
const val STAKEOUT_REPORT_HORIZONTAL_TAG = "stakeout_report_horizontal"
const val STAKEOUT_REPORT_VERTICAL_TAG = "stakeout_report_vertical"
const val STAKEOUT_REPORT_NONE_TAG = "stakeout_report_none"
const val STAKEOUT_REPORT_FILE_TAG = "stakeout_report_file"
const val STAKEOUT_REPORT_SAVE_TAG = "stakeout_report_save"
const val STAKEOUT_REPORT_SHARE_TAG = "stakeout_report_share"
const val STAKEOUT_REPORT_INVALID_TAG = "stakeout_report_invalid"
private const val CSV_MIME = "text/csv"

/** Acciones del informe: guardar y compartir reciben el nombre propuesto (S019/RF-24; S012/RF-19). */
class StakeoutReportActions(val onSave: (fileName: String) -> Unit = {}, val onShare: (fileName: String) -> Unit = {})

@Composable
fun StakeoutReportRoute(
    projectId: String,
    selection: Boolean,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    navigation: SurveyNavigation,
    viewModel: StakeoutReportViewModel = hiltViewModel(),
) {
    LaunchedEffect(projectId, selection) { viewModel.start(projectId, selection) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // S019/RF-24: el selector de ficheros del móvil; si el usuario cancela, no se escribe nada (S012/RF-17).
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(CSV_MIME)) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = writeToUri(context.contentResolver, uri, viewModel::writeTo)
                viewModel.report(if (ok) ExportResult.SAVED else ExportResult.FAILED)
            }
        }
    }
    StakeoutReportScreen(
        themeState,
        onToggleSun,
        state,
        StakeoutReportActions(
            onSave = { name ->
                viewModel.report(null)
                save.launch(name)
            },
            onShare = { name ->
                viewModel.report(null)
                scope.launch {
                    // S012/RF-18: desde la caché de la app, con el FileProvider.
                    val file = ExportFiles.prepare(context.cacheDir, name, System.currentTimeMillis())
                    val ok = withContext(Dispatchers.IO) { file.outputStream() }.use { viewModel.writeTo(it) }
                    if (ok) context.startActivity(shareExportIntent(context, file)) else viewModel.report(ExportResult.FAILED)
                }
            },
        ),
        navigation.onBack,
    )
}

/**
 * Informe de replanteo (S019): el resumen de lo replanteado, con el último replanteo de cada punto de diseño, y "Guardar" y
 * "Compartir" el CSV con todos los replanteos. Sin puntos replanteados lo dice y no deja exportar (RF-20).
 */
@Composable
fun StakeoutReportScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: StakeoutReportUiState,
    actions: StakeoutReportActions,
    onBack: () -> Unit,
) {
    SectionScaffold(
        title = stringResource(R.string.stakeout_report_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        if (!state.loaded) return@SectionScaffold
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.report.isEmpty) {
                Text(
                    stringResource(R.string.stakeout_report_none),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag(STAKEOUT_REPORT_NONE_TAG),
                )
            } else {
                Summary(state)
            }
            if (!state.options.valid) {
                Text(
                    stringResource(R.string.export_invalid),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(STAKEOUT_REPORT_INVALID_TAG),
                )
            }
            val opened = remember { Instant.now() }
            val label = stringResource(R.string.stakeout_report_file_label)
            val fileName = ExportFileName.of(state.projectName, label, opened, ZoneId.systemDefault()) // S019/RF-24
            Text(
                stringResource(R.string.export_file_name, fileName),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(STAKEOUT_REPORT_FILE_TAG),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { actions.onSave(fileName) },
                    enabled = state.canExport,
                    modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(STAKEOUT_REPORT_SAVE_TAG),
                ) { Text(stringResource(R.string.export_save)) }
                OutlinedButton(
                    onClick = { actions.onShare(fileName) },
                    enabled = state.canExport,
                    modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(STAKEOUT_REPORT_SHARE_TAG),
                ) { Text(stringResource(R.string.export_share)) }
            }
            when (state.result) {
                ExportResult.SAVED -> Text(stringResource(R.string.export_saved))
                ExportResult.FAILED -> Text(stringResource(R.string.export_failed), color = MaterialTheme.colorScheme.error) // S019/RF-24
                null -> Unit
            }
        }
    }
}

/** Los puntos de diseño replanteados, cuántos cumplen y las desviaciones máxima y media, en la unidad del proyecto (S019/RF-17). */
@Composable
private fun Summary(state: StakeoutReportUiState) {
    val summary = state.report.summary
    val unit = state.project?.linearUnit ?: LinearUnit.METRE
    val locale = uiLocale()
    val symbol = unitSymbol(unit)
    fun length(valueM: Double) = "${LengthFormat.inUnit(valueM, unit, locale)} $symbol"
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.stakeout_report_designs, summary.designPoints),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag(STAKEOUT_REPORT_DESIGNS_TAG),
        )
        Text(stringResource(R.string.stakeout_report_within, summary.within), modifier = Modifier.testTag(STAKEOUT_REPORT_WITHIN_TAG))
        Text(stringResource(R.string.stakeout_report_outside, summary.outside), modifier = Modifier.testTag(STAKEOUT_REPORT_OUTSIDE_TAG))
        if (summary.notEvaluated > 0) {
            Text(
                stringResource(R.string.stakeout_report_not_evaluated, summary.notEvaluated),
                modifier = Modifier.testTag(STAKEOUT_REPORT_NOT_EVALUATED_TAG),
            ) // S019/RF-26
        }
        val horizontalMax = summary.horizontalMaxM
        val horizontalMean = summary.horizontalMeanM
        if (horizontalMax != null && horizontalMean != null) {
            Text(
                stringResource(R.string.stakeout_report_horizontal, length(horizontalMax), length(horizontalMean)),
                modifier = Modifier.testTag(STAKEOUT_REPORT_HORIZONTAL_TAG),
            )
        }
        val verticalMax = summary.verticalMaxM
        val verticalMean = summary.verticalMeanM
        if (verticalMax != null && verticalMean != null) {
            Text(
                stringResource(R.string.stakeout_report_vertical, length(verticalMax), length(verticalMean), summary.verticalCount),
                modifier = Modifier.testTag(STAKEOUT_REPORT_VERTICAL_TAG),
            )
        }
    }
}
