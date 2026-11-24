package es.topophone.app.ui.options

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import es.topophone.app.ui.points.OBJECTS_MENU_TAG

/** Abre el menú de la opción (la barra de grupos o el «⋮») y la elige, como hace quien usa la lista de Objetos (S059/RF-1 a RF-6). */
fun ComposeContentTestRule.chooseOption(option: ProjectOption) {
    val opener = if (option.group == OptionGroup.MORE) OBJECTS_MENU_TAG else groupButtonTag(option.group)
    onNodeWithTag(opener).performClick()
    onNodeWithTag(optionTag(option)).performClick()
}
