package es.topophone.app.ui.alignment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.alignment.ChainageFormat
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.project.LinearUnit

const val ALIGNMENT_TOUCH_SHEET_TAG = "alignment_touch_sheet"
const val ALIGNMENT_TOUCH_NAME_TAG = "alignment_touch_name"
const val ALIGNMENT_TOUCH_PK_TAG = "alignment_touch_pk"
const val ALIGNMENT_TOUCH_ELEVATION_TAG = "alignment_touch_elevation"
const val ALIGNMENT_TOUCH_NO_PROFILE_TAG = "alignment_touch_no_profile"
const val ALIGNMENT_TOUCH_ACTIVE_TAG = "alignment_touch_active"
const val ALIGNMENT_TOUCH_ACTIVATE_TAG = "alignment_touch_activate"
const val ALIGNMENT_TOUCH_POINT_TAG = "alignment_touch_point"
const val ALIGNMENT_TOUCH_STAKE_TAG = "alignment_touch_stake"
const val ALIGNMENT_TOUCH_ANALYZE_TAG = "alignment_touch_analyze"
const val ALIGNMENT_TOUCH_SECTIONS_TAG = "alignment_touch_sections"

/**
 * La ficha de un eje que se ha tocado en el mapa (S049/RF-18): su nombre, su longitud, su PK inicial y final, el PK del punto tocado y la
 * cota de su rasante en ese PK si la tiene, con la opción de activarlo (o el aviso de que ya lo está). Los PK y las longitudes van en la
 * unidad del proyecto (RNF-T9). [onCalculate], si se ofrece, abre el cálculo de un punto sobre este eje (RF-20).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlignmentTouchSheet(
    touch: AlignmentTouch,
    unit: LinearUnit,
    unitSymbol: String,
    onActivate: () -> Unit,
    onDismiss: () -> Unit,
    onCalculate: (() -> Unit)? = null,
    onStake: (() -> Unit)? = null,
    onAnalyze: (() -> Unit)? = null,
    onSections: (() -> Unit)? = null,
) {
    val locale = uiLocale()
    val alignment = touch.alignment
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)
                .testTag(ALIGNMENT_TOUCH_SHEET_TAG),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(R.string.alignment_ficha_title), style = MaterialTheme.typography.titleLarge)
            Text(alignment.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag(ALIGNMENT_TOUCH_NAME_TAG))
            Text(
                stringResource(R.string.alignment_ficha_length, LengthFormat.inUnit(alignment.lengthM, unit, locale, 1) + " " + unitSymbol),
            )
            Text(
                stringResource(
                    R.string.alignment_ficha_range,
                    ChainageFormat.format(alignment.startChainage, unit, locale),
                    ChainageFormat.format(alignment.endChainage, unit, locale),
                ),
            )
            Text(
                stringResource(R.string.alignment_ficha_touched, ChainageFormat.format(touch.chainage, unit, locale)),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.testTag(ALIGNMENT_TOUCH_PK_TAG),
            )
            val elevation = touch.elevationM
            if (elevation != null) {
                Text(
                    stringResource(R.string.alignment_ficha_elevation, LengthFormat.inUnit(elevation, unit, locale, 3), unitSymbol),
                    modifier = Modifier.testTag(ALIGNMENT_TOUCH_ELEVATION_TAG),
                )
            } else {
                Text(stringResource(R.string.alignment_ficha_no_profile), modifier = Modifier.testTag(ALIGNMENT_TOUCH_NO_PROFILE_TAG))
            }
            if (touch.active) {
                Text(stringResource(R.string.alignment_ficha_is_active), modifier = Modifier.testTag(ALIGNMENT_TOUCH_ACTIVE_TAG))
            } else {
                Button(
                    onClick = onActivate,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(ALIGNMENT_TOUCH_ACTIVATE_TAG),
                ) { Text(stringResource(R.string.alignment_ficha_activate)) }
            }
            if (onCalculate != null) {
                OutlinedButton(
                    onClick = onCalculate,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(ALIGNMENT_TOUCH_POINT_TAG),
                ) { Text(stringResource(R.string.alignment_point_open)) }
            }
            if (onStake != null) {
                OutlinedButton(
                    onClick = onStake,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(ALIGNMENT_TOUCH_STAKE_TAG),
                ) { Text(stringResource(R.string.alignment_stake_open)) } // S054/RF-1
            }
            if (onAnalyze != null) {
                OutlinedButton(
                    onClick = onAnalyze,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(ALIGNMENT_TOUCH_ANALYZE_TAG),
                ) { Text(stringResource(R.string.alignment_analyze_open)) } // S054/RF-12
            }
            if (onSections != null) {
                OutlinedButton(
                    onClick = onSections,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(ALIGNMENT_TOUCH_SECTIONS_TAG),
                ) { Text(stringResource(R.string.section_open)) } // S050/RF-1
            }
        }
    }
}
