package es.topophone.app.ui.options

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import es.topophone.core.designsystem.component.MinTouchTarget

fun groupButtonTag(group: OptionGroup) = "options_group_${group.name.lowercase()}"

/** Los grupos que tienen botón en la barra, en su orden (S059/RF-1). */
val BAR_GROUPS = listOf(OptionGroup.IMPORT, OptionGroup.EXPORT, OptionGroup.TOOLS)

/**
 * La barra de grupos de la lista de Objetos: tres botones de igual ancho con icono y texto, «Importar», «Exportar» y «Herramientas»
 * (S059/RF-1). Con la letra ampliada el texto pasa a una segunda línea y no se corta. [menu] pinta el menú del grupo que está abierto,
 * anclado a su botón.
 */
@Composable
fun GroupBar(modifier: Modifier = Modifier, menu: @Composable (group: OptionGroup, dismiss: () -> Unit) -> Unit = { _, _ -> }) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BAR_GROUPS.forEach { group ->
            Box(modifier = Modifier.weight(1f)) {
                GroupButton(group, onClick = { open = group.name })
                if (open == group.name) menu(group) { open = null }
            }
        }
    }
}

@Composable
private fun GroupButton(group: OptionGroup, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(groupButtonTag(group)),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(group.iconRes()), contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                stringResource(group.labelRes()),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}
