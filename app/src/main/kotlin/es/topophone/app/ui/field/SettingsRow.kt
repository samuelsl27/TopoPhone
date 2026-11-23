package es.topophone.app.ui.field

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.core.designsystem.component.MinTouchTarget

/** Lo que dice una pastilla: el nombre del ajuste, su valor actual en texto y qué hace al tocarla (S060/RF-9, RF-27). */
data class SettingPillData(val label: String, val value: String, val tag: String, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * Una pastilla: 56 dp como mínimo, el nombre pequeño arriba y el valor debajo, y rol de botón con «nombre, valor» para TalkBack. El
 * texto se parte en líneas antes que cortarse (S060/RNF-T8).
 */
@Composable
fun SettingPill(data: SettingPillData, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.field_pill_description, data.label, data.value)
    Surface(
        onClick = data.onClick,
        enabled = data.enabled,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .heightIn(min = MinTouchTarget)
            .widthIn(min = PILL_MIN_WIDTH)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
            }
            .testTag(data.tag),
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.Center) {
            Text(data.label, style = MaterialTheme.typography.labelSmall)
            Text(data.value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * La fila de ajustes: las pastillas a partes iguales en una fila y, si no caben (poco ancho o letra grande), en dos filas
 * sin cortar nada (S060/RF-9, RF-27, RNF específicos).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsRow(pills: List<SettingPillData>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        pills.forEach { SettingPill(it, Modifier.weight(1f)) }
    }
}

private val PILL_MIN_WIDTH = 72.dp
