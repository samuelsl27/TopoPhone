package es.topophone.app.ui.field

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.core.designsystem.component.MinTouchTarget

const val FIELD_SHEET_TAG = "field_sheet"
const val FIELD_SHEET_HANDLE_TAG = "field_sheet_handle"
const val FIELD_SHEET_CLOSE_TAG = "field_sheet_close"
const val FIELD_SHEET_EXPANDED_TAG = "field_sheet_expanded"

/** Las dos alturas de la hoja de campo (S060/RF-1). */
enum class FieldSheetValue { Folded, Expanded }

/** Lo que sube el dedo para pasar de una altura a otra; menos es un toque suelto. */
private val DRAG_THRESHOLD = 24.dp

/** La parte expandida no pasa de esta fracción del alto de la ventana (S060/D1). */
private const val EXPANDED_MAX_FRACTION = 0.7f

/**
 * La hoja de campo: una hoja inferior no modal sobre el mapa, con el asa y «Cerrar» arriba, [folded] siempre a la vista y [expanded]
 * debajo cuando está expandida. Se arrastra o se toca el asa; con TalkBack ofrece «Expandir» y «Plegar» (S060/RF-1, RF-3 a RF-6).
 * La altura plegada es la de su contenido; si lo expandido no cabe, solo se desplaza esa parte.
 */
@Composable
fun FieldSheet(
    value: FieldSheetValue,
    onValueChange: (FieldSheetValue) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    folded: @Composable ColumnScope.() -> Unit,
    expanded: @Composable ColumnScope.() -> Unit = {},
) {
    val isExpanded = value == FieldSheetValue.Expanded
    val maxExpanded = (LocalConfiguration.current.screenHeightDp * EXPANDED_MAX_FRACTION).dp
    Surface(
        modifier = modifier.fillMaxWidth().imePadding().testTag(FIELD_SHEET_TAG),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Handle(isExpanded, onValueChange, onClose)
            folded()
            if (isExpanded) {
                Column(
                    modifier = Modifier.heightIn(max = maxExpanded).verticalScroll(rememberScrollState()).testTag(FIELD_SHEET_EXPANDED_TAG),
                ) { expanded() }
            }
            Box(Modifier.size(8.dp))
        }
    }
}

@Composable
private fun Handle(isExpanded: Boolean, onValueChange: (FieldSheetValue) -> Unit, onClose: () -> Unit) {
    val target = if (isExpanded) FieldSheetValue.Folded else FieldSheetValue.Expanded
    val toggleLabel = stringResource(if (isExpanded) R.string.field_sheet_fold else R.string.field_sheet_expand)
    val handleName = stringResource(R.string.field_sheet_handle)
    val stateName = stringResource(if (isExpanded) R.string.field_sheet_state_expanded else R.string.field_sheet_state_folded)
    var dragged by remember { mutableFloatStateOf(0f) }
    val thresholdPx = with(androidx.compose.ui.platform.LocalDensity.current) { DRAG_THRESHOLD.toPx() }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = MinTouchTarget)
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { dragged += it },
                    onDragStarted = { dragged = 0f },
                    onDragStopped = {
                        // Hacia arriba (negativo) expande; hacia abajo pliega.
                        when {
                            dragged <= -thresholdPx -> onValueChange(FieldSheetValue.Expanded)
                            dragged >= thresholdPx -> onValueChange(FieldSheetValue.Folded)
                        }
                        dragged = 0f
                    },
                )
                .clickable(onClickLabel = toggleLabel) { onValueChange(target) }
                .semantics {
                    contentDescription = handleName
                    stateDescription = stateName
                    customActions = listOf(
                        CustomAccessibilityAction(toggleLabel) {
                            onValueChange(target)
                            true
                        },
                    )
                }
                .testTag(FIELD_SHEET_HANDLE_TAG),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(width = 40.dp, height = 4.dp)
                    .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)),
            )
        }
        TextButton(onClick = onClose, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(FIELD_SHEET_CLOSE_TAG)) {
            Text(stringResource(R.string.collector_close))
        }
    }
}
