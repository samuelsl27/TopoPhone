package es.topophone.app.ui.baseshift

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.core.designsystem.component.MinTouchTarget

const val BASE_SHIFT_BANNER_TAG = "base_shift_banner"
const val BASE_SHIFT_BANNER_DEACTIVATE_TAG = "base_shift_banner_deactivate"

/**
 * El aviso «Base shift activo» del recolector y del replanteo, con el nombre del punto conocido y un botón para desactivarlo desde allí
 * (S020/RF-11). El estado va en texto, no solo en color (RNF-T8).
 */
@Composable
fun BaseShiftActiveBanner(knownName: String, onDeactivate: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().testTag(BASE_SHIFT_BANNER_TAG),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.base_shift_active_banner, knownName),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                onClick = onDeactivate,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(BASE_SHIFT_BANNER_DEACTIVATE_TAG),
            ) { Text(stringResource(R.string.base_shift_deactivate)) }
        }
    }
}

const val BASE_SHIFT_BASE_CHANGED_TAG = "base_shift_base_changed"
const val BASE_SHIFT_BASE_CHANGED_DEACTIVATE_TAG = "base_shift_base_changed_deactivate"
const val BASE_SHIFT_BASE_CHANGED_REPEAT_TAG = "base_shift_base_changed_repeat"
const val BASE_SHIFT_DATUM_NOTICE_TAG = "base_shift_datum_notice"
const val BASE_SHIFT_DATUM_ACK_TAG = "base_shift_datum_ack"

/**
 * La base ha cambiado con un base shift activo: puede haber dejado de valer; se ofrece desactivarlo o repetirlo (S020/RF-15). Va en
 * texto además de en color (RNF-T8).
 */
@Composable
fun BaseShiftBaseChangedBanner(knownName: String, onDeactivate: () -> Unit, onRepeat: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().testTag(BASE_SHIFT_BASE_CHANGED_TAG),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.base_shift_base_changed, knownName),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                onClick = onDeactivate,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(BASE_SHIFT_BASE_CHANGED_DEACTIVATE_TAG),
            ) { Text(stringResource(R.string.base_shift_deactivate)) }
            OutlinedButton(
                onClick = onRepeat,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(BASE_SHIFT_BASE_CHANGED_REPEAT_TAG),
            ) { Text(stringResource(R.string.base_shift_repeat)) }
        }
    }
}

/** Un cambio de datum de las correcciones desactivó el base shift: se indica hasta que el usuario lo ve (S020/RF-16). */
@Composable
fun BaseShiftDatumBanner(knownName: String, onAcknowledge: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().testTag(BASE_SHIFT_DATUM_NOTICE_TAG),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.base_shift_datum_notice, knownName),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                onClick = onAcknowledge,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(BASE_SHIFT_DATUM_ACK_TAG),
            ) { Text(stringResource(R.string.base_shift_understood)) }
        }
    }
}
