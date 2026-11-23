package es.topophone.app.ui.field

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick

/**
 * Monta [content] y, si lleva una hoja de campo, la expande: lo que los tests de S010 a S047 buscan (nombre, código, atributos, fotos,
 * avisos de la base…) está en la hoja expandida (S060/RF-20). Sin hoja, o si ya abre expandida, no hace nada más.
 */
fun ComposeContentTestRule.setContentExpanded(content: @Composable () -> Unit) {
    setContent(content)
    val alreadyExpanded = onAllNodesWithTag(FIELD_SHEET_EXPANDED_TAG).fetchSemanticsNodes().isNotEmpty()
    if (!alreadyExpanded && onAllNodesWithTag(FIELD_SHEET_HANDLE_TAG).fetchSemanticsNodes().isNotEmpty()) {
        onNodeWithTag(FIELD_SHEET_HANDLE_TAG).performClick()
    }
}
