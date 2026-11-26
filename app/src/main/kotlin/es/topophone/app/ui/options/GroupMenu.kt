package es.topophone.app.ui.options

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import es.topophone.core.designsystem.component.MinTouchTarget

fun optionTag(option: ProjectOption) = "option_${option.key}"

/**
 * El menú corto de un grupo, con una fila por opción: icono, nombre y, en «Herramientas», una descripción de una línea (S059/RF-5). Cada
 * fila lleva para TalkBack el verbo y el objeto completos (RF-8). Pulsar fuera o «Atrás» lo cierra sin hacer nada (RF-9).
 */
@Composable
fun GroupMenu(
    group: OptionGroup,
    onSelect: (ProjectOption) -> Unit,
    onDismiss: () -> Unit,
    /** Las filas que no se pueden usar ahora, con el motivo en lugar de su descripción (S062/RF-5). */
    disabled: Map<ProjectOption, Int> = emptyMap(),
) {
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        ProjectOptions.of(group).forEach { option ->
            val presentation = option.presentation()
            val accessibility = stringResource(presentation.accessibilityLabel)
            DropdownMenuItem(
                text = {
                    Column {
                        Text(stringResource(presentation.label), style = MaterialTheme.typography.bodyLarge)
                        (disabled[option] ?: presentation.description)?.let {
                            Text(
                                stringResource(it),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                leadingIcon = { Icon(painterResource(presentation.icon), contentDescription = null, modifier = Modifier.size(24.dp)) },
                onClick = {
                    onDismiss()
                    onSelect(option)
                },
                enabled = option !in disabled,
                modifier = Modifier
                    .heightIn(min = MinTouchTarget)
                    .semantics { contentDescription = accessibility }
                    .testTag(optionTag(option)),
            )
        }
    }
}
