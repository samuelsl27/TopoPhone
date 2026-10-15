package es.topophone.core.designsystem.component

import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import es.topophone.core.designsystem.R

/** Tamaño mínimo de cualquier zona táctil, pensado para guantes (RNF-T8). */
val MinTouchTarget = 56.dp

const val SUN_TOGGLE_TAG = "sun_toggle"
const val NAVIGATE_BACK_TAG = "navigate_back"

/**
 * Barra superior de las pantallas, siempre con el acceso rápido al modo sol (S001/RF-11).
 *
 * @param onNavigateBack si no es nulo, muestra la flecha para volver.
 * @param statusIndicator si no es nulo, se pinta a la derecha, delante del modo sol. Las acciones se
 *   miden antes que el título, así que es el título el que se acorta si no cabe todo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopoPhoneTopBar(
    title: String,
    sunModeActive: Boolean,
    onToggleSunMode: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateBack: (() -> Unit)? = null,
    statusIndicator: (@Composable () -> Unit)? = null,
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier,
        navigationIcon = {
            if (onNavigateBack != null) {
                IconButton(onClick = onNavigateBack, modifier = Modifier.size(MinTouchTarget).testTag(NAVIGATE_BACK_TAG)) {
                    Icon(painterResource(R.drawable.ds_ic_back), contentDescription = stringResource(R.string.ds_navigate_back))
                }
            }
        },
        actions = {
            statusIndicator?.invoke()
            IconButton(onClick = onToggleSunMode, modifier = Modifier.size(MinTouchTarget).testTag(SUN_TOGGLE_TAG)) {
                Icon(
                    painterResource(R.drawable.ds_ic_sun),
                    contentDescription = stringResource(if (sunModeActive) R.string.ds_sun_mode_off else R.string.ds_sun_mode_on),
                )
            }
        },
    )
}
