package es.topophone.app.ui.points

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import es.topophone.app.R
import es.topophone.core.designsystem.component.MinTouchTarget

const val OBJECTS_TAB_POINTS_TAG = "objects_tab_points"
const val OBJECTS_TAB_LINES_TAG = "objects_tab_lines"

/** Las dos pestañas de la lista de Objetos (S059/RF-11). */
enum class ObjectTab { POINTS, LINES }

/**
 * Las pestañas «Puntos» y «Líneas y polígonos», cada una con el número de elementos que contiene (S059/RF-11). Cada pestaña mide
 * 56 dp (RNF-T8).
 */
@Composable
fun ObjectTabs(selected: ObjectTab, onSelect: (ObjectTab) -> Unit, pointCount: Int, lineCount: Int, modifier: Modifier = Modifier) {
    TabRow(selectedTabIndex = selected.ordinal, modifier = modifier) {
        Tab(
            selected = selected == ObjectTab.POINTS,
            onClick = { onSelect(ObjectTab.POINTS) },
            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(OBJECTS_TAB_POINTS_TAG),
            text = { Text(stringResource(R.string.objects_tab_points, pointCount)) },
        )
        Tab(
            selected = selected == ObjectTab.LINES,
            onClick = { onSelect(ObjectTab.LINES) },
            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(OBJECTS_TAB_LINES_TAG),
            text = { Text(stringResource(R.string.objects_tab_lines, lineCount)) },
        )
    }
}
