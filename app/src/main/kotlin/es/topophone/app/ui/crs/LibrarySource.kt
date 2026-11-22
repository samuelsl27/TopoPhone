package es.topophone.app.ui.crs

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.SourceCrsResolution
import es.topophone.core.geodesy.catalog.SourceCrsResolver
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.isCustom
import es.topophone.core.survey.ImportTransformation
import es.topophone.core.survey.LibraryResources
import java.text.NumberFormat

const val SOURCE_LIBRARY_CHOICE_TAG = "source_library_choice"
const val SOURCE_LIBRARY_CHOSEN_TAG = "source_library_chosen"
const val SOURCE_LIBRARY_TRANSFORMATION_TAG = "source_library_transformation"
const val SOURCE_LIBRARY_WARNING_TAG = "source_library_warning"
const val SOURCE_LIBRARY_CONFIRM_TAG = "source_library_confirm"
const val SOURCE_LIBRARY_GRIDS_TAG = "source_library_grids"
const val SOURCE_LIBRARY_NO_SHIFT_TAG = "source_library_no_shift"
const val SOURCE_SUMMARY_TAG = "source_summary"

/**
 * El SC de la biblioteca elegido como SC de origen de un fichero que se importa, con su cambio de datum y si el usuario lo ha confirmado
 * (S057/RF-1, RF-2, RF-8 a RF-10). Es el estado que comparten las pantallas de importación; la importación solo empieza si está [ready].
 */
data class LibrarySourceUi(val source: CadSourceCrs.Library, val transformation: ImportTransformation?, val confirmed: Boolean = false) {
    /** La unidad lineal propia del SC, la que se propone al importar (S057/RF-3). */
    val unit: LinearUnit get() = source.unit

    /** El aviso está confirmado si hacía falta y no falta ninguna rejilla: se puede importar (S057/RF-9, RF-10). */
    val ready: Boolean
        get() = transformation == null || (!transformation.needsGrids && (!transformation.needsConfirmation || confirmed))

    fun confirm(confirmed: Boolean): LibrarySourceUi = copy(confirmed = confirmed)

    /** Vuelve a mirar qué rejillas faltan, tras descargar una (S057/RF-10). */
    fun refreshed(centre: GeoPoint?): LibrarySourceUi = copy(transformation = ImportTransformation.forSource(source.definition, centre))

    companion object {
        /**
         * El SC de la biblioteca [epsg] que declara un fichero, construido con las correcciones y el centro del proyecto de SC [project] (S057/RF-4,
         * RF-8); `null` si es el del proyecto (no hay que reproyectar, RF-12) o si no se puede calcular.
         */
        fun declared(epsg: Int, project: ProjectCrs, corrections: CorrectionsDatum, centre: GeoPoint?): LibrarySourceUi? {
            val resolution = SourceCrsResolver.resolve(CrsCatalog.bundled, epsg, project, centre, LibraryResources.current::isInstalled)
            val definition = when (resolution) {
                SourceCrsResolution.SameAsProject -> return null
                is SourceCrsResolution.Other -> when (val built = resolution.result) {
                    is CrsBuildResult.Ready -> built.definition
                    is CrsBuildResult.NeedsGrids -> built.definition
                    is CrsBuildResult.Unavailable -> return null
                }
            }
            return of(LibraryCrs(definition, corrections), centre)
        }

        /** El SC [crs] elegido en el selector de la biblioteca, con el cambio de datum para la zona alrededor de [centre]. */
        fun of(crs: LibraryCrs, centre: GeoPoint?): LibrarySourceUi {
            val source = CadSourceCrs.Library(crs.definition)
            return LibrarySourceUi(source, ImportTransformation.forSource(source.definition, centre))
        }
    }
}

/** Lo que el selector de SC necesita del proyecto al que se importa: el datum de las correcciones y el centro (S057/RF-8). */
data class LibrarySourceRequest(val corrections: CorrectionsDatum, val centre: GeoPoint?)

/**
 * La parte «otro sistema de la biblioteca» de la sección del SC de las pantallas de importación (S057/RF-1, RF-8 a RF-10): la opción de abrir el
 * selector de S034, el SC elegido, su cambio de datum con su precisión, el aviso que hay que confirmar y las rejillas que faltan, con «Descargar».
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibrarySourceSection(
    chosen: LibrarySourceUi?,
    selected: Boolean,
    request: LibrarySourceRequest,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    host: CrsLibraryHost,
    onChosen: (LibraryCrs) -> Unit,
    onSelect: () -> Unit,
    onConfirm: (Boolean) -> Unit,
    onRefresh: () -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = selected,
            onClick = { if (chosen == null) picking = true else onSelect() },
            label = { Text(stringResource(R.string.source_crs_library_choice)) },
            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(SOURCE_LIBRARY_CHOICE_TAG),
        )
    }
    if (picking) {
        host.Flow(
            themeState = themeState,
            onToggleSun = onToggleSun,
            request = CrsLibraryRequest(request.corrections, request.centre, null),
            onChosen = {
                picking = false
                onChosen(it)
            },
            onDismiss = { picking = false },
        )
    }
    if (chosen != null && selected) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (chosen.source.definition.isCustom) {
                    stringResource(R.string.source_crs_custom_chosen, chosen.source.definition.name)
                } else {
                    stringResource(R.string.source_crs_library_chosen, chosen.source.definition.name, chosen.source.definition.epsg)
                },
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.testTag(SOURCE_LIBRARY_CHOSEN_TAG),
            )
            FlowRow {
                FilterChip(
                    selected = false,
                    onClick = { picking = true },
                    label = { Text(stringResource(R.string.source_crs_library_change)) },
                    modifier = Modifier.heightIn(min = MinTouchTarget),
                )
            }
            TransformationPreview(chosen, host, onConfirm, onRefresh)
        }
    }
}

@Composable
private fun TransformationPreview(chosen: LibrarySourceUi, host: CrsLibraryHost, onConfirm: (Boolean) -> Unit, onRefresh: () -> Unit) {
    val transformation = chosen.transformation
    if (transformation == null) {
        Text(
            stringResource(R.string.source_crs_no_shift),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag(SOURCE_LIBRARY_NO_SHIFT_TAG),
        )
        return
    }
    Text(
        stringResource(R.string.source_crs_transformation, transformation.name),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag(SOURCE_LIBRARY_TRANSFORMATION_TAG),
    )
    Text(accuracyText(transformation), style = MaterialTheme.typography.bodySmall)
    if (transformation.outsideArea) {
        Text(stringResource(R.string.source_crs_outside_area), style = MaterialTheme.typography.bodySmall)
    }
    if (transformation.needsGrids) {
        Text(
            stringResource(R.string.source_crs_grids_missing),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(SOURCE_LIBRARY_GRIDS_TAG),
        )
        host.GridDownloads(transformation.missingGrids, onRefresh)
    }
    if (transformation.needsConfirmation) {
        Text(
            stringResource(R.string.source_crs_warning),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(SOURCE_LIBRARY_WARNING_TAG),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .toggleable(value = chosen.confirmed, role = Role.Checkbox, onValueChange = onConfirm)
                .testTag(SOURCE_LIBRARY_CONFIRM_TAG),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = chosen.confirmed, onCheckedChange = null)
            Text(stringResource(R.string.source_crs_confirm), modifier = Modifier.padding(start = 12.dp))
        }
    }
}

@Composable
private fun accuracyText(transformation: ImportTransformation): String {
    val accuracy = transformation.accuracyM ?: return stringResource(R.string.source_crs_accuracy_unknown)
    val number = NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 2
    }
    return stringResource(R.string.source_crs_accuracy, number.format(accuracy))
}

/**
 * Las líneas del resumen de una importación que dicen el SC de origen de la biblioteca y su cambio de datum con la precisión declarada (S057/RF-18).
 * Sirve a la pantalla y al texto que se guarda o se comparte.
 */
fun sourceSummaryText(resources: Resources, library: LibrarySourceUi): String {
    val definition = library.source.definition
    val number = NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 2
    }
    val transformation = library.transformation
    val shift = when {
        transformation == null -> resources.getString(R.string.source_summary_no_shift)
        transformation.accuracyM == null -> resources.getString(R.string.source_summary_transformation_unknown, transformation.name)
        else -> resources.getString(R.string.source_summary_transformation, transformation.name, number.format(transformation.accuracyM))
    }
    val origin = if (definition.isCustom) {
        resources.getString(R.string.source_summary_origin_custom, definition.name)
    } else {
        resources.getString(R.string.source_summary_origin, definition.name, definition.epsg)
    }
    return origin + "\n" + shift
}

/** El SC de origen y el cambio de datum de una importación con un SC de la biblioteca; no se ve con uno de España (S057/RF-18, RF-27). */
@Composable
fun SourceCrsSummary(library: LibrarySourceUi?, modifier: Modifier = Modifier) {
    if (library == null) return
    Text(sourceSummaryText(LocalResources.current, library), modifier = modifier.testTag(SOURCE_SUMMARY_TAG))
}
