package es.topophone.app.ui.receiver

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import es.topophone.app.R
import es.topophone.app.receiver.CurrentGear
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.survey.ActiveGear
import es.topophone.core.survey.SavedPole

const val GEAR_SELECTOR_TAG = "gear_selector"
const val GEAR_SELECTOR_COMPENSATED_TAG = "gear_selector_compensated"
const val GEAR_SELECTOR_PROBLEM_TAG = "gear_selector_problem"
const val GEAR_SELECTOR_LOCKED_TAG = "gear_selector_locked"
const val GEAR_SELECTOR_NOTICE_TAG = "gear_selector_notice"

fun gearSelectorItemTag(id: String) = "gear_selector_item_$id"

/**
 * Lo que necesita el selector rápido del jalón activo (S046/RF-14, RF-15, RF-22, RF-27).
 *
 * @property locked hay una medida o el modo automático en curso: no se cambia de jalón.
 * @property receiverCompensates el modo es "Compensada por el receptor": la altura de antena de la app es solo un dato.
 * @property changedNotice el equipo cambió desde el último punto guardado: afecta solo a los siguientes.
 */
data class GearSelectorUi(
    val gear: ActiveGear = CurrentGear.DEFAULT,
    val poles: List<SavedPole> = emptyList(),
    val locked: Boolean = false,
    val receiverCompensates: Boolean = false,
    val changedNotice: Boolean = false,
)

/**
 * El jalón activo con su altura vertical y la de antena, y la lista de jalones guardados para cambiar de uno a otro con dos
 * toques (S046/RF-14, RF-15). Con el receptor compensando lo dice (RF-22); con una medida en curso no se abre (RF-27).
 */
@Composable
fun GearSelector(ui: GearSelectorUi, onSelectPole: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val locale = uiLocale()
    val gear = ui.gear
    val pole = gear.pole
    val summary = if (pole == null || gear.antennaHeightM == null) {
        stringResource(R.string.gear_selector_none)
    } else {
        stringResource(
            R.string.gear_selector_summary,
            pole.name,
            LengthFormat.metres(gear.verticalPoleM ?: 0.0, locale),
            LengthFormat.metres(gear.antennaHeightM ?: 0.0, locale),
        )
    }
    val description = if (pole == null || gear.antennaHeightM == null) {
        summary
    } else {
        stringResource(R.string.gear_selector_description, pole.name, LengthFormat.metres(gear.antennaHeightM ?: 0.0, locale))
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .clickable(enabled = !ui.locked && ui.poles.isNotEmpty(), role = Role.DropdownList) { open = true }
                .semantics { contentDescription = description }
                .testTag(GEAR_SELECTOR_TAG),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(summary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ui.poles.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                R.string.gear_selector_item,
                                item.name,
                                LengthFormat.metres(item.enteredM, locale),
                            ),
                        )
                    },
                    onClick = {
                        open = false
                        onSelectPole(item.id)
                    },
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(gearSelectorItemTag(item.id)),
                )
            }
        }
        gear.problem?.let {
            Text(gearProblemText(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(GEAR_SELECTOR_PROBLEM_TAG))
        }
        if (ui.locked) Text(stringResource(R.string.gear_locked), modifier = Modifier.testTag(GEAR_SELECTOR_LOCKED_TAG))
        if (ui.receiverCompensates) {
            Text(stringResource(R.string.gear_selector_compensated), modifier = Modifier.testTag(GEAR_SELECTOR_COMPENSATED_TAG))
        }
        if (ui.changedNotice) {
            Text(stringResource(R.string.gear_selector_changed), modifier = Modifier.testTag(GEAR_SELECTOR_NOTICE_TAG))
        }
    }
}
