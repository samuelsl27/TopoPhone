package es.topophone.app.ui.localization

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.projects.unitSymbol
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.localization.LocalizationLimits
import es.topophone.core.model.localization.LocalizationOrigin
import es.topophone.core.model.localization.LocalizationPair
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.Project

const val LOCALIZATION_STATUS_TAG = "localization_status"
const val LOCALIZATION_PAIRS_TITLE_TAG = "localization_pairs_title"
const val LOCALIZATION_PAIRS_EMPTY_TAG = "localization_pairs_empty"

fun localizationPairTag(id: String) = "localization_pair_$id"

/** Acciones de la pantalla de localización (S039); crecen con cada tarea. */
class LocalizationActions

/**
 * Localización (S039/RF-1): el estado del proyecto (sin localización, o activa con su RMS) y la lista de pares con sus coordenadas conocidas y las
 * componentes que usan. Los avisos van en texto además de en color (RNF-T8).
 */
@Composable
fun LocalizationScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: LocalizationUiState,
    @Suppress("UNUSED_PARAMETER") actions: LocalizationActions,
    onBack: () -> Unit,
) {
    SectionScaffold(
        title = stringResource(R.string.localization_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val project = state.project
            if (state.loaded && project != null) {
                Text(project.name, style = MaterialTheme.typography.titleMedium)
                StatusCard(state, project)
                PairsSection(state, project)
            }
        }
    }
}

@Composable
private fun StatusCard(state: LocalizationUiState, project: Project) {
    val locale = uiLocale()
    val unit = unitSymbol(project.linearUnit)
    fun length(valueM: Double) = "${LengthFormat.inUnit(valueM, project.linearUnit, locale)} $unit"
    val active = state.active
    val text = when {
        active == null -> stringResource(R.string.localization_status_none)
        active.origin == LocalizationOrigin.SAVED_CRS -> stringResource(R.string.localization_status_saved, active.name.orEmpty())
        else -> stringResource(
            R.string.localization_status_active,
            active.rmsHorizontal?.let(::length) ?: "—",
            active.rmsVertical?.let(::length) ?: "—",
        )
    }
    Card(modifier = Modifier.fillMaxWidth().testTag(LOCALIZATION_STATUS_TAG).semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(text, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun PairsSection(state: LocalizationUiState, project: Project) {
    Text(
        stringResource(R.string.localization_pairs_title, state.pairs.size, LocalizationLimits.MAX_PAIRS),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.testTag(LOCALIZATION_PAIRS_TITLE_TAG),
    )
    if (state.pairs.isEmpty()) {
        Text(stringResource(R.string.localization_pairs_empty), modifier = Modifier.testTag(LOCALIZATION_PAIRS_EMPTY_TAG))
    }
    state.pairs.forEach { pair -> PairRow(pair, project) }
}

@Composable
private fun PairRow(pair: LocalizationPair, project: Project) {
    val locale = uiLocale()
    val unit = unitSymbol(project.linearUnit)
    fun length(valueM: Double) = "${LengthFormat.inUnit(valueM, project.linearUnit, locale)} $unit"
    val noHeight = stringResource(R.string.localization_no_height)
    Card(modifier = Modifier.fillMaxWidth().testTag(localizationPairTag(pair.id))) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(pair.pointName, style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(
                    R.string.localization_pair_known,
                    length(pair.knownE),
                    length(pair.knownN),
                    pair.knownH?.let(::length) ?: noHeight,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
