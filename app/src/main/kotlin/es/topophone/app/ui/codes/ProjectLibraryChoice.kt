package es.topophone.app.ui.codes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.projects.ChoiceGroup
import es.topophone.app.ui.projects.ConfirmDialog
import es.topophone.core.model.codes.LibraryChoice
import es.topophone.data.codes.LibrarySummary

/** Una opción del selector de biblioteca de un proyecto (S023/RF-19): mantener la que lleva, ninguna o una de las bibliotecas. */
sealed interface LibraryOption {
    /** La biblioteca que el proyecto ya lleva; solo existe al editar un proyecto con copia. */
    data class Current(val name: String) : LibraryOption

    data object None : LibraryOption

    data class Pick(val library: LibrarySummary) : LibraryOption
}

const val PROJECT_LIBRARY_CURRENT_TAG = "project_library_current"
const val PROJECT_LIBRARY_NONE_TAG = "project_library_none"
const val PROJECT_LIBRARY_HELP_TAG = "project_library_help"

fun projectLibraryTag(id: String) = "project_library_$id"

private fun tagOf(option: LibraryOption): String = when (option) {
    is LibraryOption.Current -> PROJECT_LIBRARY_CURRENT_TAG
    LibraryOption.None -> PROJECT_LIBRARY_NONE_TAG
    is LibraryOption.Pick -> projectLibraryTag(option.library.id)
}

@Composable
private fun labelOf(option: LibraryOption): String = when (option) {
    is LibraryOption.Current -> stringResource(R.string.project_library_current, option.name)
    LibraryOption.None -> stringResource(R.string.project_library_none)
    is LibraryOption.Pick ->
        if (option.library.isDefault) {
            "${option.library.name} · ${stringResource(
                R.string.codes_library_default_tag,
            )}"
        } else {
            option.library.name
        }
}

/**
 * La biblioteca de códigos de un proyecto (S023/RF-19 a RF-21). Con [linkedName] (al editar un proyecto que lleva copia) se ofrece
 * mantenerla, y cambiarla o quitarla pide confirmación antes de aplicarse; no toca ningún punto. [onChoice] recibe la elección.
 */
@Composable
fun ProjectLibraryChoice(libraries: List<LibrarySummary>, choice: LibraryChoice, linkedName: String?, onChoice: (LibraryChoice) -> Unit) {
    val current = linkedName?.let { LibraryOption.Current(it) }
    val options = listOfNotNull(current, LibraryOption.None) + libraries.map { LibraryOption.Pick(it) }
    val selected: LibraryOption = when (choice) {
        LibraryChoice.Keep -> current ?: LibraryOption.None
        LibraryChoice.None -> LibraryOption.None
        is LibraryChoice.Library -> options.filterIsInstance<LibraryOption.Pick>().firstOrNull { it.library.id == choice.id }
            ?: LibraryOption.None
    }
    // La confirmación pendiente se guarda como texto para que sobreviva al giro: «» quita, «id» cambia.
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    Column {
        ChoiceGroup(stringResource(R.string.project_library), options, selected, ::tagOf, { labelOf(it) }) { option ->
            when {
                option == selected -> Unit
                option is LibraryOption.Current -> onChoice(LibraryChoice.Keep)
                // RF-21: cambiar o quitar la biblioteca vinculada pide confirmación.
                current != null -> pending = (option as? LibraryOption.Pick)?.library?.id.orEmpty()
                option is LibraryOption.Pick -> onChoice(LibraryChoice.Library(option.library.id))
                else -> onChoice(LibraryChoice.None)
            }
        }
        Text(
            stringResource(R.string.project_library_help),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    pending?.let { id ->
        val target = libraries.firstOrNull { it.id == id }
        ConfirmDialog(
            message = if (target == null) {
                stringResource(R.string.project_library_remove_message)
            } else {
                stringResource(R.string.project_library_change_message, target.name)
            },
            confirm = stringResource(
                if (target ==
                    null
                ) {
                    R.string.project_library_remove_confirm
                } else {
                    R.string.project_library_change_confirm
                },
            ),
            onConfirm = {
                pending = null
                onChoice(if (target == null) LibraryChoice.None else LibraryChoice.Library(target.id))
            },
            onDismiss = { pending = null },
        )
    }
}
