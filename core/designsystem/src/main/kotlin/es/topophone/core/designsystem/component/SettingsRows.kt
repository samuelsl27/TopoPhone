package es.topophone.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Título de un grupo de ajustes. */
@Composable
fun SettingsSectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

/** Fila de ajuste pulsable, con el valor actual debajo del título si lo hay (≥ 56 dp, RNF-T8). */
@Composable
fun SettingsItem(title: String, onClick: () -> Unit, modifier: Modifier = Modifier, value: String? = null) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = value?.let { { Text(it) } },
        modifier = modifier.heightIn(min = MinTouchTarget).clickable(role = Role.Button, onClick = onClick),
    )
}
