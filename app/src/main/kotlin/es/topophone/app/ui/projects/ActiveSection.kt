package es.topophone.app.ui.projects

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.project.LinearUnit

const val ACTIVE_SECTION_TAG = "project_active_section"

fun activeRowTag(kind: ActiveKind) = "project_active_${kind.name.lowercase()}"

/**
 * La sección «Activo en este proyecto» de «Datos del proyecto»: una fila por cada elemento activo, con su nombre y su estado en texto
 * (S059/RF-18, RF-19). Cada fila con destino abre la pantalla donde el elemento se gestiona (RF-20); una sin destino solo informa y no
 * parece un botón (RF-20b). Si no hay nada activo no se pinta (RF-21).
 */
@Composable
fun ActiveSection(items: List<ActiveItem>, unit: LinearUnit, destinations: Map<ActiveKind, () -> Unit>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    Card(modifier = modifier.fillMaxWidth().testTag(ACTIVE_SECTION_TAG)) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                stringResource(R.string.active_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            items.forEach { item -> ActiveRow(item, unit, destinations[item.kind]) }
        }
    }
}

@Composable
private fun ActiveRow(item: ActiveItem, unit: LinearUnit, onOpen: (() -> Unit)?) {
    val title = stringResource(item.kind.titleRes())
    val status = statusText(item.status, unit)
    val open = stringResource(R.string.active_open)
    val description = stringResource(R.string.active_row_description, title, status)
    val base = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(activeRowTag(item.kind))
    val clickable = if (onOpen != null) base.clickable(onClickLabel = open, role = Role.Button, onClick = onOpen) else base
    Column(
        modifier = clickable.semantics(mergeDescendants = true) {
            contentDescription = description
        }.padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(status, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun ActiveKind.titleRes(): Int = when (this) {
    ActiveKind.TERRAIN -> R.string.active_terrain
    ActiveKind.LOCALIZATION -> R.string.active_localization
    ActiveKind.BASE_SHIFT -> R.string.active_base_shift
    ActiveKind.SURFACE -> R.string.active_surface
    ActiveKind.ALIGNMENT -> R.string.active_alignment
    ActiveKind.LAYERS -> R.string.active_layers
}

/** El estado de un elemento activo en texto (S059/RF-19). */
@Composable
private fun statusText(status: ActiveStatus, unit: LinearUnit): String {
    val locale = uiLocale()
    return when (status) {
        is ActiveStatus.Factor -> stringResource(R.string.active_status_factor, String.format(locale, "%.6f", status.value))
        is ActiveStatus.Rms ->
            status.horizontalM?.let {
                stringResource(R.string.active_status_rms, "${LengthFormat.inUnit(it, unit, locale)} ${unitSymbol(unit)}")
            }
                ?: stringResource(R.string.active_status_active)
        is ActiveStatus.Name -> status.text
        is ActiveStatus.Count -> stringResource(R.string.active_status_layers, status.visible, status.total)
    }
}
